package com.fromwau.kortex.hyprland

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.getOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Hyprland's answers, as recorded from 0.56.2 under `src/jvmTest/resources/recorded`. */
internal object Recorded {
    val workspaces = text("workspaces.json")
    val monitors = text("monitors.json")
    val activeWindow = text("activewindow.json")

    private fun text(name: String): String =
        checkNotNull(Recorded::class.java.getResource("/recorded/$name")) { "no recording $name" }.readText()
}

class RepliesTest {
    @Test
    fun recordedWorkspacesAndMonitorsDecodeInIdOrder() {
        val workspaces = decode(WORKSPACES, Recorded.workspaces, ListSerializer(WorkspaceReply.serializer()))
        val monitors = decode(MONITORS, Recorded.monitors, ListSerializer(MonitorReply.serializer()))

        val decoded = workspacesFrom(assertIs<Ok<List<WorkspaceReply>>>(workspaces).value, monitors.getOrNull()!!)

        assertEquals(
            listOf(
                Workspace(WorkspaceId(-99), "special:special", "HDMI-A-2", 1),
                Workspace(WorkspaceId(1), "1", "HDMI-A-2", 2),
                Workspace(WorkspaceId(3), "3", "HDMI-A-2", 1),
            ),
            decoded.all,
        )
        assertEquals(mapOf("HDMI-A-2" to WorkspaceId(1)), decoded.active)
        assertEquals("HDMI-A-2", decoded.focusedMonitor)
    }

    @Test
    fun recordedActiveWindowDecodes() {
        val window = assertIs<Ok<ActiveWindow?>>(activeWindowFrom(Recorded.activeWindow)).value!!

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

        assertEquals(WORKSPACES, assertIs<HyprlandError.Unparseable>((decoded as Err).error).request)
    }

    @Test
    fun onlyHyprlandsSpecialRangeIsSpecial() {
        fun workspace(id: Int) = Workspace(WorkspaceId(id), "$id", "DP-1", 0)

        assertTrue(workspace(-99).special, "the bare special workspace")
        assertTrue(workspace(-98).special, "a named special workspace")
        assertTrue(workspace(-2).special)
        assertFalse(workspace(-1337).special, "a name: workspace is negative and not special")
        assertFalse(workspace(-1).special)
        assertFalse(workspace(1).special)
    }
}
