package com.fromwau.kortex.wayland

import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.getOrElse
import com.fromwau.kortex.compose.KortexDragSource
import com.fromwau.kortex.compose.KortexScene
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Surface
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Covers both directions of a drag: what one arriving does to the drop targets in a scene's content, by driving
 * [KortexScene]'s own enter, motion, leave and drop through a real composition, where the scale a surface draws at
 * puts a drag on that scene, and what a drag out of content offers.
 *
 * No test here starts a drag on the desktop: the one that connects binds a clipboard that has been handed no input
 * serial, which is what every drag out quotes, so it fails before a source is made.
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

    @Test
    fun `a drag reaches the scene in buffer pixels, not the surface-local ones the compositor sent`() {
        withDropTarget { scene ->
            // 512 in wl_fixed is 2 surface-local px, which a surface drawn at 2x lays its scene out at 4.
            assertEquals(Offset(4f, 8f), DragDestination(scene, scale = 2f).scenePosition(512, 1024))
            assertEquals(Offset(2f, 4f), DragDestination(scene, scale = 1f).scenePosition(512, 1024))
        }
    }

    @Test
    fun `a text dragged out is offered under every text type, and an image as PNG and JPEG`() {
        assertEquals(
            DRAG_OUT_TEXT_TYPES, offeredTypesOf(KortexDragSource.Text(DRAGGED_TEXT)),
            "the types a dragged text is offered under",
        )
        assertEquals(
            DRAG_OUT_IMAGE_TYPES, offeredTypesOf(KortexDragSource.Image(opaqueImage())),
            "the types a dragged image is offered under",
        )
    }

    @Test
    fun `a drag with no input to quote fails as NoInputSerial and asks the compositor for nothing`() {
        withUnfocusedClipboard { clipboard ->
            assertEquals(
                Err(ClipboardError.NoInputSerial),
                clipboard.startDrag(Clip.Text(DRAGGED_TEXT), origin = NULL),
                "a drag with no input serial must fail before a source is made or start_drag sent",
            )
            assertNull(
                clipboard.dragSource,
                "a drag with no input serial was carried to the compositor anyway",
            )
        }
    }

    /** What a drag of [dragged] offers, failing the test rather than returning why it could not be. */
    private fun offeredTypesOf(dragged: KortexDragSource): List<String> =
        dragged.asClip()
            .getOrElse { failure -> fail("a drag had nothing to offer: $failure") }
            .offeredTypes
            .map(Mime::wireName)

    /** A small opaque image, as content dragging a picture out holds one. */
    private fun opaqueImage(): ImageBitmap {
        val info = ImageInfo.makeN32(IMAGE_SIDE, IMAGE_SIDE, ColorAlphaType.OPAQUE)
        val bitmap = Bitmap()
        assertTrue(
            bitmap.installPixels(info, ByteArray(info.computeMinByteSize()), info.minRowBytes),
            "the bitmap did not take its pixels",
        )
        return bitmap.asComposeImageBitmap()
    }

    /** A clipboard on a connection with no surface, so nothing ever hands it an input serial to quote. */
    private fun withUnfocusedClipboard(block: (WaylandClipboard) -> Unit) {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }
        display.use { wayland ->
            // Never used: a drag marshals on the calling thread, and nothing here dispatches an event to the device.
            val clipboard = WaylandClipboard.bind(wayland, Dispatchers.Unconfined)
                .getOrElse { error -> fail("binding the clipboard failed: $error") }
            clipboard.use(block)
        }
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

        val DRAG_OUT_TEXT_TYPES =
            listOf("text/plain;charset=utf-8", "text/plain", "UTF8_STRING", "STRING", "TEXT")
        val DRAG_OUT_IMAGE_TYPES = listOf("image/png", "image/jpeg")
        const val IMAGE_SIDE = 8

        const val STARTED = "started"
        const val ENTERED = "entered"
        const val MOVED = "moved"
        const val EXITED = "exited"
        const val ENDED = "ended"
        const val DROP = "drop"
    }
}
