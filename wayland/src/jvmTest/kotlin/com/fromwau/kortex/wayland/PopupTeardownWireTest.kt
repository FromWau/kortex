package com.fromwau.kortex.wayland

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Reads the order an application ends a window and the popup inside it in, which only the wire shows.
 *
 * xdg-shell's own words for `xdg_popup.destroy` are that a popup which is not the topmost one raises
 * `xdg_wm_base.not_the_topmost_popup`, and mutter goes further: it watches a popup's *parent* surface for
 * unmap and drops the client when one still has a live child (`on_parent_surface_unmapped`). kortex walks
 * its placed surfaces parent before child, so without [KortexShell.endPopupsUnder] the parent's
 * `xdg_toplevel.destroy` reached the wire first.
 *
 * Nothing catches that in process. Every popup test here leaves its popup up and lets the block return, so
 * the suite performed this exact sequence on every run and reported green; adding the call that fixes it
 * changed no test's colour either way.
 *
 * Takes the user's focus and tiles into the workspace they are looking at, so this runs only in a session
 * kept free for it.
 */
class PopupTeardownWireTest {
    @Test
    fun `an application ending with a popup up destroys the popup before the window holding it`() {
        val (exitCode, output) = runProbe(
            "com.fromwau.kortex.wayland.PopupTeardownProbeKt",
            environment = mapOf("WAYLAND_DEBUG" to "client"),
        )
        val raw = output.joinToString("\n")
        assertEquals(0, exitCode, "the popup probe exited $exitCode; output:\n$raw")

        val upAt = output.indexOfFirst { it == PROBE_MARKER_POPUP_UP }
        val downAt = output.indexOfFirst { it == PROBE_MARKER_TORN_DOWN }
        assertTrue(
            upAt >= 0 && downAt > upAt,
            "the probe's markers are missing or out of order (up=$upAt down=$downAt); output:\n$raw",
        )
        val teardown = output.subList(upAt + 1, downAt)
        val xdgTraffic = teardown.traceOf("xdg_popup", "xdg_toplevel", "xdg_surface")

        val popupAt = teardown.indexOfFirst { it.isRequest("xdg_popup", "destroy") }
        val toplevelAt = teardown.indexOfFirst { it.isRequest("xdg_toplevel", "destroy") }
        assertTrue(
            popupAt >= 0,
            "the popup was never destroyed while the application ended; teardown:\n$xdgTraffic",
        )
        assertTrue(
            toplevelAt >= 0,
            "the window was never destroyed while the application ended; teardown:\n$xdgTraffic",
        )
        assertTrue(
            popupAt < toplevelAt,
            "the window was destroyed before the popup inside it, which unmaps a popup's parent under it; " +
                "teardown:\n$xdgTraffic",
        )
    }
}
