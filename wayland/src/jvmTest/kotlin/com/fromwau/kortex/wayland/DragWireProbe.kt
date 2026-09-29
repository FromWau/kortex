package com.fromwau.kortex.wayland

import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferAction
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.getOrElse
import com.fromwau.kortex.compose.KortexDragSource
import java.util.concurrent.atomic.AtomicReference

// Read by DragWireTest, which launches this file's main() in a child JVM under WAYLAND_DEBUG=client and
// reads the wl_data_device traffic between these markers.
internal const val PROBE_MARKER_DRAG_PLACED = "KORTEX-PROBE drag-placed"
internal const val PROBE_MARKER_DRAG_DRIVEN = "KORTEX-PROBE drag-driven"
internal const val PROBE_MARKER_DROPPED = "KORTEX-PROBE dropped="

// The two sides of a drag that sends no start_drag, which the wire alone cannot tell apart: Compose never
// asking the host to carry the payload, and the host being asked and failing to start it.
internal const val PROBE_MARKER_ASKED = "KORTEX-PROBE compose-asked"
internal const val PROBE_MARKER_NOT_STARTED = "KORTEX-PROBE not-started"

/** The text the source offers, which the target must read back byte for byte. */
internal const val PROBE_DRAGGED_TEXT = "carried over the wire"

/**
 * Drags text out of one layer surface and into another with a virtual pointer, so [DragWireTest] can read the
 * `wl_data_device` traffic it takes to do it.
 *
 * In a child JVM because libwayland reads `WAYLAND_DEBUG` once per process and never clears it. The pointer
 * drives from a connection of its own, which carries no `wl_data_*` object, so the trace stays unambiguous.
 */
public fun main() {
    val display = WaylandDisplay.connect().getOrElse { error("probe: no compositor answered: $it") }
    val dropped = AtomicReference<String?>(null)

    display.use { wayland ->
        val shell = KortexShell
            .createApplication(wayland) {
                TestSurface(
                    SOURCE_NAMESPACE,
                    anchor = setOf(Edge.Top, Edge.Left),
                    width = BOX.dp,
                    height = BOX.dp,
                ) { DraggableBox() }
                TestSurface(
                    TARGET_NAMESPACE,
                    anchor = setOf(Edge.Top, Edge.Right),
                    width = BOX.dp,
                    height = BOX.dp,
                ) { DropTarget(dropped) }
            }
            .getOrElse { error("probe: shell create failed: $it") }

        try {
            check(shell.pumpOrFail(PLACE_MILLIS) { shell.shownSurfaces.size == 2 }) {
                "probe: the two surfaces never reached the screen"
            }
            val from = checkNotNull(Screen.geometry(SOURCE_NAMESPACE)) { "probe: hyprctl lost the source" }
            val to = checkNotNull(Screen.geometry(TARGET_NAMESPACE)) { "probe: hyprctl lost the target" }
            System.err.println(PROBE_MARKER_DRAG_PLACED)

            dragAcross(shell, from, to)
            System.err.println(PROBE_MARKER_DRAG_DRIVEN)

            // The drop's own transfers drain off the loop, so the text lands a few passes after the release.
            shell.pumpOrFail(DROP_MILLIS) { dropped.get() != null }
            System.err.println(PROBE_MARKER_DROPPED + dropped.get())
        } finally {
            shell.close()
        }
    }
}

