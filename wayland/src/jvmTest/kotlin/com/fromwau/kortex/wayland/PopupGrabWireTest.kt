package com.fromwau.kortex.wayland

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What separates a [ContextMenu] from a [Popup]: the menu takes `xdg_popup.grab`, so the desktop dismisses it
 * when the user clicks away, and the popup takes none and stays until its own call leaves composition.
 *
 * The grab itself is only visible on the wire, and its effect only on a real compositor, so both are read out
 * of one probe: what each call asked for, and what became of it once the user clicked elsewhere.
 *
 * Drives the user's pointer, though every click of it lands on the probe's own bar, so this runs only in a
 * session kept free for it.
 */
class PopupGrabWireTest {
    @Test
    fun `a menu grabs and is dismissed by a click away from it, where a popup does neither`() {
        val (exitCode, output) = runProbe(
            "com.fromwau.kortex.wayland.PopupGrabProbeKt",
            environment = mapOf("WAYLAND_DEBUG" to "client"),
        )
        val raw = output.joinToString("\n")
        assertEquals(0, exitCode, "the popup grab probe exited $exitCode; output:\n$raw")

        val barAt = output.indexOfFirst { it == PROBE_MARKER_GRAB_BAR_UP }
        val menuAt = output.indexOfFirst { it == PROBE_MARKER_GRAB_MENU_UP }
        val popupAt = output.indexOfFirst { it == PROBE_MARKER_GRAB_POPUP_UP }
        assertTrue(
            barAt >= 0 && menuAt > barAt && popupAt > menuAt,
            "the probe's markers are missing or out of order " +
                "(bar=$barAt menu=$menuAt popup=$popupAt); output:\n$raw",
        )

        // The menu's own opening, from the click that preceded it to the frame it reached the screen on.
        val opening = output.subList(barAt + 1, menuAt)
        assertTrue(
            opening.any { it.isRequest("xdg_popup", "grab") },
            "a ContextMenu opened without asking the compositor for a grab; traffic:\n" +
                opening.traceOf("xdg_popup", "xdg_surface"),
        )

        // And what the grab is for. A menu the user clicks away from is the compositor's to take down, which
        // it reports as popup_done and kortex ends as ClosedByCompositor.
        // Matched on the case's own name rather than on a whole toString, which no other status carries and
        // which a change to Result's own rendering cannot break.
        val ended = output.firstOrNull { it.startsWith(PROBE_MARKER_GRAB_MENU_ENDED) }
        assertTrue(
            ended != null && "ClosedByCompositor" in ended,
            "a click away from the menu did not end it as the compositor dismissing it: $ended",
        )

        // The other half: the same gesture against a popup that asked for no grab leaves it standing, since
        // only its own call leaving composition takes it down.
        val popupOpening = output.subList(menuAt + 1, popupAt)
        assertEquals(
            emptyList(), popupOpening.filter { it.isRequest("xdg_popup", "grab") },
            "a Popup asked the compositor for a grab, which would take the keyboard from whatever holds it; " +
                "traffic:\n" + popupOpening.traceOf("xdg_popup", "xdg_surface"),
        )
        val after = output.firstOrNull { it.startsWith(PROBE_MARKER_GRAB_POPUP_AFTER) }
        assertTrue(
            after != null && "OnScreen" in after,
            "a click away from a popup that holds no grab took it down anyway: $after",
        )
    }
}
