package com.fromwau.kortex.bar.desktop

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kortex.bar.BarError
import com.fromwau.kortex.bar.state.FocusedWindow
import com.fromwau.kortex.bar.state.Reading
import com.fromwau.kortex.bar.state.SlotShown
import com.fromwau.kortex.bar.state.WorkspaceSlot
import com.fromwau.kortex.hyprland.ActiveWindow
import com.fromwau.kortex.hyprland.HyprlandError
import com.fromwau.kortex.hyprland.WindowAddress
import com.fromwau.kortex.hyprland.Workspace
import com.fromwau.kortex.hyprland.WorkspaceId
import com.fromwau.kortex.hyprland.Workspaces
import kotlin.test.Test
import kotlin.test.assertEquals

class HyprlandReadingsTest {
    @Test
    fun `the gaps between workspaces in use are shown, and one empty workspace past the last`() {
        val workspaces = workspaces(workspace(1, windows = 2), workspace(4, windows = 1), active = mapOf(DP1 to 1))

        assertEquals(
            listOf(
                WorkspaceSlot(1, windows = 2, shown = SlotShown.Focused),
                WorkspaceSlot(2, windows = 0, shown = SlotShown.Hidden),
                WorkspaceSlot(3, windows = 0, shown = SlotShown.Hidden),
                WorkspaceSlot(4, windows = 1, shown = SlotShown.Hidden),
                WorkspaceSlot(5, windows = 0, shown = SlotShown.Hidden),
            ),
            workspaces.strip(),
        )
    }

    @Test
    fun `an empty workspace a monitor is showing counts as in use`() {
        val workspaces = workspaces(workspace(1, windows = 1), workspace(6, windows = 0), active = mapOf(DP1 to 6))

        val strip = workspaces.strip()

        assertEquals((1..7).toList(), strip.map { it.id })
        assertEquals(SlotShown.Focused, strip.single { it.id == 6 }.shown)
    }

    @Test
    fun `special and named workspaces stay out of the strip`() {
        val workspaces = workspaces(
            workspace(-99, windows = 3, name = "special:special"),
            workspace(-1337, windows = 1, name = "web"),
            workspace(2, windows = 1),
            active = mapOf(DP1 to 2),
        )

        assertEquals(listOf(1, 2, 3), workspaces.strip().map { it.id })
    }

    @Test
    fun `with no numbered workspace in use the strip still offers workspace 1`() {
        val workspaces = workspaces(
            workspace(-99, windows = 3, name = "special:special"),
            workspace(-1337, windows = 0, name = "web"),
            active = mapOf(DP1 to -1337),
        )

        assertEquals(listOf(WorkspaceSlot(1, windows = 0, shown = SlotShown.Hidden)), workspaces.strip())
    }

    @Test
    fun `a workspace on a monitor without focus is visible rather than focused`() {
        val workspaces = workspaces(
            workspace(1, windows = 1),
            workspace(2, windows = 1),
            active = mapOf(DP1 to 1, HDMI to 2),
        )

        assertEquals(
            listOf(SlotShown.Focused, SlotShown.Visible, SlotShown.Hidden),
            workspaces.strip().map { it.shown },
        )
    }

    @Test
    fun `not connected yet is pending, and any other failure says what hyprland reported`() {
        assertEquals(Reading.Pending, Err(HyprlandError.NotConnected).asStrip())
        assertEquals(
            Reading.Unavailable(BarError.NoHyprland(HyprlandError.NoInstance)),
            Err(HyprlandError.NoInstance).asFocused(),
        )
    }

    @Test
    fun `nothing focused is a value of null, not a missing reading`() {
        assertEquals(Reading.Value(null), Ok(null).asFocused())
        assertEquals(
            Reading.Value(FocusedWindow(appId = "kitty", title = "~")),
            Ok(ActiveWindow(WindowAddress("0x1"), "kitty", "~", WorkspaceId(1))).asFocused(),
        )
    }

    private fun workspace(
        id: Int,
        windows: Int,
        name: String = "$id",
    ): Workspace = Workspace(WorkspaceId(id), name, DP1, windows)

    private fun workspaces(
        vararg all: Workspace,
        active: Map<String, Int>,
    ): Workspaces = Workspaces(
        all = all.toList(),
        active = active.mapValues { WorkspaceId(it.value) },
        focusedMonitor = DP1,
    )

    private companion object {
        const val DP1 = "DP-1"
        const val HDMI = "HDMI-A-2"
    }
}
