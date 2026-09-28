package com.fromwau.kortex.wayland

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.getOrElse

// Read by PopupTeardownWireTest, which launches this file's main() in a child JVM under WAYLAND_DEBUG=client
// and reads the order of the destroys between these markers.
internal const val PROBE_MARKER_POPUP_UP = "KORTEX-PROBE popup-up"
internal const val PROBE_MARKER_TORN_DOWN = "KORTEX-PROBE torn-down"

/**
 * Puts a popup on screen inside a window and then ends the whole application while it is still up, which is
 * the sequence [PopupTeardownWireTest] reads the destroys of.
 *
 * Nothing here is unusual: an application closing with a menu open does exactly this, and so does every test
 * that opens a popup and lets its block return.
 */
public fun main() {
    val display = WaylandDisplay.connect().getOrElse { error("probe: no compositor answered: $it") }

    display.use { wayland ->
        val shell = KortexShell
            .createApplication(wayland) {
                Window(title = TITLE, appId = APP_ID, width = WIDTH, height = HEIGHT) {
                    Popup(at = AT, width = MENU_WIDTH, height = MENU_HEIGHT) { Grey() }
                }
            }
            .getOrElse { error("probe: shell create failed: $it") }

        // Both on screen: the window, and the popup its content opened inside it.
        check(shell.pumpOrFail(PLACE_MILLIS) { shell.shownSurfaces.size == 2 }) {
            "probe: the window and its popup never both reached the screen"
        }
        System.err.println(PROBE_MARKER_POPUP_UP)

        // The popup is never dismissed first: closing the shell with one still up is the whole point.
        shell.close()
        System.err.println(PROBE_MARKER_TORN_DOWN)
    }
}

private const val TITLE = "kortex popup teardown probe"
private const val APP_ID = "kortex-popup-teardown-probe"
private const val PLACE_MILLIS = 6_000L
private val WIDTH = 640.dp
private val HEIGHT = 480.dp
private val MENU_WIDTH = 200.dp
private val MENU_HEIGHT = 120.dp
private val AT = IntOffset(40, 8)
