package com.fromwau.kortex.hyprland

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class HyprlandTest {
    private val fake = FakeHyprland().apply {
        answers[WORKSPACES] = Recorded.workspaces
        answers[MONITORS] = Recorded.monitors
        answers[ACTIVE_WINDOW] = Recorded.activeWindow
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val hyprland = Hyprland(scope, fake.instance, RETRY)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        fake.close()
    }

    @Test
    fun theFirstValueSaysNothingIsConnectedYet() {
        assertEquals(Err(HyprlandError.NotConnected), hyprland.workspaces.value)
        assertEquals(Err(HyprlandError.NotConnected), hyprland.activeWindow.value)
    }

    @Test
    fun bothFlowsReadOnceListening() = runBlocking {
        val workspaces = hyprland.workspaces.awaitOk()
        val window = hyprland.activeWindow.awaitOk()

        assertEquals(listOf(-99, 1, 3), workspaces.all.map { it.id.value })
        assertEquals(WindowAddress("0x55d7c479f890"), window?.address)
    }

    @Test
    fun aWorkspaceEventReadsTheWorkspacesAgain() = runBlocking {
        watching(hyprland.workspaces) {
            hyprland.workspaces.awaitOk()
            fake.answers[MONITORS] = monitorsShowing(3)

            fake.emit("workspace>>3", "workspacev2>>3,3")

            hyprland.workspaces.awaitOk { it.active["HDMI-A-2"] == WorkspaceId(3) }
        }
    }

    @Test
    fun titleEventsLeaveTheWorkspacesAlone() = runBlocking {
        watching(hyprland.workspaces) {
            hyprland.workspaces.awaitOk()
            val before = fake.requestsOf(WORKSPACES)
            fake.answers[MONITORS] = monitorsShowing(3)

            // The last event is the one that must refresh, so once it has, every title before it was seen.
            fake.emit(
                "windowtitlev2>>abc,one",
                "activewindowv2>>abc",
                "windowtitlev2>>abc,two",
                "focusedmonv2>>HDMI-A-2,3",
            )
            hyprland.workspaces.awaitOk { it.active["HDMI-A-2"] == WorkspaceId(3) }

            assertEquals(before + 1, fake.requestsOf(WORKSPACES))
        }
    }

    @Test
    fun aBurstOfEventsIsReadOnceItHasArrived() = runBlocking {
        watching(hyprland.workspaces) {
            hyprland.workspaces.awaitOk()
            val before = fake.requestsOf(WORKSPACES)
            fake.hold()

            fake.emit("workspacev2>>8,8")
            awaitTrue { fake.requestsOf(WORKSPACES) == before + 1 }
            // What moving one window to another workspace sent on Hyprland 0.56.2, all in one millisecond.
            fake.emit(
                "createworkspacev2>>8,8", "movewindowv2>>a,8,8", "activewindowv2>>a", "workspacev2>>8,8",
                "activewindowv2>>a", "destroyworkspacev2>>9,9",
            )
            fake.release()

            awaitTrue { fake.requestsOf(WORKSPACES) == before + 2 }
            Thread.sleep(SETTLE.inWholeMilliseconds)
            assertEquals(before + 2, fake.requestsOf(WORKSPACES), "one read for the burst, not one per event")
        }
    }

    @Test
    fun aFocusEventReadsTheWindowAgain() = runBlocking {
        watching(hyprland.activeWindow) {
            hyprland.activeWindow.awaitOk()
            fake.answers[ACTIVE_WINDOW] = "{}"

            // Hyprland's own answer when focus goes to nothing: both events, with empty data.
            fake.emit("activewindow>>,", "activewindowv2>>")

            hyprland.activeWindow.awaitOk { it == null }
        }
    }

    @Test
    fun nothingFocusedIsNull() = runBlocking {
        fake.answers[ACTIVE_WINDOW] = "{}"

        assertEquals(null, hyprland.activeWindow.awaitOk())
    }

    @Test
    fun aRequestHyprlandRefusesIsCarriedAsTheRefusal() = runBlocking {
        fake.answers.remove(ACTIVE_WINDOW)

        assertEquals(HyprlandError.Refused(ACTIVE_WINDOW, "unknown request"), hyprland.activeWindow.firstError())
    }

    @Test
    fun aDroppedEventSocketIsReportedAndThenReattached() = runBlocking {
        val seen = CopyOnWriteArrayList<Result<Workspaces, HyprlandError>>()
        val watcher = launch(Dispatchers.Default) { hyprland.workspaces.collect { seen += it } }
        hyprland.workspaces.awaitOk()
        awaitTrue { fake.listening == 1 }

        fake.dropListeners()
        awaitTrue { Err(HyprlandError.Disconnected) in seen }
        awaitTrue { fake.listening == 1 }
        hyprland.workspaces.awaitOk()

        watcher.cancel()
        assertIs<Ok<Workspaces>>(seen.last(), "saw $seen")
    }

    @Test
    fun noSocketIsSaidWithThePathItLookedAt() = runBlocking {
        val empty = HyprlandInstance(Files.createTempDirectory("kortex-no-hyprland").toString())
        val absent = Hyprland(scope, empty, RETRY)

        assertEquals(HyprlandError.NoSocket(empty.events), absent.workspaces.firstError())
    }

    @Test
    fun theEnvironmentNamesTheFolderOrSaysThereIsNoInstance() {
        assertEquals(Err(HyprlandError.NoInstance), HyprlandInstance.fromEnvironment(emptyMap()))
        assertEquals(
            Err(HyprlandError.NoInstance),
            HyprlandInstance.fromEnvironment(mapOf("XDG_RUNTIME_DIR" to "/run/user/1000")),
        )
        assertEquals(
            Ok(HyprlandInstance("/run/user/1000/hypr/abc_1_2")),
            HyprlandInstance.fromEnvironment(
                mapOf("XDG_RUNTIME_DIR" to "/run/user/1000", "HYPRLAND_INSTANCE_SIGNATURE" to "abc_1_2"),
            ),
        )
    }

    /** The recorded monitors, with the one monitor showing workspace [id] instead of 1. */
    private fun monitorsShowing(id: Int): String = Recorded.monitors.replace(
        "\"activeWorkspace\": {\n        \"id\": 1,\n        \"name\": \"1\"",
        "\"activeWorkspace\": {\n        \"id\": $id,\n        \"name\": \"$id\"",
    )

    private suspend fun <T> StateFlow<Result<T, HyprlandError>>.awaitOk(until: (T) -> Boolean = { true }): T =
        withTimeout(TIMEOUT) { (first { it is Ok && until(it.value) } as Ok).value }

    /** The first error other than the one every flow starts with. */
    private suspend fun <T> StateFlow<Result<T, HyprlandError>>.firstError(): HyprlandError = withTimeout(TIMEOUT) {
        (first { it is Err && it.error != HyprlandError.NotConnected } as Err).error
    }

    /** Keeps one collector on [flow] for [block], so the flow stays live between the reads inside it. */
    private suspend fun watching(
        flow: StateFlow<*>,
        block: suspend () -> Unit,
    ) = coroutineScope {
        val watcher = launch(Dispatchers.Default) { flow.collect {} }
        try {
            block()
        } finally {
            watcher.cancel()
        }
    }

    private suspend fun awaitTrue(condition: () -> Boolean) = withTimeout(TIMEOUT) {
        while (!condition()) delay(10.milliseconds)
    }

    private companion object {
        val RETRY = 50.milliseconds
        val TIMEOUT = 5.seconds
        val SETTLE = 200.milliseconds
    }
}
