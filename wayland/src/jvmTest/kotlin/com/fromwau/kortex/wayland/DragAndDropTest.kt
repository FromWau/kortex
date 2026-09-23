package com.fromwau.kortex.wayland

import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.getOrElse
import com.fromwau.kortex.compose.KortexScene
import kotlinx.coroutines.asCoroutineDispatcher
import org.jetbrains.skia.Surface
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Drives a drag through a real composition exactly as the compositor's enter, motion, leave and drop do, so
 * the seam between `wl_data_device` and Compose's drop targets is covered without a human dragging anything.
 */
@OptIn(ExperimentalComposeUiApi::class)
class DragAndDropTest {
    @Test
    fun `an enter, a motion and a drop reach a drop target in that order`() {
        val seen = CopyOnWriteArrayList<String>()

        withDropTarget(seen) { scene ->
            val offer = dragOffer(TEXT_TYPES, DRAGGED_TEXT)
            val taken = scene.sendDragEnter(CENTRE, offer)
                .getOrElse { failure -> fail("content failed as the drag arrived: $failure") }

            assertTrue(taken, "content holding a drop target refused the drag")
            assertEquals(
                listOf(STARTED, ENTERED, MOVED), seen.toList(),
                "a drag arriving over content did not reach its drop target",
            )

            scene.sendDragMove(CENTRE, offer)
            val dropped = scene.sendDrop(CENTRE, offer)
                .getOrElse { failure -> fail("content failed as the drag was dropped: $failure") }

            assertTrue(dropped, "the drop target did not take what was dropped on it")
            assertEquals(
                listOf(STARTED, ENTERED, MOVED, MOVED, DROP, ENDED), seen.toList(),
                "an enter, a motion and a drop did not reach the drop target in that order",
            )
        }
    }

    @Test
    fun `a drop hands content the types the drag was offered under and the text it carried`() {
        val dropped = AtomicReference<KortexDragOffer?>(null)

        withDropTarget(onDropped = dropped::set) { scene ->
            // Advertised in the other order, since a source lists its types in whichever it likes.
            val offer = dragOffer(TEXT_TYPES.reversed(), DRAGGED_TEXT)
            scene.sendDragEnter(CENTRE, offer)
            scene.sendDrop(CENTRE, offer)
        }

        val carried = assertNotNull(dropped.get(), "the drop never reached content")
        assertEquals(
            TEXT_TYPES, carried.types,
            "content was told the drag offered other types than the source advertised",
        )
        assertEquals(Ok(DRAGGED_TEXT), carried.readText(), "the drag's text did not reach content")
    }

    @Test
    fun `a leave with no drop ends the drag and drops nothing on the target`() {
        val seen = CopyOnWriteArrayList<String>()

        withDropTarget(seen) { scene ->
            val offer = dragOffer(TEXT_TYPES, DRAGGED_TEXT)
            scene.sendDragEnter(CENTRE, offer)
            scene.sendDragMove(CENTRE, offer)
            scene.sendDragLeave(CENTRE, offer)
        }

        assertEquals(
            listOf(STARTED, ENTERED, MOVED, MOVED, EXITED, ENDED), seen.toList(),
            "a drag that left without dropping did not end at the drop target, or dropped on it anyway",
        )
    }

    /** What the compositor introduces: an offer listing the wire names [advertised], carrying [text]. */
    private fun dragOffer(advertised: List<String>, text: String): KortexDragOffer =
        Arena.ofShared().use { arena ->
            val offer = DataOffer(arena)
            advertised.forEach { offer.onOffer(NULL, NULL, arena.allocateFrom(it)) }
            KortexDragOffer(offer.offeredTypes, text = Ok(text))
        }

    /** Runs [block] on a scene whose content is one drop target, which records every event it is sent in [seen]. */
    private fun withDropTarget(
        seen: MutableList<String> = CopyOnWriteArrayList(),
        onDropped: (KortexDragOffer) -> Unit = {},
        block: (KortexScene) -> Unit,
    ) {
        val target = object : DragAndDropTarget {
            override fun onStarted(event: DragAndDropEvent) {
                seen += STARTED
            }

            override fun onEntered(event: DragAndDropEvent) {
                seen += ENTERED
            }

            override fun onMoved(event: DragAndDropEvent) {
                seen += MOVED
            }

            override fun onExited(event: DragAndDropEvent) {
                seen += EXITED
            }

            override fun onEnded(event: DragAndDropEvent) {
                seen += ENDED
            }

            override fun onDrop(event: DragAndDropEvent): Boolean {
                seen += DROP
                onDropped(event.nativeEvent as KortexDragOffer)
                return true
            }
        }
        val dispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "kortex-drag-test").apply { isDaemon = true }
        }.asCoroutineDispatcher()
        val surface = Surface.makeRasterN32Premul(SIDE, SIDE)
        dispatcher.use {
            KortexScene(
                size = IntSize(SIDE, SIDE),
                density = Density(1f),
                layoutDirection = LayoutDirection.Ltr,
                frameContext = dispatcher,
                onInvalidate = {},
            ).use { scene ->
                scene.setContent {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .dragAndDropTarget(
                                shouldStartDragAndDrop = { start ->
                                    (start.nativeEvent as? KortexDragOffer)?.types.orEmpty().isNotEmpty()
                                },
                                target = target,
                            ),
                    )
                }
                // A drop is routed by where it is, which needs the target laid out and its bounds known.
                scene.render(surface.canvas.asComposeCanvas(), 0L)
                block(scene)
            }
        }
    }

    private companion object {
        val NULL: MemorySegment = MemorySegment.NULL
        const val SIDE = 64
        val CENTRE = Offset(SIDE / 2f, SIDE / 2f)

        val TEXT_TYPES = listOf("text/plain;charset=utf-8", "text/plain")
        const val DRAGGED_TEXT = "dragged"

        const val STARTED = "started"
        const val ENTERED = "entered"
        const val MOVED = "moved"
        const val EXITED = "exited"
        const val ENDED = "ended"
        const val DROP = "drop"
    }
}
