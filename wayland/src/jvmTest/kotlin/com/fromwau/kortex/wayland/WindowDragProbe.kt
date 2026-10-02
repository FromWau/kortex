package com.fromwau.kortex.wayland

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.getOrElse

// Read by WindowDragWireTest, which launches this file's main() in a child JVM under WAYLAND_DEBUG=client and
// reads the xdg_toplevel traffic between these markers.
internal const val PROBE_MARKER_DRAG_WINDOW_UP = "KORTEX-PROBE drag-window-up"
internal const val PROBE_MARKER_ASKED_UNPRESSED = "KORTEX-PROBE asked-unpressed"
internal const val PROBE_MARKER_ASKED_PRESSED = "KORTEX-PROBE asked-pressed"

/** The corner [WindowState.askResize] is asked for, which the test reads back off the wire. */
internal val PROBE_RESIZE_EDGE = ResizeEdge.BottomRight

/** Where [WindowState.askWindowMenu] is asked to open, distinctive so a dropped or swapped axis shows. */
internal val PROBE_MENU_AT = IntOffset(37, 53)

/**
 * Asks a window to move, to resize and to show its menu, once before the user has pressed anything and once
 * under a press, which are the three requests [WindowDragWireTest] reads.
 *
 * The wire is the only place either shows. Both hand the gesture to the compositor, which takes the pointer
 * for the rest of it, so the window's own state is told nothing and `hyprctl` reports a position the
 * compositor may have chosen for its own reasons.
 *
 * All three go out under one press rather than a press each. The serial is what the test reads, and one press
 * gives a valid one to all of them; pressing again would need the window's geometry read afresh, since the
 * first ask hands the window to the compositor to move where it likes.
 */
public fun main() {
    val display = WaylandDisplay.connect().getOrElse { error("probe: no compositor answered: $it") }
    val state = WindowState()

    display.use { wayland ->
        val shell = KortexShell
            .createApplication(wayland) {
                Window(title = TITLE, appId = APP_ID, width = WIDTH, height = HEIGHT, state = state) { Grey() }
            }
            .getOrElse { error("probe: shell create failed: $it") }

        try {
            check(shell.pumpOrFail(PLACE_MILLIS) { shell.shownSurfaces.size == 1 }) {
                "probe: the window never reached the screen"
            }
            val placed = checkNotNull(Hyprctl.window(TITLE)) { "probe: hyprctl lost the window" }
            System.err.println(PROBE_MARKER_DRAG_WINDOW_UP)

            // Nothing has been pressed, so no ask has a serial to quote and none may reach the wire.
            state.askMove()
            state.askResize(PROBE_RESIZE_EDGE)
            state.askWindowMenu(PROBE_MENU_AT)
            shell.pumpOrFail(ASK_MILLIS)
            System.err.println(PROBE_MARKER_ASKED_UNPRESSED)

            askUnderPress(shell, state, placed)
            System.err.println(PROBE_MARKER_ASKED_PRESSED)
        } finally {
            shell.close()
        }
    }
}

/** Floats [placed], presses in its middle, asks for both gestures, and releases. */
private fun askUnderPress(shell: KortexShell, state: WindowState, placed: HyprWindow) {
    val pointerDisplay = WaylandDisplay.connect().getOrElse { error("probe: no compositor for the pointer: $it") }
    pointerDisplay.use { pd ->
        val manager = VirtualPointerManager.bind(pd).getOrElse { error("probe: virtual pointer bind failed: $it") }
        val monitor = checkNotNull(Hyprctl.monitors().firstOrNull()) { "probe: hyprctl reported no monitor" }

        fun settle() {
            pd.roundtrip()
            shell.pumpOrFail(SETTLE_MILLIS)
        }

        // Floated, because tiled this window fills the screen and leaves nowhere off it to park the pointer.
        Hyprctl.dispatch("window.float", address = placed.address)
        check(shell.pumpOrFail(SETTLE_MILLIS) { Hyprctl.window(TITLE)?.floating == true }) {
            "probe: the compositor was asked to float the window and never did"
        }
        val floated = checkNotNull(Hyprctl.window(TITLE)) { "probe: hyprctl lost the window after floating it" }
        val park = parkOff(floated, monitor)

        manager.createVirtualPointer().use { pointer ->
            // Off the window first: the compositor re-evaluates focus on motion, so a cursor already parked on
            // it would never enter it and the press would land nowhere.
            pointer.moveTo(monitor, park.x, park.y)
            settle()

            pointer.moveTo(monitor, floated.at.x + floated.size.width / 2, floated.at.y + floated.size.height / 2)
            settle()
            pointer.button(BTN_LEFT, pressed = true)
            pointer.frame()
            settle()

            state.askMove()
            settle()
            state.askResize(PROBE_RESIZE_EDGE)
            settle()
            state.askWindowMenu(PROBE_MENU_AT)
            settle()

            pointer.button(BTN_LEFT, pressed = false)
            pointer.frame()
            settle()
        }
    }
}

private const val TITLE = "kortex window drag probe"
private const val APP_ID = "kortex-window-drag-probe"
private const val PLACE_MILLIS = 6_000L
private const val ASK_MILLIS = 1_000L
private const val SETTLE_MILLIS = 300L
private val WIDTH = 640.dp
private val HEIGHT = 480.dp

/**
 * A point on [monitor] that is outside [window], for parking the pointer before it enters.
 *
 * One of the four corners, since a floating window sits mid-screen and cannot cover them all. Checked rather
 * than assumed, so a window that does says so here instead of failing later as a dropped press.
 */
private fun parkOff(window: HyprWindow, monitor: HyprMonitor): IntOffset {
    val corners = listOf(
        IntOffset(0, 0),
        IntOffset(monitor.logicalWidth - 1, 0),
        IntOffset(0, monitor.logicalHeight - 1),
        IntOffset(monitor.logicalWidth - 1, monitor.logicalHeight - 1),
    )

    return corners.firstOrNull { corner ->
        corner.x < window.at.x || corner.x >= window.at.x + window.size.width ||
            corner.y < window.at.y || corner.y >= window.at.y + window.size.height
    } ?: error("probe: the window covers every corner of $monitor, so there is nowhere off it to park")
}
