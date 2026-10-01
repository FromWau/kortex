package com.fromwau.kortex.wayland

import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferAction
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.getOrElse
import com.fromwau.kortex.compose.KortexDrag
import com.fromwau.kortex.compose.KortexDragSource
import com.fromwau.kortex.compose.KortexScene
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ImageInfo
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Covers both directions of a drag: what one arriving does to the drop targets in a scene's content, by driving
 * [KortexScene]'s own enter, motion, leave and drop through a real composition, where the scale a surface draws at
 * puts a drag on that scene, and what a drag out of content offers.
 *
 * No test here starts a drag on the desktop. Of the three that connect, two are refusals, one by a clipboard that
 * has been handed no input serial and one by a surface with no clipboard behind it, so each fails before a source
 * is made; the third stands a recorder in for the clipboard, so nothing reaches the compositor there either.
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
    fun `a drag offering a file manager's uri list and nothing else reaches content, with its files`() {
        val dropped = AtomicReference<KortexDragOffer?>(null)

        withDropTarget(onDropped = dropped::set) { scene ->
            // What Dolphin 26.08.1 offers, minus the three KDE types kortex reads nothing of, so the file
            // list is the only type here a drop target could be given anything under.
            val offer = uriDragOffer(URI_LIST_TYPES, DRAGGED_URI_LIST)
            val taken = scene.sendDragEnter(CENTRE, offer)
                .getOrElse { failure -> fail("content failed as the file drag arrived: $failure") }

            assertTrue(taken, "content refused a drag carrying files alone")
            scene.sendDrop(CENTRE, offer)
        }

        val carried = assertNotNull(dropped.get(), "a drag carrying files alone never reached content")
        assertEquals(
            URI_LIST_TYPES, carried.types,
            "content was told the file drag offered other types than the source advertised",
        )
        assertEquals(DRAGGED_URIS, carried.readUris(), "the dragged files did not reach content")
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

    /**
     * The two questions a settled action answers, which need different answers for the same value.
     *
     * A drag event carries exactly one action, so nothing settled has to read as something: a copy, which
     * takes nothing away. A drag that has *ended* under nothing is a drag that did not complete, which
     * Compose spells as null, and a copy there would tell content a drop happened that did not.
     */
    @Test
    fun `an action nothing settled reads as a copy while a drag is up and as no completion once it has ended`() {
        assertEquals(DragAndDropTransferAction.Copy, DndAction.Copy.asDragAction(BOTH), "a settled copy")
        assertEquals(DragAndDropTransferAction.Move, DndAction.Move.asDragAction(BOTH), "a settled move")
        assertEquals(DragAndDropTransferAction.Copy, DndAction.None.asDragAction(BOTH), "nothing settled yet")
        assertEquals(
            DragAndDropTransferAction.Copy, DndAction.Ask.asDragAction(BOTH),
            "an ask kortex never answers",
        )

        assertEquals(DragAndDropTransferAction.Copy, DndAction.Copy.asCompletedAction(), "a drop that copied")
        assertEquals(DragAndDropTransferAction.Move, DndAction.Move.asCompletedAction(), "a drop that moved")
        assertNull(DndAction.None.asCompletedAction(), "a drag that ended under no action at all")
        assertNull(DndAction.Ask.asCompletedAction(), "an ask kortex never answers")
    }

    /**
     * `wl_data_offer.set_actions` settles on what both sides offer, so an action the destination never named
     * is one the compositor owed an update on. Hyprland 0.56.2 leaves exactly that standing: it sends
     * `wl_data_offer.action(2)` before this side has answered anything and never sends another, whatever
     * `set_actions` follows it.
     *
     * The mechanism, read out of the compositor rather than inferred from the wire: it installs no handler
     * for `wl_data_offer.set_actions`, so a destination's mask is discarded, and picks from the source's
     * alone. This clamp is therefore the only thing between content and a delete it never agreed to, not a
     * guard on top of a compositor that would otherwise have honoured the mask.
     */
    @Test
    fun `a move the destination never offered reads as a copy, not as the move it was settled as`() {
        assertEquals(
            DragAndDropTransferAction.Copy, DndAction.Move.asDragAction(setOf(DndAction.Copy)),
            "content was told a move it never allowed, which is its cue to delete what it was handed",
        )
        assertEquals(
            DragAndDropTransferAction.Move, DndAction.Move.asDragAction(BOTH),
            "a move the destination did offer was not passed on",
        )
    }

    /**
     * That a drop carries whatever action it was given, which is how the destination side of a move reaches
     * content: what a drop does to what it carries is the source's to undo, and content here can only know
     * which by reading the event.
     *
     * kortex used to build every drag event as a copy whatever the compositor had settled, so a move arrived
     * indistinguishable from a copy and a drop target that treats the two differently could not.
     */
    @Test
    fun `a drop carries the action the desktop settled it under`() {

        val droppedAs = AtomicReference<DragAndDropTransferAction?>(null)

        withDropTarget(onDroppedAs = { action -> droppedAs.set(action) }) { scene ->

            val offer = dragOffer(TEXT_TYPES, DRAGGED_TEXT)
            scene.sendDragEnter(CENTRE, offer, DragAndDropTransferAction.Move)
            scene.sendDrop(CENTRE, offer, DragAndDropTransferAction.Move)
        }

        assertEquals(
            DragAndDropTransferAction.Move, droppedAs.get(),
            "a drop the desktop settled as a move reached content as something else",
        )
    }

    /**
     * That taking a drag and lying under it are two different answers.
     *
     * [KortexScene.sendDragEnter] answers the first: Compose asks every drop target in the composition whether it
     * wants what the drag carries, wherever in the composition that target is. What the compositor turns into a
     * cursor, and settles the drop under, is the second, and it changes as the drag crosses the content. Reading
     * the first as the second tells the user a drop lands anywhere on the surface, and tells the drag's source
     * its data was taken when the drop fell on nothing.
     */
    @Test
    fun `a drag content took is over a target only where that target is`() {
        withDropTarget(targetSide = SIDE / 2) { scene ->
            val offer = dragOffer(TEXT_TYPES, DRAGGED_TEXT)
            val taken = scene.sendDragEnter(CLEAR_OF_TARGET, offer)
                .getOrElse { failure -> fail("content failed as the drag arrived: $failure") }

            assertTrue(taken, "content holding a drop target refused a drag that arrived clear of it")
            assertFalse(scene.dragOverTarget, "a drag that arrived clear of the target was reported as over it")

            scene.sendDragMove(OVER_TARGET, offer)
            assertTrue(scene.dragOverTarget, "a drag moved onto the target was reported as over nothing")

            scene.sendDragMove(CLEAR_OF_TARGET, offer)
            assertFalse(scene.dragOverTarget, "a drag moved off the target was still reported as over it")
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
                clipboard.startDrag(dragOut(Clip.Text(DRAGGED_TEXT))),

                "a drag with no input serial must fail before a source is made or start_drag sent",
            )
            assertNull(
                clipboard.dragSource,
                "a drag with no input serial was carried to the compositor anyway",
            )
        }
    }

    /**
     * The other half of [`a drag with no input to quote fails as NoInputSerial`]: a surface with no clipboard
     * behind it at all refuses too, rather than reporting a drag it never sent.
     *
     * [bareSurface] builds exactly such a surface. While `onStartDrag` defaulted to `Ok(Unit)`, every drag test
     * written on that harness passed on silence: nothing reached the compositor, nothing reached the content that
     * asked, and the test could only see that neither had failed.
     */
    @Test
    fun `a drag out of a surface with no clipboard behind it is told it did not start`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use { wayland ->
            onBareSurface(wayland, REFUSAL_CONFIG) { surface, _ ->
                assertEquals(
                    Err(ClipboardError.NoClipboard),
                    surface.startDrag(copyDrag(KortexDragSource.Text(DRAGGED_TEXT))),

                    "a drag out of a surface with nowhere to send it must be refused, not reported as started",
                )
            }
        }
    }

    /**
     * That a drag asks the compositor from the call that started it, rather than from a thread behind it.
     *
     * The payload used to be encoded on `Dispatchers.Default` and the request posted back, so `start_drag` left
     * one or more loop passes after the button press whose implicit grab it names. Every compositor that checks
     * that grab refuses a request that arrives once the button is up, and an image is slow enough to encode for
     * that to be an ordinary outcome rather than a rare one.
     */
    @Test
    fun `a dragged image asks the compositor before the call that dragged it returns`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }
        val askedOn = AtomicReference<Thread?>(null)

        display.use { wayland ->
            val asking = { _: DragOut ->
                askedOn.set(Thread.currentThread())
                Ok(Unit)
            }

            onBareSurface(wayland, REFUSAL_CONFIG, onStartDrag = asking) { surface, _ ->
                assertEquals(
                    Ok(Unit),
                    surface.startDrag(copyDrag(KortexDragSource.Image(opaqueImage()))),

                    "the surface refused a drag the clipboard behind it took",
                )
                assertSame(
                    Thread.currentThread(), askedOn.get(),
                    "an image drag asked the compositor from somewhere other than the call that dragged it",
                )
            }
        }
    }

    /** [dragged] as a drag that offers a copy and reports nothing, which every leg here starts. */
    private fun copyDrag(dragged: KortexDragSource): KortexDrag =
        KortexDrag(dragged, setOf(DragAndDropTransferAction.Copy)) {}

    /** [clip] as a drag out of nowhere, which is all the clipboard's own refusals need. */
    private fun dragOut(clip: Clip): DragOut =
        DragOut(clip, origin = NULL, actions = setOf(DndAction.Copy)) {}

    /** What a drag of [dragged] offers. */
    private fun offeredTypesOf(dragged: KortexDragSource): List<String> =
        dragged.asClip()
            .getOrElse { error -> fail("a drag of $dragged built no clip: $error") }
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

    /** The same, for a drag out of a file manager: [uriList] is the `text/uri-list` bytes it would send. */
    private fun uriDragOffer(advertised: List<String>, uriList: String): KortexDragOffer =
        Arena.ofShared().use { arena ->
            val offer = DataOffer(arena)
            advertised.forEach { offer.onOffer(NULL, NULL, arena.allocateFrom(it)) }
            KortexDragOffer(offer.offeredTypes, uris = Ok(decodeUriList(uriList.encodeToByteArray())))
        }

    /**
     * Runs [block] on a scene whose content is one drop target of [targetSide] a side, in its top-left corner,
     * which records every event it is sent in [seen].
     */
    private fun withDropTarget(
        seen: MutableList<String> = CopyOnWriteArrayList(),
        onDropped: (KortexDragOffer) -> Unit = {},
        onDroppedAs: (DragAndDropTransferAction?) -> Unit = {},
        targetSide: Int = SIDE,

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
                onDroppedAs(event.action)
                onDropped(event.nativeEvent as KortexDragOffer)
                return true
            }
        }
        onScene(IntSize(SIDE, SIDE)) { scene, _, tick ->
            scene.setContent {
                Box(Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .size(targetSide.dp)
                            .dragAndDropTarget(
                                shouldStartDragAndDrop = { start ->
                                    (start.nativeEvent as? KortexDragOffer)?.types.orEmpty().isNotEmpty()
                                },
                                target = target,
                            ),
                    )
                }
            }
            // A drop is routed by where it is, which needs the target laid out and its bounds known.

            tick(0L)
            block(scene)
        }
    }

    private companion object {
        val NULL: MemorySegment = MemorySegment.NULL
        const val SIDE = 64
        val CENTRE = Offset(SIDE / 2f, SIDE / 2f)

        // Against a target half the scene's side, anchored in its top-left corner: one well inside it, one well
        // outside and still on the surface, since a drag off the surface is a leave rather than a miss.
        val OVER_TARGET = Offset(SIDE / 4f, SIDE / 4f)
        val CLEAR_OF_TARGET = Offset(SIDE * 3f / 4f, SIDE * 3f / 4f)

        val TEXT_TYPES = listOf("text/plain;charset=utf-8", "text/plain")
        const val DRAGGED_TEXT = "dragged"

        /** A destination that allows either, against which the mapping alone is read. */
        val BOTH = setOf(DndAction.Copy, DndAction.Move)

        val URI_LIST_TYPES = listOf("text/uri-list")
        const val DRAGGED_URI_LIST = "file:///tmp/test-file.txt\r\n"
        val DRAGGED_URIS = Ok(listOf("file:///tmp/test-file.txt"))

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

        // The drag-out legs' own surface: an OSD, since the smallest preset that maps is enough for a drag that
        // never leaves the client.
        const val REFUSAL_NAMESPACE = "kortex-drag-refusal"
        const val REFUSAL_OSD_DP = 64
        val REFUSAL_CONFIG =
            SurfaceConfig.osd(REFUSAL_OSD_DP.dp, REFUSAL_OSD_DP.dp).copy(namespace = REFUSAL_NAMESPACE)
    }
}
