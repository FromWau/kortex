package com.fromwau.kortex.wayland

import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.getOrElse

// Read by WindowSizeBoundsWireTest, which launches this file's main() in a child JVM under
// WAYLAND_DEBUG=client and reads the xdg_toplevel traffic between these markers.
internal const val PROBE_MARKER_BOUNDS_WINDOW_UP = "KORTEX-PROBE bounds-window-up"
internal const val PROBE_MARKER_BOUNDS_ASKED = "KORTEX-PROBE bounds-asked"
internal const val PROBE_MARKER_RECOMMENDED = "KORTEX-PROBE recommended="

/** The bounds asked for, read back off the wire. Both sit either side of the window's own size. */
internal const val PROBE_MIN_WIDTH = 321
internal const val PROBE_MIN_HEIGHT = 213
internal const val PROBE_MAX_WIDTH = 987
internal const val PROBE_MAX_HEIGHT = 654

/**
 * Asks a window for a minimum and a maximum size, which is what [WindowSizeBoundsWireTest] reads.
 *
 * The wire is the only place either shows. Both are double-buffered state the compositor answers with
 * nothing, and `hyprctl` reports a size it would have chosen anyway while both bounds sit either side of it.
 *
 * Also prints what the compositor recommended, which is how the claim that Hyprland sends no
 * `configure_bounds` stays checked rather than remembered.
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
            System.err.println(PROBE_MARKER_BOUNDS_WINDOW_UP)

            state.askMinSize(PROBE_MIN_WIDTH.dp, PROBE_MIN_HEIGHT.dp)
            state.askMaxSize(PROBE_MAX_WIDTH.dp, PROBE_MAX_HEIGHT.dp)
            // The asks are queued from whatever thread calls them and leave on the shell's next pass.
            shell.pumpOrFail(ASK_MILLIS)
            System.err.println(PROBE_MARKER_BOUNDS_ASKED)
            System.err.println(PROBE_MARKER_RECOMMENDED + state.recommendedMaxSize)
        } finally {
            shell.close()
        }
    }
}

private const val TITLE = "kortex size bounds probe"
private const val APP_ID = "kortex-size-bounds-probe"
private const val PLACE_MILLIS = 6_000L
private const val ASK_MILLIS = 1_000L
private val WIDTH = 640.dp
private val HEIGHT = 480.dp
