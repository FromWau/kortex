package com.fromwau.kortex.wayland

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.getOrElse

// Read by PopupGrabWireTest, which launches this file's main() in a child JVM under WAYLAND_DEBUG=client and
// reads the xdg_popup traffic between these markers.
internal const val PROBE_MARKER_GRAB_BAR_UP = "KORTEX-PROBE grab-bar-up"
internal const val PROBE_MARKER_GRAB_MENU_UP = "KORTEX-PROBE grab-menu-up"
internal const val PROBE_MARKER_GRAB_MENU_ENDED = "KORTEX-PROBE grab-menu-ended="
internal const val PROBE_MARKER_GRAB_POPUP_UP = "KORTEX-PROBE grab-popup-up"
internal const val PROBE_MARKER_GRAB_POPUP_AFTER = "KORTEX-PROBE grab-popup-after-click="

/**
 * Opens a [ContextMenu] from a click and then clicks away from it, and does the same with a plain [Popup], so
 * [PopupGrabWireTest] can read what each one asked the compositor for and what became of it.
 *
 * Every click lands on this probe's own bar, never on anything of the user's: a grab is dismissed by a click
 * anywhere outside the popup, and the far end of the bar is outside it.
 */
public fun main() {
    val display = WaylandDisplay.connect().getOrElse { error("probe: no compositor answered: $it") }
    val menu = SurfaceState()
    val popup = SurfaceState()
    val menuShowing = mutableStateOf(false)
    val popupShowing = mutableStateOf(false)

    display.use { wayland ->
        val content: @Composable KortexApplicationScope.() -> Unit = {
            LayerSurface(
                namespace = NAMESPACE,
                anchor = setOf(Edge.Bottom, Edge.Left, Edge.Right),
                height = BAR_THICKNESS,
                // Reserves nothing, so nothing the user has open is re-tiled around it.
                exclusiveZone = ExclusiveZone.Overlap,
            ) {
                if (menuShowing.value) ContextMenu(at = MENU_AT, menuSize = MENU_SIZE, state = menu) { Grey() }
                if (popupShowing.value) {
                    Popup(at = MENU_AT, width = MENU_W.dp, height = MENU_H.dp, state = popup) { Grey() }
                }
            }
        }
        val shell = KortexShell.createApplication(wayland, content = content)
            .getOrElse { error("probe: shell create failed: $it") }

        try {
            check(shell.pumpOrFail(PLACE_MILLIS) { shell.shownSurfaces.size == 1 }) {
                "probe: the bar never reached the screen"
            }
            val bar = checkNotNull(Screen.awaitGeometry(NAMESPACE)) { "probe: hyprctl lost the bar" }
            System.err.println(PROBE_MARKER_GRAB_BAR_UP)

            withClicks(shell, bar) { click ->
                // On the bar, so the shell has a pointer press to name its grab by. A menu opened with none
                // behind it is refused the grab and dismissed as it arrives.
                click(bar.x + MENU_AT.x, bar.y + bar.logicalHeight / 2)

                menuShowing.value = true
                check(shell.pumpOrFail(SHOW_MILLIS) { menu.status is SurfaceStatus.OnScreen }) {
                    "probe: the menu never reached the screen: ${menu.status}"
                }
                System.err.println(PROBE_MARKER_GRAB_MENU_UP)

                // Far end of the bar, which is outside the menu and so dismisses a grabbing popup.
                click(bar.x + bar.logicalWidth - EDGE_INSET, bar.y + bar.logicalHeight / 2)
                shell.pumpOrFail(DISMISS_MILLIS) { menu.status is SurfaceStatus.Ended }
                System.err.println(PROBE_MARKER_GRAB_MENU_ENDED + menu.status)

                menuShowing.value = false
                shell.pumpOrFail(SETTLE_MILLIS)

                // The same gesture against a popup that asks for no grab, which must outlive the click.
                click(bar.x + MENU_AT.x, bar.y + bar.logicalHeight / 2)
                popupShowing.value = true
                check(shell.pumpOrFail(SHOW_MILLIS) { popup.status is SurfaceStatus.OnScreen }) {
                    "probe: the popup never reached the screen: ${popup.status}"
                }
                System.err.println(PROBE_MARKER_GRAB_POPUP_UP)

                click(bar.x + bar.logicalWidth - EDGE_INSET, bar.y + bar.logicalHeight / 2)
                shell.pumpOrFail(DISMISS_MILLIS)
                System.err.println(PROBE_MARKER_GRAB_POPUP_AFTER + popup.status)
            }
        } finally {
            shell.close()
        }
    }
}

/** Hands [block] a click, driven from a connection of its own so the traced shell carries no pointer of its own. */
private fun withClicks(shell: KortexShell, bar: LayerGeometry, block: ((x: Int, y: Int) -> Unit) -> Unit) {
    val pointerDisplay = WaylandDisplay.connect().getOrElse { error("probe: no compositor for the pointer: $it") }
    pointerDisplay.use { pd ->
        val manager = VirtualPointerManager.bind(pd).getOrElse { error("probe: virtual pointer bind failed: $it") }
        val monitor = checkNotNull(Hyprctl.monitors().firstOrNull { it.name == bar.monitor }) {
            "probe: hyprctl lists no monitor called ${bar.monitor}"
        }
        manager.createVirtualPointer().use { pointer ->
            fun settle() {
                pd.roundtrip()
                shell.pumpOrFail(SETTLE_MILLIS)
            }
            block { x, y ->
                pointer.moveTo(monitor, x, y)
                settle()
                pointer.button(BTN_LEFT, pressed = true)
                pointer.frame()
                settle()
                pointer.button(BTN_LEFT, pressed = false)
                pointer.frame()
                settle()
            }
        }
    }
}

private const val NAMESPACE = "kortex-popup-grab-probe"
private const val BTN_LEFT = 0x110
private val BAR_THICKNESS = 64.dp
private const val MENU_W = 160
private const val MENU_H = 48
private val MENU_AT = IntOffset(40, 8)
private val MENU_SIZE = IntSize(MENU_W, MENU_H)
// How far in from the bar's right edge the dismissing click lands, clear of the menu at MENU_AT.
private const val EDGE_INSET = 20
private const val PLACE_MILLIS = 6_000L
private const val SHOW_MILLIS = 4_000L
private const val DISMISS_MILLIS = 3_000L
private const val SETTLE_MILLIS = 250L
