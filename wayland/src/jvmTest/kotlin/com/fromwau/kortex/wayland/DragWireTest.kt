package com.fromwau.kortex.wayland

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Reads a whole drag off the wire, which is the only place it can be read: every other drag test drives
 * [com.fromwau.kortex.compose.KortexScene] or the clipboard directly, so `wl_data_device`'s own side, the
 * one a compositor talks to, has no cover at all.
 *
 * The rule this pins is `wl_data_offer`'s lifetime. A destination refuses a drag by accepting no type, and
 * the offer stays the compositor's until it sends `leave`; giving it back any earlier tells the drag's
 * *source* the session is over, which cancels the drag, and cancels another application's drag as readily as
 * this one's. Weston's own toytoolkit destroys a drag offer in `data_device_leave` and nowhere else
 * (`clients/window.c`). kortex used to destroy it inside `enter`, which is why a drag never reached a
 * destination at all.
 *
 * Drives the user's pointer across their screen, so like the window tests this runs only in a session kept
 * free for it.
 */
class DragWireTest {
    @Test
    fun `a drag keeps the compositor's offer until it leaves, and carries its text across`() {
        val (exitCode, output) = runProbe(
            "com.fromwau.kortex.wayland.DragWireProbeKt",
            environment = mapOf("WAYLAND_DEBUG" to "client"),
        )
        val raw = output.joinToString("\n")
        assertEquals(0, exitCode, "the drag probe exited $exitCode; output:\n$raw")

        val placedAt = output.indexOfFirst { it == PROBE_MARKER_DRAG_PLACED }
        val drivenAt = output.indexOfFirst { it == PROBE_MARKER_DRAG_DRIVEN }
        assertTrue(
            placedAt >= 0 && drivenAt > placedAt,
            "the probe's markers are missing or out of order (placed=$placedAt driven=$drivenAt); output:\n$raw",
        )
        val wire = output.subList(placedAt + 1, drivenAt)

        // The request itself, which answers the first question: did the gesture reach the compositor at all.
        assertTrue(
            wire.any { it.isRequest("wl_data_device", "start_drag") },
            "no wl_data_device.start_drag left the client while the pointer dragged; wire:\n${wire.pretty()}",
        )

        // A drag's first enter always names the surface it started from, whose own tree holds no drop target,
        // so this is the refusal the defect lived in rather than an exotic case.
        val enterAt = wire.indexOfFirst { it.isEvent("wl_data_device", "enter") }
        assertTrue(
            enterAt >= 0,
            "the compositor never entered any surface with the drag; wire:\n${wire.pretty()}",
        )
        val leaveAt = wire.drop(enterAt).indexOfFirst { it.isEvent("wl_data_device", "leave") }
        assertTrue(
            leaveAt >= 0,
            "the drag entered a surface and never left it; wire:\n${wire.pretty()}",
        )

        val whileEntered = wire.subList(enterAt, enterAt + leaveAt)
        assertEquals(
            emptyList(), whileEntered.filter { it.isRequest("wl_data_offer", "destroy") },
            "an offer the compositor still held as the drag's own was given back before it left; " +
                "wire:\n${whileEntered.pretty()}",
        )

        // The other half of the same rule: held until the leave, and given back once it comes, since an offer
        // nobody destroys is a leak on both sides of the socket.
        assertTrue(
            wire.drop(enterAt + leaveAt).any { it.isRequest("wl_data_offer", "destroy") },
            "the drag left the surface and its offer was never given back; wire:\n${wire.pretty()}",
        )

        // And the end of it: the text the source offered reached the target's content.
        assertTrue(
            output.any { it == PROBE_MARKER_DROPPED + PROBE_DRAGGED_TEXT },
            "the dragged text never reached the target's content; output:\n$raw",
        )
    }
}

// WAYLAND_DEBUG marks a request with an arrow and leaves an event bare, so the two are told apart by that
// alone. The id follows a #, as libwayland's own wl_closure_print writes it, and matching on it keeps
// wl_data_device from also matching wl_data_device_manager.
private fun String.isRequest(interfaceName: String, name: String): Boolean =
    contains("-> $interfaceName#") && contains(".$name(")

private fun String.isEvent(interfaceName: String, name: String): Boolean =
    !contains("-> ") && contains("$interfaceName#") && contains(".$name(")

/** Only the data-device traffic, since a failure message full of frame callbacks helps nobody. */
private fun List<String>.pretty(): String =
    filter { it.contains("wl_data_") }.joinToString("\n").ifEmpty { "(no wl_data_* traffic at all)" }