/** Presses on [from], crosses to [to] a step at a time as a hand would, and releases there. */
private fun dragAcross(shell: KortexShell, from: LayerGeometry, to: LayerGeometry) {
    val pointerDisplay = WaylandDisplay.connect().getOrElse { error("probe: no compositor for the pointer: $it") }
    pointerDisplay.use { pd ->
        val manager = VirtualPointerManager.bind(pd).getOrElse { error("probe: virtual pointer bind failed: $it") }
        val monitor = checkNotNull(Hyprctl.monitors().firstOrNull()) { "probe: hyprctl reported no monitor" }

        fun settle(times: Int = 1) = repeat(times) {
            pd.roundtrip()
            shell.pumpOrFail(SETTLE_MILLIS)
        }

        manager.createVirtualPointer().use { pointer ->
            // Off both surfaces first: the compositor re-evaluates focus on motion, so a cursor already parked
            // on the source would never enter it and the press would land nowhere.
            pointer.moveTo(monitor, monitor.logicalWidth / 2, monitor.logicalHeight / 2)
            settle()

            pointer.moveTo(monitor, from.x + BOX / 2, from.y + BOX / 2)
            settle()
            pointer.button(BTN_LEFT, pressed = true)
            pointer.frame()
            settle()

            // Past Compose's own drag slop without leaving the surface, which is the whole difficulty: a
            // press at the middle of the box is half its width from the edge, so a slop wider than that
            // lands outside, and a pointer that leaves gets wl_pointer.leave rather than motion. Compose
            // sees no movement at all then and asks for no transfer. Crossed a step at a time, as the
            // traverse below is and as a hand does, since one jump is one event and a gesture wants several.
            repeat(SLOP_STEPS) { step ->
                pointer.moveTo(monitor, from.x + BOX / 2 + SLOP * (step + 1) / SLOP_STEPS, from.y + BOX / 2)
                settle()
            }

            val fromX = from.x + BOX / 2
            val toX = to.x + BOX / 2
            repeat(STEPS) { step ->

                pointer.moveTo(monitor, fromX + (toX - fromX) * (step + 1) / STEPS, to.y + BOX / 2)
                settle()
            }

            // Moved about inside the target before letting go. The traverse above enters it on its last step,
            // so a drag that answers the compositor per motion and one that answers once, at the enter, leave
            // exactly the same wire without these.
            repeat(INSIDE_STEPS) { step ->
                pointer.moveTo(monitor, toX, to.y + BOX / 2 + INSIDE_STEP * (step + 1))
                settle()
            }

            pointer.button(BTN_LEFT, pressed = false)

            pointer.frame()
            settle(DROP_SETTLES)
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun DraggableBox() {
    Box(
        Modifier
            .fillMaxSize()
            .dragAndDropSource(drawDragDecoration = {}) {
                // Printed where Compose decides a drag has begun, so a run that sends no start_drag says
                // whether the gesture was ever recognised or whether the host was asked and could not.
                System.err.println(PROBE_MARKER_ASKED)
                DragAndDropTransferData(
                    KortexDragSource.Text(PROBE_DRAGGED_TEXT),
                    listOf(DragAndDropTransferAction.Copy),
                    // Null is Compose's own way of saying the gesture did not complete, which is what the
                    // host answers with when it took the drag on and then could not start it.
                    onTransferCompleted = { action ->
                        if (action == null) System.err.println(PROBE_MARKER_NOT_STARTED)
                    },
                )
            },
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun DropTarget(dropped: AtomicReference<String?>) {
    Box(
        Modifier
            .fillMaxSize()
            .dragAndDropTarget(
                shouldStartDragAndDrop = { true },
                target = object : DragAndDropTarget {
                    override fun onDrop(event: DragAndDropEvent): Boolean {
                        val offer = event.nativeEvent as? KortexDragOffer ?: return false
                        dropped.set(offer.readText().getOrElse { null })
                        return true
                    }
                },
            ),
    )
}

private const val SOURCE_NAMESPACE = "kortex-drag-wire-source"
private const val TARGET_NAMESPACE = "kortex-drag-wire-target"
// Wide enough that the slop below is crossed well inside the box: at 64 the middle was 32 from the edge
// and a 40 slop left the surface before Compose ever called the movement a drag.
private const val BOX = 160
private const val SLOP = 40
private const val SLOP_STEPS = 4
private const val STEPS = 8
// Well inside the box, whose half-height is 80, so every one of these lands on the target.
private const val INSIDE_STEPS = 3
private const val INSIDE_STEP = 8

private const val BTN_LEFT = 0x110
private const val PLACE_MILLIS = 4_000L
private const val SETTLE_MILLIS = 250L
private const val DROP_MILLIS = 3_000L
private const val DROP_SETTLES = 4
