package com.fromwau.kortex.wayland

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draganddrop.DragAndDropTransferAction
import androidx.compose.ui.geometry.Offset
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.flatMap
import com.fromwau.kern.result.getOrElse
import com.fromwau.kern.result.map
import com.fromwau.kortex.compose.KortexScene
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor

/**
 * The clipboard's `wl_data_device`: follows which offer is the selection, gives back every other offer the
 * compositor introduces, and carries a drag over one of this client's surfaces to the content drawn there.
 */
@OptIn(ExperimentalComposeUiApi::class)
internal class DataDevice private constructor(
    private val proxy: MemorySegment,
    private val display: WaylandDisplay,
    // Where a drop's transfers are handed back once they have drained off this thread.
    private val loop: CoroutineDispatcher,
) {
    private val arena: Arena = Arena.ofShared()

    // Introduced by data_offer and not yet named by a selection, by proxy address.
    private val introduced = mutableMapOf<Long, DataOffer>()

    /** The offer the compositor last named as the selection, or null when it named none. */
    var selection: DataOffer? = null
        private set

    /** Whether [selection] is offered as text; answered at once, on any thread. */
    @Volatile
    var selectionHasText: Boolean = false
        private set

    /** Where a drag over each `wl_surface` this client owns goes; the loop thread alone calls it. */
    var dragDestinations: (surface: Long) -> DragDestination? = { null }

    // The drag over one of this client's surfaces, from the compositor's enter until its leave or its drop.
    private var drag: Drag? = null

    // The offer of a drag this client refused, kept until the compositor's leave gives it somewhere to be freed.
    // Nothing answers a drop of it: a finish after an accept of nothing is a protocol error.
    private var refused: DataOffer? = null

    /** The source of the drag this client is carrying out, and null until one starts and once it has ended. */
    var dragged: DataSource? = null
        private set

    fun onDataOffer(data: MemorySegment, device: MemorySegment, offer: MemorySegment) {
        introduced[offer.address()] = DataOffer().also { it.install(offer) }
    }

    fun onEnter(
        data: MemorySegment,
        device: MemorySegment,
        serial: Int,
        surface: MemorySegment,
        x: Int,
        y: Int,
        offer: MemorySegment,
    ) {
        // The compositor leaves one drag before entering with the next, but a drop's transfers can still be
        // draining when that next one arrives, and its offer and its hover would be left behind.
        drag?.let(::leaveDrag)
        // A refusal whose leave never came: the enter replacing it makes its offer stale either way.
        endRefused()
        // NULL is in no map, so a drag the compositor names no offer for brings nothing to take.
        val brought = introduced.remove(offer.address()) ?: return
        val destination = dragDestinations(surface.address())
        val types = brought.offeredTypes
        if (destination == null || types.isEmpty()) return decline(brought, serial)
        val arrival = Drag(brought, destination, types, destination.scenePosition(x, y), serial)
        // Content that has already failed takes nothing more, this drag included.
        val taken = destination.scene
            .sendDragEnter(arrival.position, arrival.announced, arrival.action)
            .getOrElse { false }

        if (!taken) return decline(brought, serial)
        drag = arrival
        answerDrag(arrival)
    }

    fun onLeave(data: MemorySegment, device: MemorySegment) {
        // A drop is followed by a leave of its own, and ends with its transfers rather than here.
        drag?.takeUnless { it.dropping }?.let(::leaveDrag)
        endRefused()
    }

    fun onMotion(data: MemorySegment, device: MemorySegment, time: Int, x: Int, y: Int) {
        val moving = drag?.takeUnless { it.dropping } ?: return
        moving.position = moving.destination.scenePosition(x, y)
        moving.destination.scene.sendDragMove(moving.position, moving.announced, moving.action)
        answerDrag(moving)
    }

    /**
     * Tells the drag's source whether a drop where [moving] is now would be taken, which is what the compositor
     * turns into the cursor the user sees and what settles the action the drop is finished under.
     *
     * Sent again on every motion, as `wl_data_offer.set_actions` says to: content takes a drag for the whole
     * session, but only the part of it under the drag would take the drop, and that changes as the drag crosses
     * the surface. The serial stays the enter's, which is the only one a drag is given.
     */
    private fun answerDrag(moving: Drag) {
        val wanted = moving.destination.scene.dragOverTarget
        moving.offer.accept(moving.serial, moving.types.first().takeIf { wanted })
        val offered = if (wanted) TAKEABLE else emptySet()

        val preferred = if (wanted) DndAction.Copy else DndAction.None
        moving.offer.setActions(DndAction.mask(offered), preferred.wire)
        display.flush()
    }

    fun onDrop(data: MemorySegment, device: MemorySegment) {
        val dropped = drag?.takeUnless { it.dropping } ?: return
        dropped.dropping = true
        // Opened here because only this thread may ask for them, and drained off it because a source writing
        // slowly would hold every surface up for as long as it took.
        val textPipe = dropped.textType?.let(dropped.offer::openTransfer)
        val imagePipe = dropped.imageType?.let(dropped.offer::openTransfer)
        val uriListPipe = dropped.uriListType?.let(dropped.offer::openTransfer)
        display.flush()
        val io = Dispatchers.IO.asExecutor()
        io.execute {
            // What content is told if the try below throws before it reassigns carried: the types stay honest, but
            // a technical failure must not read as though the drag offered nothing of that kind.
            var carried = KortexDragOffer(
                carried = dropped.types,
                text = Err(ClipboardError.PipeFailed),
                image = Err(ClipboardError.PipeFailed),
                uris = Err(ClipboardError.PipeFailed),
            )
            try {
                // A task each: a source writing the image first blocks on its full pipe until something reads it, so
                // a text drained ahead of it would spend its whole bound waiting for bytes that cannot come yet.
                // Submitted inside the try so a rejection still reaches the finally below, rather than stranding
                // the drag with dropping left true.
                val image = CompletableFuture.supplyAsync(
                    { drain(imagePipe, MAX_IMAGE_BYTES, ClipboardError.NoImage).flatMap(::decodeImage) },
                    io,
                )
                val uris = CompletableFuture.supplyAsync(
                    { drain(uriListPipe, MAX_TEXT_BYTES, ClipboardError.NoUris).map(::decodeUriList) },
                    io,
                )
                carried = KortexDragOffer(
                    carried = dropped.types,
                    text = drain(textPipe, MAX_TEXT_BYTES, ClipboardError.NoText).map(ByteArray::decodeToString),
                    image = joinDrain(image),
                    uris = joinDrain(uris),
                )
            } finally {
                // However a drain ended: left unposted, the drag would hover for good and its offer never be freed.
                loop.asExecutor().execute { completeDrop(dropped, carried) }
            }
        }
    }

    fun onSelection(data: MemorySegment, device: MemorySegment, offer: MemorySegment) {
        // NULL is in no map, so a cleared selection names nothing.
        val named = introduced.remove(offer.address())
        selection?.destroy()
        // Anything else introduced so far will never be named now.
        introduced.values.forEach(DataOffer::destroy)
        introduced.clear()
        selection = named
        selectionHasText = named?.preferredText != null
    }

    /**
     * `wl_data_device.set_selection`: makes [source] the selection, or clears it when [source] is null. [serial] is
     * the input event the compositor checks the request against.
     */
    fun setSelection(source: DataSource?, serial: Int) {
        LibWayland.marshal(
            proxy, WL_DATA_DEVICE_SET_SELECTION,
            args = listOf(WlArg.Ptr(source?.proxy ?: MemorySegment.NULL), WlArg.Num(serial)),
        )
    }

    /**
     * `wl_data_device.start_drag`: drags what [source] offers out of [origin], one of this client's own surfaces.
     * [serial] is the input event the compositor checks the request against.
     *
     * No icon surface is sent, so the compositor shows a drag cursor of its own rather than an image of what is
     * being dragged.
     *
     * [source] is taken over here and given back once the compositor ends the drag it carries.
     */
    fun startDrag(source: DataSource, origin: MemorySegment, serial: Int) {
        // Before the request, not after: the compositor ends a drag as its source goes, this new one included.
        dragged?.destroy()
        dragged = source
        // Posted, not run in the event that tells it: the destroy frees the very stub that event runs in.
        source.onDragEnded = { loop.asExecutor().execute { endDragged(source) } }
        LibWayland.marshal(
            proxy, WL_DATA_DEVICE_START_DRAG,
            args = listOf(
                WlArg.Ptr(source.proxy),
                WlArg.Ptr(origin),
                WlArg.Ptr(MemorySegment.NULL),
                WlArg.Num(serial),
            ),
        )
    }

    /** Gives the device back, then every offer it introduced; nothing here may be used afterwards. */
    fun release() {
        LibWayland.marshal(proxy, WL_DATA_DEVICE_RELEASE)
        LibWayland.proxyDestroy(proxy)
        // After the destroy, never before: a data_offer or selection still queued would otherwise reach a freed stub.
        arena.close()
        selection?.destroy()
        selection = null
        dragged?.destroy()
        dragged = null
        drag?.let(::endDrag)
        endRefused()
        introduced.values.forEach(DataOffer::destroy)
        introduced.clear()
    }

    // No destroy here: the compositor holds this offer as the drag's live one until it leaves, and giving a live
    // offer back tells the drag's source the session is over, other clients' drags as readily as this client's.
    private fun decline(offer: DataOffer, serial: Int) {
        offer.accept(serial, type = null)
        offer.setActions(DndAction.None.wire, DndAction.None.wire)
        display.flush()
        refused = offer
    }

    // Back on the loop thread, with everything [dropped] carried in hand.
    private fun completeDrop(dropped: Drag, carried: KortexDragOffer) {
        // The device can have been given back, or another drag begun, while the transfers drained.
        if (drag !== dropped) return
        val taken = dropped.destination.scene
            .sendDrop(dropped.position, carried, dropped.action)
            .getOrElse { false }

        // A finish says the drop succeeded, and a source dragging a move may delete what it sent as soon as it
        // hears that, so only content actually taking what arrived earns one. A drop over no target of its own,
        // or into content that has failed, is given back unfinished instead.
        if (taken) dropped.offer.finish()

        display.flush()
        endDrag(dropped)
    }

    // Back on the loop thread, once the compositor has ended what [source] carried. Another drag, or the release,
    // can have given it back meanwhile, and a source given back twice would reach a freed proxy.
    private fun endDragged(source: DataSource) {
        if (dragged !== source) return
        source.destroy()
        dragged = null
    }

    // Tells content the drag is over with nothing dropped, and gives its offer back.
    private fun leaveDrag(leaving: Drag) {
        leaving.destination.scene.sendDragLeave(leaving.position, leaving.announced, leaving.action)

        endDrag(leaving)
    }

    // The offer alone, for a shell whose scenes have already closed. Never from inside one of the offer's own
    // events, whose stubs its destroy frees.
    private fun endDrag(ending: Drag) {
        drag = null
        ending.offer.destroy()
    }

    // A refused offer, once the compositor has moved its drag on and freeing it can no longer end that drag.
    private fun endRefused() {
        refused?.destroy()
        refused = null
    }

    private fun install() {
        val listener = arena.allocate(ADDRESS.byteSize() * EVENT_COUNT)
        listener.setAtIndex(
            ADDRESS, DATA_OFFER,
            LibWayland.upcall(arena, this, "onDataOffer", DATA_OFFER_DESCRIPTOR),
        )
        listener.setAtIndex(ADDRESS, ENTER, LibWayland.upcall(arena, this, "onEnter", ENTER_DESCRIPTOR))
        listener.setAtIndex(ADDRESS, LEAVE, LibWayland.upcall(arena, this, "onLeave", LEAVE_DESCRIPTOR))
        listener.setAtIndex(ADDRESS, MOTION, LibWayland.upcall(arena, this, "onMotion", MOTION_DESCRIPTOR))
        listener.setAtIndex(ADDRESS, DROP, LibWayland.upcall(arena, this, "onDrop", DROP_DESCRIPTOR))
        listener.setAtIndex(
            ADDRESS, SELECTION,
            LibWayland.upcall(arena, this, "onSelection", SELECTION_DESCRIPTOR),
        )
        check(LibWayland.proxyAddListener(proxy, listener, MemorySegment.NULL) == 0) {
            "wl_proxy_add_listener rejected the data device listener"
        }
    }

    /** One drag over one of this client's surfaces, from the compositor's enter until its leave or its drop. */
    private class Drag(
        val offer: DataOffer,
        val destination: DragDestination,
        /** Every type the drag is offered under that kortex can carry, in the order a read prefers them. */
        val types: List<Mime>,
        /** Where content last saw the drag: `wl_data_device.drop` carries no position of its own. */
        var position: Offset,
        /** The enter's own serial, which every later answer to this drag quotes: it is given no other. */
        val serial: Int,
    ) {
        /** What content reads the drag through until it is dropped, which is its types and nothing else yet. */
        val announced = KortexDragOffer(types)

        /** The one text type a drop reads, out of [types]; null where the drag offers no text. */
        val textType: TextMime? = types.filterIsInstance<TextMime>().firstOrNull()

        /** The one image type a drop reads, out of [types]; null where the drag offers no image. */
        val imageType: ImageMime? = types.filterIsInstance<ImageMime>().firstOrNull()

        /** The one list-of-files type a drop reads, out of [types]; null where the drag offers no file list. */
        val uriListType: UriListMime? = types.filterIsInstance<UriListMime>().firstOrNull()

        /** Set as the drop's transfers open, since the compositor follows a drop with a leave of its own. */
        var dropping = false

        /**
         * What a drop would do to what this carries, as content reads it off the drag event.
         *
         * The compositor settles it from both sides' actions and the modifiers the user holds, so it changes
         * while the drag is up. Nothing settled yet reads as a copy, which is what a drag that carries no
         * action does when it lands, and so does anything outside [TAKEABLE].
         */
        val action: DragAndDropTransferAction get() = offer.settledAction.asDragAction(TAKEABLE)
    }

    companion object {
        /** Takes [seat]'s data device from [manager], listening before the compositor can send it anything. */
        fun create(display: WaylandDisplay, loop: CoroutineDispatcher, manager: MemorySegment, seat: Seat): DataDevice {
            val proxy = LibWayland.marshal(
                manager, WL_DATA_DEVICE_MANAGER_GET_DATA_DEVICE, LibWayland.dataDeviceInterface,
                LibWayland.proxyGetVersion(manager), listOf(WlArg.Ptr(MemorySegment.NULL), WlArg.Ptr(seat.proxy)),
            )
            return DataDevice(proxy, display, loop).also { it.install() }
        }

        /**
         * Reads [transfer] to its end, keeping at most [maxBytes], and closes it.
         *
         * @return what arrived, or [absent] where the drag offered nothing of that kind to open a transfer on.
         */
        private fun drain(
            transfer: Result<Int, ClipboardError>?,
            maxBytes: Long,
            absent: ClipboardError,
        ): Result<ByteArray, ClipboardError> {
            val readFd = (transfer ?: return Err(absent)).getOrElse { return Err(it) }
            return try {
                readPipeToEnd(readFd, TRANSFER_TIMEOUT_MILLIS, maxBytes)
            } finally {
                LibC.close(readFd)
            }
        }

        /**
         * What [pending] drained, or [ClipboardError.ReadTimedOut] where the pool has not finished it in time.
         *
         * [readPipeToEnd]'s own deadline starts only once the pool schedules the task, so a bound of its own
         * here is what keeps scheduling delay alone from hanging the drop.
         */
        private fun <T> joinDrain(
            pending: CompletableFuture<Result<T, ClipboardError>>,
        ): Result<T, ClipboardError> = try {
            pending.get(DRAIN_JOIN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            Err(ClipboardError.ReadTimedOut)
        }

        // Slack over a drain's own TRANSFER_TIMEOUT_MILLIS, for the wait to be scheduled on Dispatchers.IO that
        // deadline does not cover.
        private const val DRAIN_JOIN_SLACK_MILLIS = 1_000L
        private const val DRAIN_JOIN_TIMEOUT_MILLIS = TRANSFER_TIMEOUT_MILLIS + DRAIN_JOIN_SLACK_MILLIS

        /**
         * What a drag arriving here may do, which is a copy and nothing else, because a destination cannot
         * steer the choice on Hyprland 0.56.2 and a drag that lands as a move deletes what it came from.
         *
         * Read off the wire: the compositor answers a drag's enter with `wl_data_offer.action(2)`, a move,
         * before this side has said anything, and re-sends nothing after four `set_actions(3, 1)` naming both
         * and preferring copy. So naming move here means every drag in is a move, whatever the user held.
         * `ask` is left out for a second reason: answering it means putting the compositor's own menu to the
         * user and sending one last `set_actions` for what they picked, and a destination that names it
         * without answering it stalls the drop.
         */
        private val TAKEABLE = setOf(DndAction.Copy)

        private const val WL_DATA_DEVICE_MANAGER_GET_DATA_DEVICE = 1

        private const val WL_DATA_DEVICE_START_DRAG = 0
        private const val WL_DATA_DEVICE_SET_SELECTION = 1
        private const val WL_DATA_DEVICE_RELEASE = 2

        // wl_data_device v4 declares exactly these six events; every slot must be filled, because
        // libwayland indexes the struct and calls straight through it.
        private const val EVENT_COUNT = 6L
        private const val DATA_OFFER = 0L
        private const val ENTER = 1L
        private const val LEAVE = 2L
        private const val MOTION = 3L
        private const val DROP = 4L
        private const val SELECTION = 5L

        private val DATA_OFFER_DESCRIPTOR = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS)
        private val ENTER_DESCRIPTOR =
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS)
        private val LEAVE_DESCRIPTOR = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS)
        private val MOTION_DESCRIPTOR = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT)
        private val DROP_DESCRIPTOR = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS)
        private val SELECTION_DESCRIPTOR = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS)
    }
}

