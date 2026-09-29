package com.fromwau.kortex.wayland

import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.getOrElse

// Read by MinimizeWireTest, which launches this file's main() in a child JVM under WAYLAND_DEBUG=client and
// reads the xdg_toplevel traffic between these markers.
internal const val PROBE_MARKER_WINDOW_UP = "KORTEX-PROBE window-up"
internal const val PROBE_MARKER_ASKED_MINIMIZED = "KORTEX-PROBE asked-minimized"

/**
 * Puts a window on screen and asks for it to be minimized, which is the request [MinimizeWireTest] reads.
 *
 * The wire is the only place that ask shows. `xdg_toplevel` carries no minimized state and no event back, so
 * the window's own state cannot report it, and Hyprland has no minimized windows for `hyprctl` to list either.
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
            check(state.canMinimize) { "probe: the compositor says it does not honour a minimize" }
            System.err.println(PROBE_MARKER_WINDOW_UP)

            state.askMinimized()
            // The ask is queued from whatever thread calls it and leaves on the shell's next pass.
            shell.pumpOrFail(ASK_MILLIS)
            System.err.println(PROBE_MARKER_ASKED_MINIMIZED)
        } finally {
            shell.close()
        }
    }
}

private const val TITLE = "kortex minimize probe"
private const val APP_ID = "kortex-minimize-probe"
private const val PLACE_MILLIS = 6_000L
private const val ASK_MILLIS = 1_000L
private val WIDTH = 640.dp
private val HEIGHT = 480.dp
