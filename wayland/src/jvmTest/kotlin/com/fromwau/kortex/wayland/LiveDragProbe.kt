package com.fromwau.kortex.wayland

import androidx.compose.foundation.background
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropTransferAction
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.getOrElse
import com.fromwau.kortex.compose.KortexDragSource
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/**
 * Puts one draggable square on screen and waits, so a drag out of kortex can be made by hand.
 *
 * [DragWireProbe] drives the same gesture with a virtual pointer, and that gesture is under suspicion: it
 * sends no `wl_data_device.start_drag` in most whole-suite runs while sending one when run alone. A hand
 * makes the press, the slop and the motion the way a compositor expects them, so a drag that works here and
 * not there puts the fault in the driving rather than in the drag.
 *
 * [PROBE_PAYLOAD_VAR] set to [PROBE_PAYLOAD_FILES] drags files instead of a text, which is the leg worth a
 * hand: a file manager is the only destination that can say whether `text/uri-list` out of kortex reads as
 * files to something that was not written alongside it.
 *
 * Run it under `WAYLAND_DEBUG=client` and read the same markers [DragWireTest] reads.
 */
public fun main() {
    val display = WaylandDisplay.connect().getOrElse { error("live: no compositor answered: $it") }
    if (dragsFiles) writeDraggedFiles()

    display.use { wayland ->
        val shell = KortexShell
            .createApplication(wayland) {
                TestSurface(
                    NAMESPACE,
                    anchor = setOf(Edge.Top, Edge.Left),
                    margins = Margins(top = MARGIN.dp, left = MARGIN.dp),
                    width = Length.Of(BOX.dp),
                    height = Length.Of(BOX.dp),
                ) { DraggableSquare() }
            }
            .getOrElse { error("live: shell create failed: $it") }

        try {
            check(shell.pumpOrFail(PLACE_MILLIS) { shell.shownSurfaces.size == 1 }) {
                "live: the square never reached the screen"
            }
            val placed = Screen.geometry(NAMESPACE)
            System.err.println("$PROBE_MARKER_DRAG_PLACED at $placed")
            val carrying = if (dragsFiles) PROBE_DRAGGED_URIS.toString() else "a text"
            System.err.println("LIVE: drag $carrying out of the square now; ${WAIT_MILLIS / 1_000}s to do it")

            shell.pumpOrFail(WAIT_MILLIS)
            System.err.println(PROBE_MARKER_DRAG_DRIVEN)
        } finally {
            shell.close()
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun DraggableSquare() {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFFE05A2B))
            .dragAndDropSource(drawDragDecoration = {}) {
                System.err.println(PROBE_MARKER_ASKED)
                DragAndDropTransferData(
                    if (dragsFiles) KortexDragSource.Files(PROBE_DRAGGED_URIS)
                    else KortexDragSource.Text(PROBE_DRAGGED_TEXT),
                    // Copy alone, deliberately: Hyprland picks move whenever a source offers it, and a move
                    // out of here has the destination take the files and this side delete them.
                    listOf(DragAndDropTransferAction.Copy),
                    onTransferCompleted = { action ->
                        if (action == null) System.err.println(PROBE_MARKER_NOT_STARTED)
                        System.err.println(PROBE_MARKER_COMPLETED + action)
                    },
                )
            },
    )
}

// Read once rather than per composition, since the payload is fixed for the life of the probe.
private val dragsFiles = System.getenv(PROBE_PAYLOAD_VAR) == PROBE_PAYLOAD_FILES

/** The files [PROBE_DRAGGED_URIS] names, so a drop into a file manager lands on something real. */
private fun writeDraggedFiles() {
    PROBE_DRAGGED_URIS.forEach { uri ->
        val path = Path.of(URI(uri))
        Files.writeString(path, "dragged out of kortex\n")
        System.err.println("LIVE: wrote $path")
    }
}

private const val NAMESPACE = "kortex-live-drag"
private const val BOX = 160
private const val MARGIN = 200
private const val PLACE_MILLIS = 4_000L
private const val WAIT_MILLIS = 120_000L