/**
 * A `wl_data_offer`: the types the selection, or a drag, is offered under.
 *
 * @param arena holds its listener's stubs; [destroy] closes it.
 */
internal class DataOffer(private val arena: Arena = Arena.ofShared()) {
    private var proxy: MemorySegment = MemorySegment.NULL
    private val offered = mutableSetOf<Mime>()

    // Whether the last accept named a type, which wl_data_offer.finish is an error after it did not.
    private var accepting = false

    // What the compositor last settled this drag on, which stays none against a source older than
    // wl_data_device_manager v3, since such a source names no action for one to be matched against.
    private var settled = DndAction.None

    /** The type a text paste asks for: the first [TextMime] this offer lists, or null when it lists none. */
    val preferredText: TextMime? get() = TextMime.entries.firstOrNull { it in offered }

    /** The type an image paste asks for: the first [ImageMime] this offer lists, or null when it lists none. */
    val preferredImage: ImageMime? get() = ImageMime.entries.firstOrNull { it in offered }

    /** Every type this offer lists that kortex can carry, in the order a read prefers them. */
    val offeredTypes: List<Mime> get() = Mime.all.filter { it in offered }

    fun onOffer(data: MemorySegment, offer: MemorySegment, mimeType: MemorySegment) {
        // The char* arrives with zero length because C says nothing about its extent.
        Mime.fromWireNameOrNull(mimeType.reinterpret(Long.MAX_VALUE).getString(0))?.let(offered::add)
    }

