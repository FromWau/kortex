package com.fromwau.kortex.hyprland

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.getOrNull
import com.fromwau.kern.result.assertError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Against the Hyprland this suite runs under, checked against what `hyprctl` reports.
 *
 * The workspace test drives the desktop: it switches to a workspace nobody is using, renames it and
 * switches back, restoring focus to the window that had it, so it needs the desktop to itself.
 */
class HyprlandLiveTest {
    private val instance = checkNotNull(HyprlandInstance.fromEnvironment().getOrNull()) { "not running under Hyprland" }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val hyprland = Hyprland(scope, instance)

    @AfterTest
    fun tearDown() = scope.cancel()

    @Test
    fun theFlowsReadWhatHyprctlReports() = runBlocking {
        val workspaces = hyprland.monitors.awaitOk()
        val window = hyprland.activeWindow.awaitOk()
        val submap = hyprland.submap.awaitOk()
        val layout = hyprland.keyboardLayout.awaitOk()

        val listed = hyprctl("workspaces").jsonArray().map { it.jsonObject }
        assertEquals(
            listed.map { it.int("id") }.sorted(),
            workspaces.allWorkspaces.map { it.id.value },
        )
        assertEquals(
            listed.associate { it.int("id") to it.text("name") },
            workspaces.allWorkspaces.associate { it.id.value to it.name },
        )
        val monitors = hyprctl("monitors").jsonArray().map { it.jsonObject }
        assertEquals(
            monitors.associate { it.text("name") to it.getValue("activeWorkspace").jsonObject.int("id") },
            workspaces.associate { it.name to it.active.value },
        )
        assertEquals(
            listed.groupBy({ it.text("monitor") }, { it.int("id") }).mapValues { it.value.sorted() },
            workspaces.associate { monitor -> monitor.name to monitor.workspaces.map { it.id.value } },
            "each workspace on the monitor hyprctl names",
        )
        assertEquals(
            monitors.associate { it.text("name") to it.getValue("specialWorkspace").jsonObject.int("id") },
            workspaces.associate { it.name to (it.special?.value ?: 0) },
        )

        assertEquals(Json.parseToJsonElement(hyprctl("submap")).jsonPrimitive.content, submap ?: "default")
        val main = hyprctl("devices").let { Json.parseToJsonElement(it).jsonObject.getValue("keyboards") as JsonArray }
            .map { it.jsonObject }
            .single { it.getValue("main").jsonPrimitive.content == "true" }
        assertEquals(main.text("name"), layout?.keyboard)
        assertEquals(main.text("active_keymap"), layout?.keymap)

        val focused = Json.parseToJsonElement(hyprctl("activewindow")).jsonObject
        assertEquals(focused["address"]?.jsonPrimitive?.content, window?.address?.value)
    }

    @Test
    fun theFlowsFollowAWorkspaceSwitchARenameAndTheWayBack() = runBlocking {
        val start = hyprland.monitors.awaitOk()
        val focusedBefore = hyprland.activeWindow.awaitOk()
        val monitor = start.focused.name
        val returnTo = checkNotNull(start.activeOn(monitor))
        val taken = start.allWorkspaces.map { it.id.value }.toSet()
        val spare = generateSequence(SPARE_FROM) { it + 1 }.first { it !in taken }

        watching(hyprland.monitors, hyprland.activeWindow) {
            try {
                assertEquals(Ok(Unit), hyprland.focusWorkspace(spare))
                hyprland.monitors.awaitOk { it.activeOn(monitor) == WorkspaceId(spare) }
                hyprland.activeWindow.awaitOk { it == null }

                // A comma, because event data is comma separated and a name may carry one.
                assertEquals(
                    Ok(Unit),
                    hyprland.dispatch("hl.dsp.workspace.rename({ workspace = \"$spare\", name = \"kortex,probe\" })"),
                )
                hyprland.monitors.awaitOk { now ->
                    now.allWorkspaces.any { it.id.value == spare && it.name == "kortex,probe" }
                }
            } finally {
                start.allWorkspaces.firstOrNull { it.id == returnTo }?.let { hyprland.focusWorkspace(it) }
                focusedBefore?.let { hyprland.dispatch("hl.dsp.focus({ window = \"address:${it.address.value}\" })") }
            }

            hyprland.monitors.awaitOk { now ->
                now.activeOn(monitor) == returnTo && now.allWorkspaces.none { it.id.value == spare }
            }
            hyprland.activeWindow.awaitOk { it?.address == focusedBefore?.address }
        }
    }

    @Test
    fun aNamedWorkspaceIsFocusedByTheWorkspaceTheFlowListed() = runBlocking {
        val start = hyprland.monitors.awaitOk()
        val focusedBefore = hyprland.activeWindow.awaitOk()
        val monitor = start.focused.name
        val returnTo = checkNotNull(start.allWorkspaces.firstOrNull { it.id == start.activeOn(monitor) })

        watching(hyprland.monitors, hyprland.activeWindow) {
            try {
                assertEquals(Ok(Unit), hyprland.dispatch("hl.dsp.focus({ workspace = \"name:$PROBE\" })"))
                val named = hyprland.monitors.awaitOk { now -> now.allWorkspaces.any { it.name == PROBE } }
                    .allWorkspaces
                    .single { it.name == PROBE }
                assertTrue(named.id.value < 0 && !named.special, "a name: workspace got the id ${named.id}")

                // Away and back, so the second switch is the selector built from the listed workspace.
                assertEquals(Ok(Unit), hyprland.focusWorkspace(returnTo))
                hyprland.monitors.awaitOk { it.activeOn(monitor) == returnTo.id }
                assertEquals(Ok(Unit), hyprland.focusWorkspace(named))
                hyprland.monitors.awaitOk { it.activeOn(monitor) == named.id }
            } finally {
                hyprland.focusWorkspace(returnTo)
                focusedBefore?.let { hyprland.dispatch("hl.dsp.focus({ window = \"address:${it.address.value}\" })") }
            }

            hyprland.monitors.awaitOk { now ->
                now.activeOn(monitor) == returnTo.id && now.allWorkspaces.none { it.name == PROBE }
            }
        }
    }

    @Test
    fun luaHyprlandCannotRunIsRefusedWithLuasOwnError() = runBlocking {
        val refused = hyprland.dispatch("hl.dsp.nope(")

        val answer = refused.assertError<HyprlandError.Refused>().answer
        assertTrue(answer.startsWith("error:"), answer)
    }

    private suspend fun <T> StateFlow<Result<T, HyprlandError>>.awaitOk(until: (T) -> Boolean = { true }): T =
        withTimeout(TIMEOUT) { (first { it is Ok && until(it.value) } as Ok).value }

    private suspend fun watching(
        vararg flows: StateFlow<*>,
        block: suspend () -> Unit,
    ) = coroutineScope {
        val watchers = flows.map { flow -> launch { flow.collect {} } }
        try {
            block()
        } finally {
            watchers.forEach { it.cancel() }
        }
    }

    private fun hyprctl(command: String): String {
        val process = ProcessBuilder("hyprctl", "-j", command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "hyprctl -j $command: $output" }
        return output
    }

    private fun String.jsonArray(): JsonArray = Json.parseToJsonElement(this) as JsonArray

    private fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

    private companion object {
        val TIMEOUT = 5.seconds

        /** Far enough above what a person binds to keys that the first free one is free in practice too. */
        const val SPARE_FROM = 77

        /** Named so it cannot be one of somebody's own. */
        const val PROBE = "kortex-probe-named"
    }
}
