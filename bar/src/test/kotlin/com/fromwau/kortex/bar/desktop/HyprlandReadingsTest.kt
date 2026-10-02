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
import com.fromwau.kortex.hyprland.KeyboardLayout
import com.fromwau.kortex.hyprland.Monitor
import com.fromwau.kortex.hyprland.WindowAddress
import com.fromwau.kortex.hyprland.Workspace
import com.fromwau.kortex.hyprland.WorkspaceId
import kotlin.test.Test
import kotlin.test.assertEquals

class HyprlandReadingsTest {
    @Test
    fun `the gaps between workspaces in use are shown, and one empty workspace past the last`() {
        val monitors = listOf(monitor(DP1, workspace(1, windows = 2), workspace(4, windows = 1), active = 1))

        assertEquals(
            listOf(
                WorkspaceSlot(1, windows = 2, shown = SlotShown.Focused, urgent = false),
                WorkspaceSlot(2, windows = 0, shown = SlotShown.Hidden, urgent = false),
                WorkspaceSlot(3, windows = 0, shown = SlotShown.Hidden, urgent = false),
                WorkspaceSlot(4, windows = 1, shown = SlotShown.Hidden, urgent = false),
                WorkspaceSlot(5, windows = 0, shown = SlotShown.Hidden, urgent = false),
            ),
            monitors.strip(DP1).slots,
        )
    }

    @Test
    fun `an empty workspace the monitor is showing counts as in use`() {
        val monitors = listOf(monitor(DP1, workspace(1, windows = 1), workspace(6, windows = 0), active = 6))

        val strip = monitors.strip(DP1)

        assertEquals((1..7).toList(), strip.slots.map { it.id })
        assertEquals(SlotShown.Focused, strip.slots.single { it.id == 6 }.shown)
    }

    @Test
    fun `each monitor's strip holds its own workspaces and skips the numbers another one holds`() {
        val monitors = listOf(
            monitor(DP1, workspace(1, windows = 1), workspace(4, windows = 1), active = 1),
            monitor(HDMI, workspace(2, windows = 1), workspace(3, windows = 1), active = 3, focused = false),
        )

        assertEquals(listOf(1, 4, 5), monitors.strip(DP1).slots.map { it.id })
        assertEquals(listOf(2, 3, 5), monitors.strip(HDMI).slots.map { it.id })
        assertEquals(
            listOf(SlotShown.Hidden, SlotShown.Visible, SlotShown.Hidden),
            monitors.strip(HDMI).slots.map { it.shown },
            "shown on a monitor without focus",
        )
    }

    @Test
    fun `a workspace moved to another monitor leaves one strip for the other`() {
        val before = listOf(
            monitor(DP1, workspace(1, windows = 1), workspace(2, windows = 1), active = 1),
            monitor(HDMI, workspace(3, windows = 1), active = 3, focused = false),
        )
        val after = listOf(
            monitor(DP1, workspace(1, windows = 1), active = 1),
            monitor(HDMI, workspace(2, windows = 1), workspace(3, windows = 1), active = 3, focused = false),
        )

        assertEquals(listOf(1, 2, 4), before.strip(DP1).slots.map { it.id })
        assertEquals(listOf(1, 4), after.strip(DP1).slots.map { it.id })
        assertEquals(listOf(2, 3, 4), after.strip(HDMI).slots.map { it.id })
    }

    @Test
    fun `special and named workspaces stay out of the numbers, and an open special one is named`() {
        val monitors = listOf(
            monitor(
                DP1,
                workspace(-99, windows = 3, name = "special:special"),
                workspace(-1337, windows = 1, name = "web"),
                workspace(2, windows = 1),
                active = 2,
                special = -99,
            ),
        )

        val strip = monitors.strip(DP1)

        assertEquals(listOf(1, 2, 3), strip.slots.map { it.id })
        assertEquals("special", strip.special)
    }

    @Test
    fun `with no numbered workspace in use the strip still offers workspace 1`() {
        val monitors = listOf(monitor(DP1, workspace(-1337, windows = 0, name = "web"), active = -1337))

        assertEquals(
            listOf(WorkspaceSlot(1, windows = 0, shown = SlotShown.Hidden, urgent = false)),
            monitors.strip(DP1).slots,
        )
    }

    @Test
    fun `the next workspace may be one of the monitor's own empty persistent ones`() {
        val monitors = listOf(
            monitor(
                DP1,
                workspace(1, windows = 1),
                workspace(2, windows = 1),
                workspace(3, windows = 0),
                workspace(4, windows = 0),
                active = 1,
            ),
            monitor(HDMI, workspace(6, windows = 0), active = 6, focused = false),
        )

        assertEquals(listOf(1, 2, 3), monitors.strip(DP1).slots.map { it.id })
        assertEquals(listOf(5, 6, 7), monitors.strip(HDMI).slots.map { it.id })
    }

    @Test
    fun `an urgent workspace is marked on its pill`() {
        val monitors = listOf(
            monitor(DP1, workspace(1, windows = 1), workspace(2, windows = 1, urgent = true), active = 1),
        )

        assertEquals(listOf(false, true, false), monitors.strip(DP1).slots.map { it.urgent })
    }

    @Test
    fun `not connected yet is pending, and any other failure says what hyprland reported`() {
        assertEquals(Reading.Pending, Err(HyprlandError.NotConnected).asStrip(DP1))
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

    @Test
    fun `the layout is its code in capitals where hyprland gives one, and its full name as written where not`() {
        assertEquals(Reading.Value("AT"), Ok(KeyboardLayout("k", "German (Austria)", "at")).asLayout())
        assertEquals(Reading.Value("German (Austria)"), Ok(KeyboardLayout("k", "German (Austria)", null)).asLayout())
    }

    private fun workspace(
        id: Int,
        windows: Int,
        name: String = "$id",
        urgent: Boolean = false,
    ): Workspace = Workspace(WorkspaceId(id), name, windows, urgent)

    private fun monitor(
        name: String,
        vararg workspaces: Workspace,
        active: Int,
        focused: Boolean = true,
        special: Int? = null,
    ): Monitor = Monitor(
        id = 0,
        name = name,
        description = name,
        focused = focused,
        workspaces = workspaces.sortedBy { it.id.value },
        active = WorkspaceId(active),
        special = special?.let(::WorkspaceId),
    )

    private companion object {
        const val DP1 = "DP-1"
        const val HDMI = "HDMI-A-2"
    }
}
