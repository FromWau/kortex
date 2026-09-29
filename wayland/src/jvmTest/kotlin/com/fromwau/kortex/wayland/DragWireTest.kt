package com.fromwau.kortex.wayland

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
        val dataTraffic = wire.traceOf("wl_data_device", "wl_data_offer", "wl_data_source")

        // The gesture before the drag, because start_drag has nothing to quote without a press: startDrag
        // answers a missing grab serial with NoInputSerial and sends nothing, which on the wire is
        // indistinguishable from a drag that was asked for and refused. Asserted the other way round, a
        // pointer that never reached the source surface reads as a drag defect.
        val pressSerials = wire
            .filter { it.isEvent("wl_pointer", "button") }
            .mapNotNull { BUTTON_PRESS_SERIAL.find(it)?.groupValues?.get(1)?.toIntOrNull() }
        assertTrue(
            pressSerials.isNotEmpty(),
            "no wl_pointer.button press reached the client at all, so no drag could start; wire:\n" +
                wire.traceOf("wl_pointer"),
        )

        // The request itself, which answers the first question: did the gesture reach the compositor at all.
        // The two markers say which side a missing one is on, since the wire looks the same either way:
        // Compose never asking the host, or the host being asked and failing to start the drag.
        val asked = output.any { it == PROBE_MARKER_ASKED }
        val notStarted = output.any { it == PROBE_MARKER_NOT_STARTED }
        // Motion after the press is what Compose reads a drag out of, and an enter is what says the pointer
        // was over the surface to begin with, so both counts belong beside the markers: a gesture the
        // compositor never delivered and one Compose declined to call a drag are otherwise the same silence.
        val enters = wire.count { it.isEvent("wl_pointer", "enter") }
        val motions = wire.count { it.isEvent("wl_pointer", "motion") }
        assertTrue(
            wire.any { it.isRequest("wl_data_device", "start_drag") },
            "no wl_data_device.start_drag left the client while the pointer dragged " +
                "(compose asked for the transfer: $asked; the host reported it did not start: $notStarted; " +
                "pointer enters: $enters, motions: $motions, presses: ${pressSerials.size}); " +
                "wire:\n$dataTraffic",
        )

        // A drag's first enter always names the surface it started from, whose own tree holds no drop target,
        // so this is the refusal the defect lived in rather than an exotic case.
        val enterAt = wire.indexOfFirst { it.isEvent("wl_data_device", "enter") }
        assertTrue(
            enterAt >= 0,
            "the compositor never entered any surface with the drag; wire:\n$dataTraffic",
        )
        val leaveAt = wire.drop(enterAt).indexOfFirst { it.isEvent("wl_data_device", "leave") }
        assertTrue(
            leaveAt >= 0,
            "the drag entered a surface and never left it; wire:\n$dataTraffic",
        )

        val whileEntered = wire.subList(enterAt, enterAt + leaveAt)
        val whileEnteredTraffic =
            whileEntered.traceOf("wl_data_device", "wl_data_offer", "wl_data_source")
        assertEquals(
            emptyList(), whileEntered.filter { it.isRequest("wl_data_offer", "destroy") },
            "an offer the compositor still held as the drag's own was given back before it left; " +
                "wire:\n$whileEnteredTraffic",
        )

        // The other half of the same rule: held until the leave, and given back once it comes, since an offer
        // nobody destroys is a leak on both sides of the socket.
        assertTrue(
            wire.drop(enterAt + leaveAt).any { it.isRequest("wl_data_offer", "destroy") },
            "the drag left the surface and its offer was never given back; wire:\n$dataTraffic",
        )

        // The serial start_drag quotes must be a button press's own. Its argument asks for "the serial
        // number of the implicit grab on the origin", and KWin refuses a drag whose serial names no such
        // grab (src/input.cpp, hasImplicitPointerGrab). kortex used to quote whatever input happened last,
        // which a key or a button release could be.
        val startDrag = wire.first { it.isRequest("wl_data_device", "start_drag") }
        val quoted = assertNotNull(
            START_DRAG_SERIAL.find(startDrag)?.groupValues?.get(1)?.toIntOrNull(),
            "could not read start_drag's serial from: $startDrag",
        )
        assertTrue(
            quoted in pressSerials,
            "start_drag quoted $quoted, which is no button press's serial (presses: $pressSerials)",
        )

        // Every motion is answered, not only the arrival. Content takes a drag for the whole session, but only
        // the part of it under the drag would take the drop, so the answer changes as the drag crosses the
        // surface and the last one before the drop is what the compositor settles on. One answer per motion
        // plus the arrival's own, so this counts strictly more accepts than motions; kortex used to accept
        // once, at the enter, and never revise it.
        val lastEnterAt = wire.indexOfLast { it.isEvent("wl_data_device", "enter") }
        val dropAt = wire.drop(lastEnterAt).indexOfFirst { it.isEvent("wl_data_device", "drop") }
        assertTrue(
            dropAt >= 0,
            "the drag entered its last surface and was never dropped on it; wire:\n$dataTraffic",
        )
        val overTarget = wire.subList(lastEnterAt, lastEnterAt + dropAt)
        val accepts = overTarget.count { it.isRequest("wl_data_offer", "accept") }
        val moves = overTarget.count { it.isEvent("wl_data_device", "motion") }
        assertTrue(
            accepts > moves,
            "the drag answered $accepts times over $moves motions, so a move left its answer stale; " +
                "wire:\n" + overTarget.traceOf("wl_data_device", "wl_data_offer"),
        )

        // The target took what arrived, so the drag's source is told its drop succeeded. A finish is what lets a
        // source dragging a move delete what it sent, so only content taking the drop earns one.
        assertTrue(
            wire.drop(lastEnterAt + dropAt).any { it.isRequest("wl_data_offer", "finish") },
            "the target took the drop and its source was never told; wire:\n$dataTraffic",
        )

        // What each side told the compositor a drop could do. The source names both, since content listed
        // both and the application it drags to decides between them. The destination names only a copy,
        // because Hyprland settles the action before this side has spoken and never revisits it, so naming a
        // move here would make every drag in a move whatever the user held.

        assertTrue(
            wire.any { it.isRequest("wl_data_source", "set_actions") && COPY_AND_MOVE.containsMatchIn(it) },
            "the source did not offer both a copy and a move; wire:\n$dataTraffic",
        )
        assertTrue(
            wire.any { it.isRequest("wl_data_offer", "set_actions") && COPY_ALONE.containsMatchIn(it) },
            "the destination named an action other than a copy, which it cannot steer the choice between; " +
                "wire:\n$dataTraffic",
        )

        // And back to the content that dragged, which is the only place a move would be told to undo itself.
        assertTrue(
            output.any { it == PROBE_MARKER_COMPLETED + "Copy" },
            "the content that dragged was never told the drag completed as a copy; output:\n$raw",
        )

        // And the end of it: the text the source offered reached the target's content.

        assertTrue(
            output.any { it == PROBE_MARKER_DROPPED + PROBE_DRAGGED_TEXT },
            "the dragged text never reached the target's content; output:\n$raw",
        )
    }
}

// start_drag(source, origin, icon, serial): the serial is its last argument.
private val START_DRAG_SERIAL = Regex("\\.start_drag\\([^)]*,\\s*(\\d+)\\s*\\)")

// button(serial, time, button, state): state 1 is a press, and only a press begins a grab.
private val BUTTON_PRESS_SERIAL = Regex("\\.button\\((\\d+),\\s*\\d+,\\s*\\d+,\\s*1\\)")

// wl_data_device_manager.dnd_action is a bitfield: copy is 1 and move is 2, so both together are 3.
private val COPY_AND_MOVE = Regex("\\.set_actions\\(3\\)")
private val COPY_ALONE = Regex("\\.set_actions\\(1,\\s*1\\)")