    fun onSourceActions(data: MemorySegment, offer: MemorySegment, sourceActions: Int) = Unit

    fun onAction(data: MemorySegment, offer: MemorySegment, dndAction: Int) {
        settled = DndAction.fromWire(dndAction)
    }

    /** `wl_data_offer.receive`: asks the offer's source to write itself into [fd], as [type]. */
    fun receive(type: Mime, fd: Int) {
        // wl_proxy_marshal copies the string into the message it builds, so it is only borrowed for the call.
        Arena.ofConfined().use { request ->
            LibWayland.marshal(
                proxy, WL_DATA_OFFER_RECEIVE,
                args = listOf(WlArg.Ptr(request.allocateFrom(type.wireName)), WlArg.Num(fd)),
            )
        }
    }

    /**
     * Opens a pipe this offer's source writes [type] into.
     *
     * @return the read end, which the caller closes, or [ClipboardError.PipeFailed] where no pipe could be made.
     */
    fun openTransfer(type: Mime): Result<Int, ClipboardError> {
        val pipe = LibC.pipe().getOrElse { return Err(ClipboardError.PipeFailed) }
        receive(type, pipe.writeFd)
        // The connection's flush sends libwayland's own duplicate; this end left open would hold the read open too.
        LibC.close(pipe.writeFd)
        return Ok(pipe.readFd)
    }

