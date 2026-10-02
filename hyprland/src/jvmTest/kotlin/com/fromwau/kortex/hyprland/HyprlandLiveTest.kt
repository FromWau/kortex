package com.fromwau.kortex.hyprland

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.getOrNull
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
import kotlin.test.assertIs
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
        val workspaces = hyprland.workspaces.awaitOk()
        val window = hyprland.activeWindow.awaitOk()

        val listed = hyprctl("workspaces").jsonArray().map { it.jsonObject }
        assertEquals(
            listed.map { it.int("id") }.sorted(),
            workspaces.all.map { it.id.value },
        )
        assertEquals(
            listed.associate { it.int("id") to it.text("name") },
            workspaces.all.associate { it.id.value to it.name },
        )
        assertEquals(
            hyprctl("monitors").jsonArray().associate {
                it.jsonObject.text("name") to it.jsonObject.getValue("activeWorkspace").jsonObject.int("id")
            },
            workspaces.active.mapValues { it.value.value },
        )

        val focused = Json.parseToJsonElement(hyprctl("activewindow")).jsonObject
        assertEquals(focused["address"]?.jsonPrimitive?.content, window?.address?.value)
    }

    @Test
    fun theFlowsFollowAWorkspaceSwitchARenameAndTheWayBack() = runBlocking {
        val start = hyprland.workspaces.awaitOk()
        val focusedBefore = hyprland.activeWindow.awaitOk()
        val monitor = checkNotNull(start.focusedMonitor)
        val returnTo = checkNotNull(start.active[monitor])
        val spare = generateSequence(SPARE_FROM) { it + 1 }.first { id -> start.all.none { it.id.value == id } }

        watching(hyprland.workspaces, hyprland.activeWindow) {
            try {
                assertEquals(Ok(Unit), hyprland.focusWorkspace(spare))
                hyprland.workspaces.awaitOk { it.active[monitor] == WorkspaceId(spare) }
                hyprland.activeWindow.awaitOk { it == null }

                // A comma, because event data is comma separated and a name may carry one.
                assertEquals(
                    Ok(Unit),
                    hyprland.dispatch("hl.dsp.workspace.rename({ workspace = \"$spare\", name = \"kortex,probe\" })"),
                )
                hyprland.workspaces.awaitOk { all -> all.all.any { it.id.value == spare && it.name == "kortex,probe" } }
            } finally {
                start.all.firstOrNull { it.id == returnTo }?.let { hyprland.focusWorkspace(it) }
                focusedBefore?.let { hyprland.dispatch("hl.dsp.focus({ window = \"address:${it.address.value}\" })") }
            }

            hyprland.workspaces.awaitOk { now ->
                now.active[monitor] == returnTo && now.all.none { it.id.value == spare }
            }
            hyprland.activeWindow.awaitOk { it?.address == focusedBefore?.address }
        }
    }

    @Test
    fun aNamedWorkspaceIsFocusedByTheWorkspaceTheFlowListed() = runBlocking {
        val start = hyprland.workspaces.awaitOk()
        val focusedBefore = hyprland.activeWindow.awaitOk()
        val monitor = checkNotNull(start.focusedMonitor)
        val returnTo = checkNotNull(start.all.firstOrNull { it.id == start.active[monitor] })

        watching(hyprland.workspaces, hyprland.activeWindow) {
            try {
                assertEquals(Ok(Unit), hyprland.dispatch("hl.dsp.focus({ workspace = \"name:$PROBE\" })"))
                val named = hyprland.workspaces.awaitOk { now -> now.all.any { it.name == PROBE } }
                    .all
                    .single { it.name == PROBE }
                assertTrue(named.id.value < 0 && !named.special, "a name: workspace got the id ${named.id}")

                // Away and back, so the second switch is the selector built from the listed workspace.
                assertEquals(Ok(Unit), hyprland.focusWorkspace(returnTo))
                hyprland.workspaces.awaitOk { it.active[monitor] == returnTo.id }
                assertEquals(Ok(Unit), hyprland.focusWorkspace(named))
                hyprland.workspaces.awaitOk { it.active[monitor] == named.id }
            } finally {
                hyprland.focusWorkspace(returnTo)
                focusedBefore?.let { hyprland.dispatch("hl.dsp.focus({ window = \"address:${it.address.value}\" })") }
            }

            hyprland.workspaces.awaitOk { now ->
                now.active[monitor] == returnTo.id && now.all.none { it.name == PROBE }
            }
        }
    }

    @Test
    fun luaHyprlandCannotRunIsRefusedWithLuasOwnError() = runBlocking {
        val refused = hyprland.dispatch("hl.dsp.nope(")

        val answer = assertIs<HyprlandError.Refused>((refused as Err).error).answer
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
