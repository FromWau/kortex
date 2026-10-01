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

/** What the drag settled on, which reaches the source only through Compose's own completion channel. */
internal const val PROBE_MARKER_COMPLETED = "KORTEX-PROBE completed="

/** What the destination told its content the drop would do, which is not always what the compositor settled. */
internal const val PROBE_MARKER_TOOK_AS = "KORTEX-PROBE took-as="

/** Set to [PROBE_PAYLOAD_FILES] to drag files rather than the text every other leg carries. */
internal const val PROBE_PAYLOAD_VAR = "KORTEX_DRAG_PAYLOAD"
internal const val PROBE_PAYLOAD_FILES = "files"

/** Set to `"true"` to have the target decline the drop it is handed rather than take it. */
internal const val PROBE_REFUSE_VAR = "KORTEX_DROP_REFUSED"

/**
 * Printed where the target was handed the drop and declined it.
 *
 * The wire cannot tell that from a drop content never saw, and the two call for opposite verdicts: one is
 * the branch under test, the other is a drag that never arrived and proves nothing about it.
 */
internal const val PROBE_MARKER_REFUSED = "KORTEX-PROBE refused"

/** What a refused drop leaves behind, so the probe stops waiting on a payload that will never be set. */
internal const val PROBE_REFUSED = "refused"

/** What a file drag carries: two, and one with a percent-encoded space, so a mangled list cannot read right. */
internal val PROBE_DRAGGED_URIS = listOf("file:///tmp/one.txt", "file:///tmp/a%20file.txt")

// Read once here rather than per composition, since both are fixed for the life of the probe.
private val dragsFiles = System.getenv(PROBE_PAYLOAD_VAR) == PROBE_PAYLOAD_FILES
private val refusesDrop = System.getenv(PROBE_REFUSE_VAR) == "true"

/** The text the source offers, which the target must read back byte for byte. */
internal const val PROBE_DRAGGED_TEXT = "carried over the wire"

/**
 * Drags text out of one layer surface and into another with a virtual pointer, so [DragWireTest] can read the
 * `wl_data_device` traffic it takes to do it.
 *
 * [PROBE_PAYLOAD_VAR] chooses what is dragged and [PROBE_REFUSE_VAR] whether the target takes it, so the
 * same gesture serves each leg and only the one thing under test differs between them.
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
                    width = Length.Of(BOX.dp),
                    height = Length.Of(BOX.dp),
                ) { DraggableBox() }
                TestSurface(
                    TARGET_NAMESPACE,
                    anchor = setOf(Edge.Top, Edge.Right),
                    width = Length.Of(BOX.dp),
                    height = Length.Of(BOX.dp),
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
                    if (dragsFiles) KortexDragSource.Files(PROBE_DRAGGED_URIS)
                    else KortexDragSource.Text(PROBE_DRAGGED_TEXT),
                    // Both, so the wire carries a source that would let a drop move what it holds as well as
                    // copy it, and the compositor has two to match the destination's own against.
                    listOf(DragAndDropTransferAction.Copy, DragAndDropTransferAction.Move),
                    onTransferCompleted = { action ->
                        // Null is Compose's own way of saying the gesture did not complete, which is what the
                        // host answers with when it would not start the drag at all.
                        if (action == null) System.err.println(PROBE_MARKER_NOT_STARTED)
                        System.err.println(PROBE_MARKER_COMPLETED + action)
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
                        System.err.println(PROBE_MARKER_TOOK_AS + event.action)
                        if (refusesDrop) {
                            // Set before returning, so the wait outside ends on the refusal rather than
                            // running out the clock on a payload this leg never reads.
                            dropped.set(PROBE_REFUSED)
                            System.err.println(PROBE_MARKER_REFUSED)
                            return false
                        }
                        dropped.set(
                            if (dragsFiles) offer.readUris().getOrElse { null }?.toString()
                            else offer.readText().getOrElse { null },
                        )
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

private const val PLACE_MILLIS = 4_000L
private const val SETTLE_MILLIS = 250L
private const val DROP_MILLIS = 3_000L
private const val DROP_SETTLES = 4