    /**
     * `wl_data_offer.accept`: tells the drag's source that this client would take [type] where the drag is now, or
     * nothing where [type] is null. Answered again as the drag moves; the last answer before the drop is the one
     * that settles it, and a source left with null there is cancelled.
     */
    fun accept(serial: Int, type: Mime?) {
        accepting = type != null
        Arena.ofConfined().use { request ->

            val name = type?.let { request.allocateFrom(it.wireName) } ?: MemorySegment.NULL
            LibWayland.marshal(proxy, WL_DATA_OFFER_ACCEPT, args = listOf(WlArg.Num(serial), WlArg.Ptr(name)))
        }
    }

    /** What the compositor last matched this offer's two sides to; the drop happens under it. */
    val settledAction: DndAction get() = settled

    /** `wl_data_offer.set_actions`: the drag actions this client supports, and the one it would rather have. */
    fun setActions(actions: Int, preferred: Int) {
        LibWayland.marshal(proxy, WL_DATA_OFFER_SET_ACTIONS,
            args = listOf(WlArg.Num(actions), WlArg.Num(preferred)),
        )
    }

    /**
     * `wl_data_offer.finish`: tells the drag's source its drop succeeded.
     *
     * Silent where the protocol names saying so an error: after an accept of no type, and before the compositor
     * has settled an action. An ask is settled only once the destination has answered it with a set_actions of
     * its own, which kortex never sends.
     */
    fun finish() {
        if (!accepting) return
        when (settled) {
            DndAction.Copy, DndAction.Move -> LibWayland.marshal(proxy, WL_DATA_OFFER_FINISH)
            DndAction.None, DndAction.Ask -> Unit
        }
    }

