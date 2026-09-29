package com.fromwau.kortex.wayland

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * That [WindowState.askMove], [WindowState.askResize] and [WindowState.askWindowMenu] reach the compositor
 * quoting the press the user is holding, which only the wire shows.
 *
 * Each hands the gesture to the compositor, which takes the pointer for the rest of it, so neither the
 * window's own state nor `hyprctl` can say whether the ask arrived: a compositor that ignored one and a client
 * that never sent it leave the same window in the same place.
 *
 * Drives the user's pointer and hands the compositor a window to move, so like the other window tests this
 * runs only in a session kept free for it.
 */
class WindowDragWireTest {
    @Test
    fun `a window asked to move, resize or show its menu sends each quoting a press, and none without one`() {
        val (exitCode, output) = runProbe(
            "com.fromwau.kortex.wayland.WindowDragProbeKt",
            environment = mapOf("WAYLAND_DEBUG" to "client"),
        )
        val raw = output.joinToString("\n")
        assertEquals(0, exitCode, "the window drag probe exited $exitCode; output:\n$raw")

        val upAt = output.indexOfFirst { it == PROBE_MARKER_DRAG_WINDOW_UP }
        val unpressedAt = output.indexOfFirst { it == PROBE_MARKER_ASKED_UNPRESSED }
        val pressedAt = output.indexOfFirst { it == PROBE_MARKER_ASKED_PRESSED }
        assertTrue(
            upAt >= 0 && unpressedAt > upAt && pressedAt > unpressedAt,
            "the probe's markers are missing or out of order " +
                "(up=$upAt unpressed=$unpressedAt pressed=$pressedAt); output:\n$raw",
        )

        // Asked before anything was pressed, so neither request has a grab to name and neither may go out. A
        // compositor refuses one naming no press by ignoring it, so sending it anyway is invisible from here
        // and would leave content holding a gesture that silently does nothing.
        val unpressed = output.subList(upAt + 1, unpressedAt)
        assertEquals(
            emptyList(),
            unpressed.filter {
                it.isRequest("xdg_toplevel", "move") ||
                    it.isRequest("xdg_toplevel", "resize") ||
                    it.isRequest("xdg_toplevel", "show_window_menu")
            },
            "a window asked before the user pressed anything sent the ask anyway; traffic:\n" +
                unpressed.traceOf("xdg_toplevel"),
        )

        val pressed = output.subList(unpressedAt + 1, pressedAt)
        val traffic = pressed.traceOf("xdg_toplevel", "wl_pointer")
        val presses = pressed.mapNotNull { BUTTON_PRESS_SERIAL.find(it)?.groupValues?.get(1)?.toIntOrNull() }
        assertTrue(
            presses.isNotEmpty(),
            "no wl_pointer.button press reached the client, so no ask could quote one; traffic:\n$traffic",
        )

        // The serial each request quotes has to be a press's own. Its argument names "the serial of the
        // implicit grab on the pointer", and a compositor checking it refuses a move whose serial names no
        // such grab, the way KWin refuses a start_drag that does.
        val move = assertNotNull(
            pressed.firstOrNull { it.isRequest("xdg_toplevel", "move") },
            "the window was asked to move and no move left the client; traffic:\n$traffic",
        )
        val movedWith = assertNotNull(
            MOVE_SERIAL.find(move)?.groupValues?.get(1)?.toIntOrNull(),
            "could not read move's serial from: $move",
        )
        assertTrue(movedWith in presses, "move quoted $movedWith, which is no press's serial (presses: $presses)")

        val resize = assertNotNull(
            pressed.firstOrNull { it.isRequest("xdg_toplevel", "resize") },
            "the window was asked to resize and no resize left the client; traffic:\n$traffic",
        )
        val resized = assertNotNull(
            RESIZE_ARGS.find(resize),
            "could not read resize's serial and edge from: $resize",
        )
        val resizedWith = assertNotNull(
            resized.groupValues[1].toIntOrNull(),
            "could not read resize's serial from: $resize",
        )
        assertTrue(
            resizedWith in presses,
            "resize quoted $resizedWith, which is no press's serial (presses: $presses)",
        )

        // And the corner, which is the one argument a caller chooses: a resize sent under the wrong edge
        // drags the opposite side of the window and reads as correct everywhere but here.
        assertEquals(
            PROBE_RESIZE_EDGE.wireValue, resized.groupValues[2].toIntOrNull(),
            "the window was asked to resize by $PROBE_RESIZE_EDGE and some other edge left the client: $resize",
        )

        val menu = assertNotNull(
            pressed.firstOrNull { it.isRequest("xdg_toplevel", "show_window_menu") },
            "the window was asked to show its menu and no show_window_menu left the client; traffic:\n$traffic",
        )
        val shown = assertNotNull(
            MENU_ARGS.find(menu),
            "could not read show_window_menu's serial and position from: $menu",
        )
        assertTrue(
            shown.groupValues[1].toIntOrNull() in presses,
            "show_window_menu quoted ${shown.groupValues[1]}, which is no press's serial (presses: $presses)",
        )
        // Both axes, since a menu opened at the transposed point is in the window either way and only a
        // distinctive pair tells the two apart.
        assertEquals(
            listOf(PROBE_MENU_AT.x, PROBE_MENU_AT.y),
            listOf(shown.groupValues[2].toIntOrNull(), shown.groupValues[3].toIntOrNull()),
            "the window asked for its menu at $PROBE_MENU_AT and some other point left the client: $menu",
        )
    }
}

// move(seat, serial): the serial is its last argument.
private val MOVE_SERIAL = Regex("\\.move\\([^)]*,\\s*(\\d+)\\s*\\)")

// resize(seat, serial, edges): the serial, then the xdg_toplevel.resize_edge it drags.
private val RESIZE_ARGS = Regex("\\.resize\\([^,]*,\\s*(\\d+),\\s*(\\d+)\\s*\\)")

// show_window_menu(seat, serial, x, y): the serial, then where the menu opens in the window.
private val MENU_ARGS = Regex("\\.show_window_menu\\([^,]*,\\s*(\\d+),\\s*(-?\\d+),\\s*(-?\\d+)\\s*\\)")
