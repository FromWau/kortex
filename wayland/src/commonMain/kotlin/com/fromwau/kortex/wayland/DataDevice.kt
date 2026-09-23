package com.fromwau.kortex.wayland

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
        // NULL is in no map, so a drag the compositor names no offer for brings nothing to take.
        val brought = introduced.remove(offer.address()) ?: return
        val destination = dragDestinations(surface.address())
        val types = brought.offeredTypes
        if (destination == null || types.isEmpty()) return decline(brought, serial)
        val arrival = Drag(brought, destination, types, destination.scenePosition(x, y))
        // Content that has already failed takes nothing more, this drag included.
        val taken = destination.scene.sendDragEnter(arrival.position, arrival.announced).getOrElse { false }
        if (!taken) return decline(brought, serial)
        brought.accept(serial, types.first())
        brought.setActions(DND_ACTION_COPY, DND_ACTION_COPY)
        display.flush()
        drag = arrival
    }

    fun onLeave(data: MemorySegment, device: MemorySegment) {
        // A drop is followed by a leave of its own, and ends with its transfers rather than here.
        drag?.takeUnless { it.dropping }?.let(::leaveDrag)
    }

    fun onMotion(data: MemorySegment, device: MemorySegment, time: Int, x: Int, y: Int) {
        val moving = drag?.takeUnless { it.dropping } ?: return
        moving.position = moving.destination.scenePosition(x, y)
        moving.destination.scene.sendDragMove(moving.position, moving.announced)
    }

    fun onDrop(data: MemorySegment, device: MemorySegment) {
        val dropped = drag?.takeUnless { it.dropping } ?: return
        dropped.dropping = true
        // Opened here because only this thread may ask for them, and drained off it because a source writing
        // slowly would hold every surface up for as long as it took.
        val textPipe = dropped.textType?.let(dropped.offer::openTransfer)
        val imagePipe = dropped.imageType?.let(dropped.offer::openTransfer)
        display.flush()
        val io = Dispatchers.IO.asExecutor()
        io.execute {
            // What content is told if a drain throws instead of resolving to a Result: the types stay honest, but a
            // technical failure must not read as though the drag offered nothing of that kind.
            var carried = KortexDragOffer(
                carried = dropped.types,
                text = Err(ClipboardError.PipeFailed),
                image = Err(ClipboardError.PipeFailed),
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
                carried = KortexDragOffer(
                    carried = dropped.types,
                    text = drain(textPipe, MAX_TEXT_BYTES, ClipboardError.NoText).map(ByteArray::decodeToString),
                    // readPipeToEnd's own deadline starts only once the pool schedules this task; joined with a
                    // bound of its own so scheduling delay alone cannot hang the drop.
                    image = try {
                        image.get(IMAGE_JOIN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                    } catch (_: TimeoutException) {
                        Err(ClipboardError.ReadTimedOut)
                    },
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

    /** Gives the device back, then every offer it introduced; nothing here may be used afterwards. */
    fun release() {
        LibWayland.marshalIfSince(proxy, WL_DATA_DEVICE_RELEASE, WL_DATA_DEVICE_RELEASE_SINCE)
        LibWayland.proxyDestroy(proxy)
        // After the destroy, never before: a data_offer or selection still queued would otherwise reach a freed stub.
        arena.close()
        selection?.destroy()
        selection = null
        drag?.let(::endDrag)
        introduced.values.forEach(DataOffer::destroy)
        introduced.clear()
    }

    // Takes nothing from [offer] and gives it back, which leaves the drag's source cancelled.
    private fun decline(offer: DataOffer, serial: Int) {
        offer.accept(serial, type = null)
        display.flush()
        offer.destroy()
    }

    // Back on the loop thread, with everything [dropped] carried in hand.
    private fun completeDrop(dropped: Drag, carried: KortexDragOffer) {
        // The device can have been given back, or another drag begun, while the transfers drained.
        if (drag !== dropped) return
        dropped.destination.scene.sendDrop(dropped.position, carried)
        dropped.offer.finish()
        display.flush()
        endDrag(dropped)
    }

    // Tells content the drag is over with nothing dropped, and gives its offer back.
    private fun leaveDrag(leaving: Drag) {
        leaving.destination.scene.sendDragLeave(leaving.position, leaving.announced)
        endDrag(leaving)
    }

    // The offer alone, for a shell whose scenes have already closed. Never from inside one of the offer's own
    // events, whose stubs its destroy frees.
    private fun endDrag(ending: Drag) {
        drag = null
        ending.offer.destroy()
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
    ) {
        /** What content reads the drag through until it is dropped, which is its types and nothing else yet. */
        val announced = KortexDragOffer(types)

        /** The one text type a drop reads, out of [types]; null where the drag offers no text. */
        val textType: TextMime? = types.filterIsInstance<TextMime>().firstOrNull()

        /** The one image type a drop reads, out of [types]; null where the drag offers no image. */
        val imageType: ImageMime? = types.filterIsInstance<ImageMime>().firstOrNull()

        /** Set as the drop's transfers open, since the compositor follows a drop with a leave of its own. */
        var dropping = false
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

        // Slack over the image drain's own TRANSFER_TIMEOUT_MILLIS, for the wait to be scheduled on Dispatchers.IO
        // that deadline does not cover.
        private const val IMAGE_JOIN_SLACK_MILLIS = 1_000L
        private const val IMAGE_JOIN_TIMEOUT_MILLIS = TRANSFER_TIMEOUT_MILLIS + IMAGE_JOIN_SLACK_MILLIS

        private const val WL_DATA_DEVICE_MANAGER_GET_DATA_DEVICE = 1
        private const val WL_DATA_DEVICE_SET_SELECTION = 1
        private const val WL_DATA_DEVICE_RELEASE = 2
        private const val WL_DATA_DEVICE_RELEASE_SINCE = 2

        // wl_data_device_manager.dnd_action; copy is the only one kortex asks for or answers to.
        private const val DND_ACTION_COPY = 1

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

    // A finish before the compositor has settled on an action is a protocol error, and a source older than
    // wl_data_device_manager v3 makes it settle on none.
    private var actionSelected = false

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
        actionSelected = dndAction != DND_ACTION_NONE
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
     * `wl_data_offer.accept`: tells the drag's source that this client takes [type], or takes nothing where it is
     * null, which cancels the source.
     */
    fun accept(serial: Int, type: Mime?) {
        Arena.ofConfined().use { request ->
            val name = type?.let { request.allocateFrom(it.wireName) } ?: MemorySegment.NULL
            LibWayland.marshal(proxy, WL_DATA_OFFER_ACCEPT, args = listOf(WlArg.Num(serial), WlArg.Ptr(name)))
        }
    }

    /** `wl_data_offer.set_actions`: the drag actions this client supports, and the one it would rather have. */
    fun setActions(actions: Int, preferred: Int) {
        LibWayland.marshalIfSince(
            proxy, WL_DATA_OFFER_SET_ACTIONS, WL_DATA_OFFER_SET_ACTIONS_SINCE,
            args = listOf(WlArg.Num(actions), WlArg.Num(preferred)),
        )
    }

    /** `wl_data_offer.finish`: tells the drag's source its drop is done, where the compositor chose an action. */
    fun finish() {
        if (!actionSelected) return
        LibWayland.marshalIfSince(proxy, WL_DATA_OFFER_FINISH, WL_DATA_OFFER_FINISH_SINCE)
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
        const val WL_DATA_OFFER_FINISH_SINCE = 3
        const val WL_DATA_OFFER_SET_ACTIONS = 4
        const val WL_DATA_OFFER_SET_ACTIONS_SINCE = 3

        // wl_data_device_manager.dnd_action: the compositor settled on nothing.
        const val DND_ACTION_NONE = 0

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
internal class DataSource(private val clip: Clip, private val arena: Arena = Arena.ofShared()) {
    // Set on the loop thread; ownedText reads it from any.
    @Volatile
    private var cancelled = false

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
        val type = Mime.fromWireNameOrNull(mimeType.reinterpret(Long.MAX_VALUE).getString(0))
        // A type this copy never offered leaves the receiver an empty transfer, never another type's bytes.
        val bytes = type?.let(clip::bytesFor) ?: return LibC.close(fd)
        // Off the loop thread: a write into a full pipe waits for the receiver to drain it, stalling every surface.
        Dispatchers.IO.asExecutor().execute { writePipeAndClose(fd, bytes, TRANSFER_TIMEOUT_MILLIS) }
    }

    // Replaced as the selection. The next copy or clear, or the clipboard's close, destroys it, never this event,
    // which runs in one of the stubs that would free.
    fun onCancelled(data: MemorySegment, source: MemorySegment) {
        cancelled = true
    }

    fun onDndDropPerformed(data: MemorySegment, source: MemorySegment) = Unit

    fun onDndFinished(data: MemorySegment, source: MemorySegment) = Unit

    fun onAction(data: MemorySegment, source: MemorySegment, dndAction: Int) = Unit

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

    companion object {
        /** Creates a source for [clip] and offers it under every type [clip] holds. */
        fun create(manager: MemorySegment, clip: Clip): DataSource {
            val proxy = LibWayland.marshal(
                manager, WL_DATA_DEVICE_MANAGER_CREATE_DATA_SOURCE, LibWayland.dataSourceInterface,
                LibWayland.proxyGetVersion(manager), listOf(WlArg.Ptr(MemorySegment.NULL)),
            )
            val source = DataSource(clip).also { it.install(proxy) }
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

/** Where a drag over one of this client's surfaces goes: the content drawn on it, at the scale it is drawn at. */
internal class DragDestination(val scene: KortexScene, private val scale: Float) {
    /** A surface-local `wl_fixed_t` position as the pixels [scene] is laid out in. */
    fun scenePosition(x: Int, y: Int): Offset = PointerInput.scenePixels(x, y, scale)
}
