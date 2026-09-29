package com.fromwau.kortex.wayland

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * That [WindowState.askMinimized] reaches the compositor, which only the wire shows.
 *
 * Every other window ask is read back through the state the compositor answers with, or through `hyprctl`.
 * A minimize has neither: `xdg_toplevel` carries no minimized state and no event back, and Hyprland keeps no
 * minimized windows for `hyprctl` to list. So an `askMinimized` that reached nothing at all would look exactly
 * like one that worked, which is what this reads the request itself for.
 *
 * Takes the user's focus and tiles into the workspace they are looking at, so this runs only in a session kept
 * free for it.
 */
class MinimizeWireTest {
    @Test
    fun `a window asked to minimize sends set_minimized and nothing else of its own`() {
        val (exitCode, output) = runProbe(
            "com.fromwau.kortex.wayland.MinimizeProbeKt",
            environment = mapOf("WAYLAND_DEBUG" to "client"),
        )
        val raw = output.joinToString("\n")
        assertEquals(0, exitCode, "the minimize probe exited $exitCode; output:\n$raw")

        val upAt = output.indexOfFirst { it == PROBE_MARKER_WINDOW_UP }
        val askedAt = output.indexOfFirst { it == PROBE_MARKER_ASKED_MINIMIZED }
        assertTrue(
            upAt >= 0 && askedAt > upAt,
            "the probe's markers are missing or out of order (up=$upAt asked=$askedAt); output:\n$raw",
        )
        val whileAsking = output.subList(upAt + 1, askedAt)
        val xdgTraffic = whileAsking.traceOf("xdg_toplevel", "xdg_surface")

        assertTrue(
            whileAsking.any { it.isRequest("xdg_toplevel", "set_minimized") },
            "the window asked to be minimized and no set_minimized left the client; traffic:\n$xdgTraffic",
        )
        // The ask carries nothing and asks for nothing else: a set_maximized or a set_fullscreen here would be
        // the wrong request reaching the wire under the right name.
        assertEquals(
            emptyList(),
            whileAsking.filter {
                it.isRequest("xdg_toplevel", "set_maximized") ||
                    it.isRequest("xdg_toplevel", "unset_maximized") ||
                    it.isRequest("xdg_toplevel", "set_fullscreen") ||
                    it.isRequest("xdg_toplevel", "unset_fullscreen")
            },
            "asking for a minimize sent some other window request too; traffic:\n$xdgTraffic",
        )
    }
}