    fun install(offer: MemorySegment) {
        proxy = offer
        val listener = arena.allocate(ADDRESS.byteSize() * EVENT_COUNT)
        listener.setAtIndex(ADDRESS, OFFER, LibWayland.upcall(arena, this, "onOffer", OFFER_DESCRIPTOR))
        listener.setAtIndex(
            ADDRESS, SOURCE_ACTIONS,
            LibWayland.upcall(arena, this, "onSourceActions", SOURCE_ACTIONS_DESCRIPTOR),
        )
        listener.setAtIndex(ADDRESS, ACTION, LibWayland.upcall(arena, this, "onAction", ACTION_DESCRIPTOR))
        check(LibWayland.proxyAddListener(offer, listener, MemorySegment.NULL) == 0) {
            "wl_proxy_add_listener rejected the data offer listener"
        }
    }

    /** Gives the offer back and frees its stubs; never from inside one of its own events, whose stub this frees. */
    fun destroy() {
        LibWayland.marshal(proxy, WL_DATA_OFFER_DESTROY)
        LibWayland.proxyDestroy(proxy)
        // After the destroy, never before: an offer event still queued would otherwise reach a freed stub.
        arena.close()
    }

    private companion object {
        const val WL_DATA_OFFER_ACCEPT = 0
        const val WL_DATA_OFFER_RECEIVE = 1
        const val WL_DATA_OFFER_DESTROY = 2
        const val WL_DATA_OFFER_FINISH = 3
        const val WL_DATA_OFFER_SET_ACTIONS = 4

        // wl_data_offer v4 declares exactly these three events; every slot must be filled, because
        // libwayland indexes the struct and calls straight through it.
        const val EVENT_COUNT = 3L
        const val OFFER = 0L
        const val SOURCE_ACTIONS = 1L
        const val ACTION = 2L

        val OFFER_DESCRIPTOR: FunctionDescriptor = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS)
        val SOURCE_ACTIONS_DESCRIPTOR: FunctionDescriptor = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT)
        val ACTION_DESCRIPTOR: FunctionDescriptor = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT)
    }
}

