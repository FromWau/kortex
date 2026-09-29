package com.fromwau.kortex.wayland

import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.getOrElse

// Read by WindowDragWireTest, which launches this file's main() in a child JVM under WAYLAND_DEBUG=client and
// reads the xdg_toplevel traffic between these markers.
internal const val PROBE_MARKER_DRAG_WINDOW_UP = "KORTEX-PROBE drag-window-up"
internal const val PROBE_MARKER_ASKED_UNPRESSED = "KORTEX-PROBE asked-unpressed"
internal const val PROBE_MARKER_ASKED_PRESSED = "KORTEX-PROBE asked-pressed"

/** The corner [WindowState.askResize] is asked for, which the test reads back off the wire. */
internal val PROBE_RESIZE_EDGE = ResizeEdge.BottomRight

/**
 * Asks a window to move and to resize, once before the user has pressed anything and once under a press, which
 * are the two requests [WindowDragWireTest] reads.
 *
 * The wire is the only place either shows. Both hand the gesture to the compositor, which takes the pointer
 * for the rest of it, so the window's own state is told nothing and `hyprctl` reports a position the
 * compositor may have chosen for its own reasons.
 *
 * Both asks go out under one press rather than a press each. The serial is what the test reads, and one press
 * gives a valid one to both; pressing twice would need the window's geometry read again in between, since the
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

            // Nothing has been pressed, so neither ask has a serial to quote and neither may reach the wire.
            state.askMove()
            state.askResize(PROBE_RESIZE_EDGE)
            shell.pumpOrFail(ASK_MILLIS)
            System.err.println(PROBE_MARKER_ASKED_UNPRESSED)

            askUnderPress(shell, state, placed)
            System.err.println(PROBE_MARKER_ASKED_PRESSED)
        } finally {
            shell.close()
        }
    }
}

/** Presses in the middle of [placed], asks for both gestures, and releases. */
private fun askUnderPress(shell: KortexShell, state: WindowState, placed: HyprWindow) {
    val pointerDisplay = WaylandDisplay.connect().getOrElse { error("probe: no compositor for the pointer: $it") }
    pointerDisplay.use { pd ->
        val manager = VirtualPointerManager.bind(pd).getOrElse { error("probe: virtual pointer bind failed: $it") }
        val monitor = checkNotNull(Hyprctl.monitors().firstOrNull()) { "probe: hyprctl reported no monitor" }

        fun settle() {
            pd.roundtrip()
            shell.pumpOrFail(SETTLE_MILLIS)
        }

        manager.createVirtualPointer().use { pointer ->
            // Off the window first: the compositor re-evaluates focus on motion, so a cursor already parked on
            // it would never enter it and the press would land nowhere.
            pointer.moveTo(monitor, monitor.logicalWidth / 2, monitor.logicalHeight / 2)
            settle()

            pointer.moveTo(monitor, placed.at.x + placed.size.width / 2, placed.at.y + placed.size.height / 2)
            settle()
            pointer.button(BTN_LEFT, pressed = true)
            pointer.frame()
            settle()

            state.askMove()
            settle()
            state.askResize(PROBE_RESIZE_EDGE)
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
