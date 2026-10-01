package com.fromwau.kortex.wayland

import androidx.compose.foundation.background
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.getOrElse
import com.fromwau.kern.result.map

/**
 * Puts one square on screen that takes anything dropped on it and prints what arrived, so a drag out of
 * another application can be made into kortex by hand.
 *
 * Nothing in the suite can do this: every drag test here is kortex to kortex, so the whole of
 * `wl_data_device`'s destination side is only ever exercised against a source kortex wrote. A file manager
 * offers types and actions kortex never does, which is the point of dragging from one.
 *
 * Run it under `WAYLAND_DEBUG=client` to read the `wl_data_offer` traffic beside what content saw.
 */
public fun main() {
    val display = WaylandDisplay.connect().getOrElse { error("live: no compositor answered: $it") }

    display.use { wayland ->
        val shell = KortexShell
            .createApplication(wayland) {
                TestSurface(
                    NAMESPACE,
                    anchor = setOf(Edge.Top, Edge.Left),
                    margins = Margins(top = MARGIN.dp, left = MARGIN.dp),
                    width = Length.Of(BOX.dp),
                    height = Length.Of(BOX.dp),
                ) { DropSquare() }
            }
            .getOrElse { error("live: shell create failed: $it") }

        try {
            check(shell.pumpOrFail(PLACE_MILLIS) { shell.shownSurfaces.size == 1 }) {
                "live: the square never reached the screen"
            }
            val placed = Screen.geometry(NAMESPACE)
            System.err.println("$MARKER_READY at $placed")
            System.err.println("LIVE: drop something on the square now; ${WAIT_MILLIS / 1_000}s to do it")

            shell.pumpOrFail(WAIT_MILLIS)
            System.err.println(MARKER_DONE)
        } finally {
            shell.close()
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun DropSquare() {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF2B6AE0))
            .dragAndDropTarget(
                // Anything at all: what a real source offers is the thing being found out here.
                shouldStartDragAndDrop = { start ->
                    System.err.println("$MARKER_OFFERED${(start.nativeEvent as? KortexDragOffer)?.types}")
                    true
                },
                target = object : DragAndDropTarget {
                    override fun onEntered(event: DragAndDropEvent) {
                        System.err.println("$MARKER_ENTERED${event.action}")
                    }

                    override fun onDrop(event: DragAndDropEvent): Boolean {
                        val offer = event.nativeEvent as? KortexDragOffer
                        System.err.println("$MARKER_DROPPED${event.action}")
                        System.err.println("$MARKER_TYPES${offer?.types}")
                        System.err.println("$MARKER_TEXT${offer?.readText()}")
                        // map, not let: the result is never null, so let reports an image for a drag with none.
                        System.err.println("$MARKER_IMAGE${offer?.readImage()?.map { "<image>" }}")
                        System.err.println("$MARKER_URIS${offer?.readUris()}")
                        // The drain nothing else reaches. Dolphin offers the portal type on an ordinary
                        // drag, so a sandboxed application is not what it takes to see one after all.
                        System.err.println("$MARKER_PORTAL${offer?.readPortalKey()}")
                        return true
                    }
                },
            ),
    )
}

private const val MARKER_READY = "LIVE-DROP ready"
private const val MARKER_OFFERED = "LIVE-DROP offered-types="
private const val MARKER_ENTERED = "LIVE-DROP entered-action="
private const val MARKER_DROPPED = "LIVE-DROP dropped-action="
private const val MARKER_TYPES = "LIVE-DROP dropped-types="
private const val MARKER_TEXT = "LIVE-DROP dropped-text="
private const val MARKER_IMAGE = "LIVE-DROP dropped-image="
private const val MARKER_URIS = "LIVE-DROP dropped-uris="
private const val MARKER_PORTAL = "LIVE-DROP dropped-portal-key="
private const val MARKER_DONE = "LIVE-DROP done"
private const val NAMESPACE = "kortex-live-drop"
private const val BOX = 260
private const val MARGIN = 200
private const val PLACE_MILLIS = 4_000L
private const val WAIT_MILLIS = 300_000L
