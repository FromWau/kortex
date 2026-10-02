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
        answers[SUBMAP] = Recorded.submap
        answers[DEVICES] = Recorded.devices
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
        assertEquals(Err(HyprlandError.NotConnected), hyprland.monitors.value)
        assertEquals(Err(HyprlandError.NotConnected), hyprland.activeWindow.value)
    }

    @Test
    fun bothFlowsReadOnceListening() = runBlocking {
        val workspaces = hyprland.monitors.awaitOk()
        val window = hyprland.activeWindow.awaitOk()

        assertEquals(listOf(-99, 1, 3), workspaces.allWorkspaces.map { it.id.value })
        assertEquals(WindowAddress("0x55d7c479f890"), window?.address)
    }

    @Test
    fun aWorkspaceEventReadsTheWorkspacesAgain() = runBlocking {
        watching(hyprland.monitors) {
            hyprland.monitors.awaitOk()
            fake.answers[MONITORS] = monitorsShowing(3)

            fake.emit("workspace>>3", "workspacev2>>3,3")

            hyprland.monitors.awaitOk { it.activeOn("HDMI-A-2") == WorkspaceId(3) }
        }
    }

    @Test
    fun titleEventsLeaveTheWorkspacesAlone() = runBlocking {
        watching(hyprland.monitors) {
            hyprland.monitors.awaitOk()
            val before = fake.requestsOf(WORKSPACES)
            fake.answers[MONITORS] = monitorsShowing(3)

            // The last event is the one that must refresh, so once it has, every title before it was seen.
            fake.emit(
                "windowtitlev2>>abc,one",
                "activewindowv2>>abc",
                "windowtitlev2>>abc,two",
                "focusedmonv2>>HDMI-A-2,3",
            )
            hyprland.monitors.awaitOk { it.activeOn("HDMI-A-2") == WorkspaceId(3) }

            assertEquals(before + 1, fake.requestsOf(WORKSPACES))
        }
    }

    @Test
    fun aBurstOfEventsIsReadOnceItHasArrived() = runBlocking {
        watching(hyprland.monitors) {
            hyprland.monitors.awaitOk()
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
    fun aWorkspaceMovedToAnotherMonitorIsFoundOnIt() = runBlocking {
        fake.answers[MONITORS] = TWO_MONITORS
        fake.answers[WORKSPACES] = workspacesOn(three = "DP-1")
        watching(hyprland.monitors) {
            hyprland.monitors.awaitOk { now -> now.single { it.name == "DP-1" }.workspaces.any { it.id.value == 3 } }

            fake.answers[WORKSPACES] = workspacesOn(three = "DP-2")
            fake.emit("moveworkspacev2>>3,3,DP-2")

            hyprland.monitors.awaitOk { now ->
                now.single { it.name == "DP-2" }.workspaces.any { it.id.value == 3 } &&
                    now.single { it.name == "DP-1" }.workspaces.none { it.id.value == 3 }
            }
        }
    }

    @Test
    fun aWindowAskingForAttentionMarksItsWorkspaceUntilItHasFocus() = runBlocking {
        fake.answers[CLIENTS] = """[{"address": "0x55d7c61e2a20", "workspace": {"id": 3, "name": "3"}}]"""
        watching(hyprland.monitors) {
            hyprland.monitors.awaitOk()
            assertEquals(0, fake.requestsOf(CLIENTS), "nothing is urgent, so nothing asks which workspace")

            fake.emit("urgent>>55d7c61e2a20")
            hyprland.monitors.awaitOk { now -> now.allWorkspaces.single { it.id.value == 3 }.urgent }

            fake.emit("activewindow>>kitty,t", "activewindowv2>>55d7c61e2a20")
            hyprland.monitors.awaitOk { now -> now.allWorkspaces.none { it.urgent } }
        }
    }

    @Test
    fun closingAnUrgentWindowClearsItsWorkspace() = runBlocking {
        fake.answers[CLIENTS] = """[{"address": "0xbeef", "workspace": {"id": 1, "name": "1"}}]"""
        watching(hyprland.monitors) {
            hyprland.monitors.awaitOk()
            fake.emit("urgent>>beef")
            hyprland.monitors.awaitOk { now -> now.allWorkspaces.single { it.id.value == 1 }.urgent }

            fake.emit("closewindow>>beef")

            hyprland.monitors.awaitOk { now -> now.allWorkspaces.none { it.urgent } }
        }
    }

    @Test
    fun theSubmapFollowsItsEvent() = runBlocking {
        watching(hyprland.submap) {
            assertEquals(null, hyprland.submap.awaitOk())

            fake.answers[SUBMAP] = "\"resize\"\n"
            fake.emit("submap>>resize")

            hyprland.submap.awaitOk { it == "resize" }
        }
    }

    @Test
    fun theKeyboardLayoutFollowsItsEvent() = runBlocking {
        watching(hyprland.keyboardLayout) {
            assertEquals("us", hyprland.keyboardLayout.awaitOk()?.code)

            fake.answers[DEVICES] = """{"keyboards": [{"name": "cx-2.4g-wireless-receiver", "layout": "us,at",
                "active_layout_index": 1, "active_keymap": "German (Austria)", "main": true}]}"""
            fake.emit("activelayout>>cx-2.4g-wireless-receiver,German (Austria)")

            hyprland.keyboardLayout.awaitOk { it?.code == "at" }
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
        val seen = CopyOnWriteArrayList<Result<List<Monitor>, HyprlandError>>()
        val watcher = launch(Dispatchers.Default) { hyprland.monitors.collect { seen += it } }
        hyprland.monitors.awaitOk()
        awaitTrue { fake.listening == 1 }

        fake.dropListeners()
        awaitTrue { Err(HyprlandError.Disconnected) in seen }
        awaitTrue { fake.listening == 1 }
        hyprland.monitors.awaitOk()

        watcher.cancel()
        assertIs<Ok<List<Monitor>>>(seen.last(), "saw $seen")
    }

    @Test
    fun noSocketIsSaidWithThePathItLookedAt() = runBlocking {
        val empty = HyprlandInstance(Files.createTempDirectory("kortex-no-hyprland").toString())
        val absent = Hyprland(scope, empty, RETRY)

        assertEquals(HyprlandError.NoSocket(empty.events), absent.monitors.firstError())
    }

    @Test
    fun focusingAWorkspaceSendsTheLuaHyprlandAccepts() = runBlocking {
        fake.answers[FOCUS_3] = "ok"

        assertEquals(Ok(Unit), hyprland.focusWorkspace(3))
        assertEquals(listOf(FOCUS_3), fake.requests)
    }

    @Test
    fun aNumberBelowOneIsRefusedBeforeHyprlandCouldReadItAsAMove() = runBlocking {
        assertEquals(Err(HyprlandError.NotNumbered(0)), hyprland.focusWorkspace(0))
        assertEquals(Err(HyprlandError.NotNumbered(-98)), hyprland.focusWorkspace(-98))
        assertEquals(emptyList(), fake.requests)
    }

    @Test
    fun aListedWorkspaceIsAskedForTheWayHyprlandNamesIt() = runBlocking {
        val sent = listOf(
            Workspace(WorkspaceId(3), "3", windows = 1, urgent = false),
            Workspace(WorkspaceId(-98), "special:magic", windows = 1, urgent = false),
            Workspace(WorkspaceId(-1337), "we\"b\\", windows = 1, urgent = false),
        ).map { workspace ->
            fake.requests.clear()
            fake.answers.clear()
            hyprland.focusWorkspace(workspace)
            fake.requests.single()
        }

        assertEquals(
            listOf(
                FOCUS_3,
                "dispatch hl.dsp.focus({ workspace = \"special:magic\" })",
                "dispatch hl.dsp.focus({ workspace = \"name:we\\\"b\\\\\" })",
            ),
            sent,
        )
    }

    @Test
    fun aDispatchHyprlandCannotRunIsRefusedWithItsOwnWords() = runBlocking {
        // What Hyprland 0.56.2 answered the pre-Lua `dispatch workspace 2`.
        val lua = "workspace 2"
        val error = "error: [string \"return hl.dispatch(workspace 2)\"]:1: ')' expected near '2'\n\n" +
            " → Note: dispatch in lua is a shorthand for hl.dispatch(...), your syntax might need to be updated."
        fake.answers["dispatch $lua"] = error

        assertEquals(Err(HyprlandError.Refused("dispatch $lua", error)), hyprland.dispatch(lua))
    }

    @Test
    fun aCommandWithNoSocketSaysWhichPathItTried() = runBlocking {
        val empty = HyprlandInstance(Files.createTempDirectory("kortex-no-hyprland").toString())

        assertEquals(
            Err(HyprlandError.NoSocket(empty.requests)),
            Hyprland(scope, empty, RETRY).focusWorkspace(1),
        )
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

    private fun workspacesOn(three: String): String = """[
        {"id": 1, "name": "1", "monitor": "DP-1", "windows": 1},
        {"id": 3, "name": "3", "monitor": "$three", "windows": 1}
    ]"""

    private companion object {
        val TWO_MONITORS = """[
            {"id": 0, "name": "DP-1", "description": "left", "focused": true,
             "activeWorkspace": {"id": 1, "name": "1"}, "specialWorkspace": {"id": 0, "name": ""}},
            {"id": 1, "name": "DP-2", "description": "right", "focused": false,
             "activeWorkspace": {"id": 3, "name": "3"}, "specialWorkspace": {"id": 0, "name": ""}}
        ]"""

        val RETRY = 50.milliseconds
        const val FOCUS_3 = "dispatch hl.dsp.focus({ workspace = \"3\" })"
        val TIMEOUT = 5.seconds
        val SETTLE = 200.milliseconds
    }
}