/**
 * One copy's `wl_data_source`: offers [clip] under every type [clip] holds, and sends what it holds for each.
 *
 * @param arena holds its listener's stubs; [destroy] closes it.
 */
internal class DataSource(
    private val clip: Clip,
    private val arena: Arena = Arena.ofShared(),
    /** What a drag of this may do, and empty for a selection, which negotiates no action at all. */
    private val actions: Set<DndAction> = emptySet(),
) {

    // Set on the loop thread; ownedText reads it from any.
    @Volatile
    private var cancelled = false

    /** Called on the loop thread once the compositor ends the drag this carries, for its owner to give it back. */
    var onDragEnded: () -> Unit = {}

    /**
     * Called on the loop thread with what the drag this carries settled on, and [DndAction.None] where it ended
     * without a drop. Content reads it to undo what a move took away, which nothing else reports.
     */
    var onDragCompleted: (DndAction) -> Unit = {}

    // What the compositor last matched the two sides' actions to, which the drop is finished under.
    private var settled = DndAction.None

    // What it holds until the compositor cancels it, as it does once another selection or a clear replaces it.
    private val ownedClip: Clip? get() = clip.takeUnless { cancelled }

    /** Its text while it holds one and nothing has replaced it as the selection. */
    val ownedText: String? get() = (ownedClip as? Clip.Text)?.text

    /** Its image while it holds one and nothing has replaced it as the selection. */
    val ownedImage: Clip.Image? get() = ownedClip as? Clip.Image

    var proxy: MemorySegment = MemorySegment.NULL
        private set

    fun onTarget(data: MemorySegment, source: MemorySegment, mimeType: MemorySegment) = Unit

    fun onSend(data: MemorySegment, source: MemorySegment, mimeType: MemorySegment, fd: Int) {
        val asked = Mime.fromWireNameOrNull(mimeType.reinterpret(Long.MAX_VALUE).getString(0))
        // A type this copy never offered leaves the receiver an empty transfer, never another type's bytes.
        val type = asked?.takeIf { it in clip.offeredTypes } ?: return LibC.close(fd)
        // Off the loop thread: a drag leaves its encoding to here, and a write into a full pipe waits for the
        // receiver to drain it; either one on the loop stalls every surface for as long as it runs.
        Dispatchers.IO.asExecutor().execute {
            val bytes = clip.bytesFor(type).getOrElse { return@execute LibC.close(fd) }
            writePipeAndClose(fd, bytes, TRANSFER_TIMEOUT_MILLIS)
        }
    }

    // Replaced as the selection, or the drag this carried is over. Destroyed by whoever owns it, never by this
    // event, which runs in one of the stubs that would free.
    fun onCancelled(data: MemorySegment, source: MemorySegment) {
        cancelled = true
        // No drop happened, whatever was settled on the way: a cancel is how a drag released over nothing ends.
        onDragCompleted(DndAction.None)
        onDragEnded()
    }

    fun onDndDropPerformed(data: MemorySegment, source: MemorySegment) = Unit

    /**
     * What the drop is taken to have been where the compositor never said, which on Hyprland 0.56.2 is always:
     * it sends no `wl_data_source.action` at all, and `dnd_finished` says only that the drop happened.
     *
     * Copy wherever content offered it. Reporting a move that was not one has content delete what nobody took,
     * and reporting a copy that was really a move leaves the same thing in two places, which is the one of the
     * two mistakes that can be undone.
     */
    private val assumedAction: DndAction
        get() = if (DndAction.Copy in actions) DndAction.Copy else DndAction.Move

    // The destination is done with what this offered, so nothing will be asked of it again.
    fun onDndFinished(data: MemorySegment, source: MemorySegment) {
        // Never None here: a finished drop did happen, and null would tell content its gesture never completed.
        onDragCompleted(settled.takeUnless { it == DndAction.None } ?: assumedAction)
        onDragEnded()
    }

    // The compositor's own match of what this source offers against what the destination will take. It arrives
    // again as either side changes, and the last one before dnd_finished is what the drop was.
    fun onAction(data: MemorySegment, source: MemorySegment, dndAction: Int) {
        settled = DndAction.fromWire(dndAction)
    }

    /** Gives the source back, which clears the selection if it still is one, and frees its stubs. */
    fun destroy() {
        LibWayland.marshal(proxy, WL_DATA_SOURCE_DESTROY)
        LibWayland.proxyDestroy(proxy)
        // After the destroy, never before: a send still queued would otherwise reach a freed stub.
        arena.close()
    }

    private fun install(source: MemorySegment) {
        proxy = source
        val listener = arena.allocate(ADDRESS.byteSize() * EVENT_COUNT)
        listener.setAtIndex(ADDRESS, TARGET, LibWayland.upcall(arena, this, "onTarget", TARGET_DESCRIPTOR))
        listener.setAtIndex(ADDRESS, SEND, LibWayland.upcall(arena, this, "onSend", SEND_DESCRIPTOR))
        listener.setAtIndex(ADDRESS, CANCELLED, LibWayland.upcall(arena, this, "onCancelled", CANCELLED_DESCRIPTOR))
        listener.setAtIndex(
            ADDRESS, DND_DROP_PERFORMED,
            LibWayland.upcall(arena, this, "onDndDropPerformed", DND_DROP_PERFORMED_DESCRIPTOR),
        )
        listener.setAtIndex(
            ADDRESS, DND_FINISHED,
            LibWayland.upcall(arena, this, "onDndFinished", DND_FINISHED_DESCRIPTOR),
        )
        listener.setAtIndex(ADDRESS, ACTION, LibWayland.upcall(arena, this, "onAction", ACTION_DESCRIPTOR))
        check(LibWayland.proxyAddListener(source, listener, MemorySegment.NULL) == 0) {
            "wl_proxy_add_listener rejected the data source listener"
        }
    }

    /** `wl_data_source.set_actions`: what content will let a drop of this do. */
    private fun offerActions() {
        LibWayland.marshal(proxy, WL_DATA_SOURCE_SET_ACTIONS,
            args = listOf(WlArg.Num(DndAction.mask(actions))),
        )
    }

    companion object {
        /**
         * Creates a source for [clip] that a drag offers, declaring [actions] as what a drop of it may do.
         *
         * Declared rather than left empty: a source that names no action at all is dragged as a move, and the
         * protocol allows `set_actions` only before `start_drag` and only once.
         */
        fun createForDrag(manager: MemorySegment, clip: Clip, actions: Set<DndAction>): DataSource =
            create(manager, clip, actions).also { it.offerActions() }

        /** Creates a source for [clip] and offers it under every type [clip] holds. */
        fun create(manager: MemorySegment, clip: Clip, actions: Set<DndAction> = emptySet()): DataSource {

            val proxy = LibWayland.marshal(
                manager, WL_DATA_DEVICE_MANAGER_CREATE_DATA_SOURCE, LibWayland.dataSourceInterface,
                LibWayland.proxyGetVersion(manager), listOf(WlArg.Ptr(MemorySegment.NULL)),
            )
            val source = DataSource(clip, actions = actions).also { it.install(proxy) }

            // wl_proxy_marshal copies each string into the message it builds, so they are only borrowed for the calls.
            Arena.ofConfined().use { request ->
                clip.offeredTypes.forEach { type ->
                    LibWayland.marshal(
                        proxy, WL_DATA_SOURCE_OFFER,
                        args = listOf(WlArg.Ptr(request.allocateFrom(type.wireName))),
                    )
                }
            }
            return source
        }

        private const val WL_DATA_DEVICE_MANAGER_CREATE_DATA_SOURCE = 0
        private const val WL_DATA_SOURCE_OFFER = 0
        private const val WL_DATA_SOURCE_DESTROY = 1
        private const val WL_DATA_SOURCE_SET_ACTIONS = 2

        // wl_data_source v4 declares exactly these six events; every slot must be filled, because
        // libwayland indexes the struct and calls straight through it.
        private const val EVENT_COUNT = 6L
        private const val TARGET = 0L
        private const val SEND = 1L
        private const val CANCELLED = 2L
        private const val DND_DROP_PERFORMED = 3L
        private const val DND_FINISHED = 4L
        private const val ACTION = 5L

        private val TARGET_DESCRIPTOR = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS)
        private val SEND_DESCRIPTOR = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, JAVA_INT)
        private val CANCELLED_DESCRIPTOR = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS)
        private val DND_DROP_PERFORMED_DESCRIPTOR = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS)
        private val DND_FINISHED_DESCRIPTOR = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS)
        private val ACTION_DESCRIPTOR = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT)
    }
}

