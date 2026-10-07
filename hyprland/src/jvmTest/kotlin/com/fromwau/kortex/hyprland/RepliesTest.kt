package com.fromwau.kortex.hyprland

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Hyprland's answers, as recorded from 0.56.2 under `src/jvmTest/resources/recorded`. */
internal object Recorded {
    val workspaces = text("workspaces.json")
    val monitors = text("monitors.json")
    val activeWindow = text("activewindow.json")
    val submap = text("submap.json")
    val devices = text("devices.json")

    private fun text(name: String): String =
        checkNotNull(Recorded::class.java.getResource("/recorded/$name")) { "no recording $name" }.readText()
}

class RepliesTest {
    @Test
    fun recordedWorkspacesAndMonitorsDecodeAsOneMonitorHoldingItsWorkspaces() {
        val workspaces = decode(WORKSPACES, Recorded.workspaces, ListSerializer(WorkspaceReply.serializer()))
        val monitors = decode(MONITORS, Recorded.monitors, ListSerializer(MonitorReply.serializer()))

        val decoded = monitorsFrom(workspaces.assertSuccess(), monitors.assertSuccess(), urgent = emptySet())

        assertEquals(
            listOf(
                Monitor(
                    id = 1,
                    name = "HDMI-A-2",
                    description = "LG Electronics LG TV SSCR2 0x01010101",
                    focused = true,
                    workspaces = listOf(
                        Workspace(WorkspaceId(-99), "special:special", windows = 1, urgent = false),
                        Workspace(WorkspaceId(1), "1", windows = 2, urgent = false),
                        Workspace(WorkspaceId(3), "3", windows = 1, urgent = false),
                    ),
                    active = WorkspaceId(1),
                    special = null,
                ),
            ),
            decoded,
        )
    }

    @Test
    fun workspacesGoToTheMonitorTheyAreOnAndTheOpenSpecialIsNamed() {
        val workspaces = listOf(
            WorkspaceReply(1, "1", "DP-1", 1),
            WorkspaceReply(2, "2", "DP-2", 1),
            WorkspaceReply(-98, "special:magic", "DP-2", 1),
        )
        val monitors = listOf(
            MonitorReply(2, "DP-2", "right", false, WorkspaceRef(2), WorkspaceRef(-98)),
            MonitorReply(1, "DP-1", "left", true, WorkspaceRef(1), WorkspaceRef(0)),
        )

        val decoded = monitorsFrom(workspaces, monitors, urgent = setOf(WorkspaceId(2)))

        assertEquals(listOf("DP-1", "DP-2"), decoded.map { it.name }, "ordered by id")
        assertEquals(listOf(1), decoded[0].workspaces.map { it.id.value })
        assertEquals(listOf(-98, 2), decoded[1].workspaces.map { it.id.value })
        assertEquals(null, decoded[0].special)
        assertEquals(WorkspaceId(-98), decoded[1].special)
        assertEquals(listOf(false, true), decoded[1].workspaces.map { it.urgent })
    }

    @Test
    fun aWorkspaceIsUrgentWhereAnUrgentWindowIsOnIt() {
        val clients = listOf(
            ClientReply("0xa", WorkspaceRef(1)),
            ClientReply("0xb", WorkspaceRef(3)),
        )

        assertEquals(setOf(WorkspaceId(3)), workspacesHolding(setOf(WindowAddress("0xb")), clients))
        assertEquals(emptySet(), workspacesHolding(setOf(WindowAddress("0xgone")), clients))
    }

    @Test
    fun theDefaultSubmapIsNoSubmap() {
        assertEquals(Ok(null), submapFrom(Recorded.submap))
        assertEquals(Ok("resize"), submapFrom("\"resize\"\n"))
    }

    @Test
    fun theMainKeyboardsLayoutIsTheOneAtItsActiveIndex() {
        val layout = KeyboardLayout(keyboard = "cx-2.4g-wireless-receiver", keymap = "English (US)", code = "us")

        assertEquals(Ok(layout), keyboardLayoutFrom(Recorded.devices))
    }

    @Test
    fun aKeyboardWithNoLayoutIndexHasNoCode() {
        // Hyprland writes a bare `none` here rather than a number or a string.
        val devices = """{"keyboards": [{"name": "k", "layout": "us,at", "active_layout_index": none,
            "active_keymap": "English (US)", "main": true}]}"""

        assertEquals(Ok(KeyboardLayout("k", "English (US)", code = null)), keyboardLayoutFrom(devices))
        assertEquals(Ok(null), keyboardLayoutFrom("""{"keyboards": []}"""))
    }

    @Test
    fun recordedActiveWindowDecodes() {
        val window = checkNotNull(activeWindowFrom(Recorded.activeWindow).assertSuccess())

        assertEquals(WindowAddress("0x55d7c479f890"), window.address)
        assertEquals("com.mitchellh.ghostty", window.appId)
        assertTrue(window.title.endsWith("kortex-reactive (3)"), window.title)
        assertEquals(WorkspaceId(1), window.workspace)
    }

    @Test
    fun anEmptyObjectIsNothingFocused() {
        assertEquals(Ok(null), activeWindowFrom("{}"))
    }

    @Test
    fun aTextAnswerIsARefusalNotBadJson() {
        assertEquals(Err(HyprlandError.Refused(ACTIVE_WINDOW, "unknown request")), activeWindowFrom("unknown request"))
    }

    @Test
    fun jsonOfTheWrongShapeIsUnparseable() {
        val decoded = decode(WORKSPACES, """[{"id":"one"}]""", ListSerializer(WorkspaceReply.serializer()))

        assertEquals(WORKSPACES, decoded.assertError<HyprlandError.Unparseable>().request)
    }

    @Test
    fun onlyHyprlandsSpecialRangeIsSpecial() {
        fun workspace(id: Int) = Workspace(WorkspaceId(id), "$id", windows = 0, urgent = false)

        assertTrue(workspace(-99).special, "the bare special workspace")
        assertTrue(workspace(-98).special, "a named special workspace")
        assertTrue(workspace(-2).special)
        assertFalse(workspace(-1337).special, "a name: workspace is negative and not special")
        assertFalse(workspace(-1).special)
        assertFalse(workspace(1).special)
    }
}