/** A `wl_data_device_manager.dnd_action`: how a drag is carried out. Copy is the only one kortex asks for. */
internal enum class DndAction(val wire: Int) {
    None(0),
    Copy(1),
    Move(2),
    Ask(4),
    ;

    companion object {
        /** The action [wire] names, and [None] for a value the protocol gives no meaning to. */
        fun fromWire(wire: Int): DndAction = DndAction.entries.firstOrNull { it.wire == wire } ?: None

        /** The bits [actions] set together, which is how both `set_actions` requests carry a set of them. */
        fun mask(actions: Set<DndAction>): Int = actions.fold(0) { bits, action -> bits or action.wire }
    }
}

/**
 * What the protocol calls [this], for a drag this client carries out. Anything but a move is carried as a copy:
 * `:compose` refuses a drag before this whose actions it can express none of, so the only one that can arrive
 * here unnamed is one a newer Compose added.
 */
@OptIn(ExperimentalComposeUiApi::class)
internal fun DragAndDropTransferAction.asDndAction(): DndAction =
    if (this == DragAndDropTransferAction.Move) DndAction.Move else DndAction.Copy

/**
 * What Compose calls the action a drag ended under, and null where it ended under none, which is Compose's own
 * way of saying the gesture did not complete.
 */
@OptIn(ExperimentalComposeUiApi::class)
internal fun DndAction.asCompletedAction(): DragAndDropTransferAction? = when (this) {
    DndAction.Copy -> DragAndDropTransferAction.Copy
    DndAction.Move -> DragAndDropTransferAction.Move
    DndAction.None, DndAction.Ask -> null
}

/**
 * What Compose calls the action a drag is carrying as it crosses content, out of the set [offered] the
 * destination named. Unlike [asCompletedAction] this has no way to say "none": a drag event carries one
 * action, and a copy is the one that takes nothing away.
 *
 * An action outside [offered] reads as a copy as well. `wl_data_offer.set_actions` settles on what both sides
 * offer, so one outside that is a value the compositor owed an update on, and content is not made to act on
 * it: told a move it never allowed, content deletes what nobody moved, where the mistake the other way leaves
 * one thing in two places.
 */
@OptIn(ExperimentalComposeUiApi::class)
internal fun DndAction.asDragAction(offered: Set<DndAction>): DragAndDropTransferAction =
    when (takeIf { it in offered }) {
        DndAction.Move -> DragAndDropTransferAction.Move
        DndAction.Copy, DndAction.None, DndAction.Ask, null -> DragAndDropTransferAction.Copy
    }

/** Where a drag over one of this client's surfaces goes: the content drawn on it, at the scale it is drawn at. */
internal class DragDestination(val scene: KortexScene, private val scale: Float) {
    /** A surface-local `wl_fixed_t` position as the pixels [scene] is laid out in. */
    fun scenePosition(x: Int, y: Int): Offset = PointerInput.scenePixels(x, y, scale)
}
