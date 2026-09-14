package com.fromwau.kortex.wayland

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor

/**
 * The clipboard's `wl_data_device`: follows which offer is the selection, and gives back every other offer the
 * compositor introduces.
 */
internal class DataDevice private constructor(private val proxy: MemorySegment) {
    private val arena: Arena = Arena.ofShared()

    // Introduced by data_offer and not yet named by a selection, by proxy address.
    private val introduced = mutableMapOf<Long, DataOffer>()

    /** The offer that is the selection; null while nothing is selected, and until this client first has focus. */
    var selection: DataOffer? = null
        private set

    /** Whether [selection] is offered as text; answered at once, on any thread. */
    @Volatile
    var selectionHasText: Boolean = false
        private set

    fun onDataOffer(data: MemorySegment, device: MemorySegment, offer: MemorySegment) {
        introduced[offer.address()] = DataOffer().also { it.install(offer) }
    }

    // kortex takes no drops, so the offer a drag brings is given back as soon as the drag names it.
    fun onEnter(
        data: MemorySegment,
        device: MemorySegment,
        serial: Int,
        surface: MemorySegment,
        x: Int,
        y: Int,
        offer: MemorySegment,
    ) {
        introduced.remove(offer.address())?.destroy()
    }

    fun onLeave(data: MemorySegment, device: MemorySegment) = Unit

    fun onMotion(data: MemorySegment, device: MemorySegment, time: Int, x: Int, y: Int) = Unit

    fun onDrop(data: MemorySegment, device: MemorySegment) = Unit

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
        introduced.values.forEach(DataOffer::destroy)
        introduced.clear()
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

    companion object {
        /** Takes [seat]'s data device from [manager], listening before the compositor can send it anything. */
        fun create(manager: MemorySegment, seat: Seat): DataDevice {
            val proxy = LibWayland.marshal(
                manager, WL_DATA_DEVICE_MANAGER_GET_DATA_DEVICE, LibWayland.dataDeviceInterface,
                LibWayland.proxyGetVersion(manager), listOf(WlArg.Ptr(MemorySegment.NULL), WlArg.Ptr(seat.proxy)),
            )
            return DataDevice(proxy).also { it.install() }
        }

        private const val WL_DATA_DEVICE_MANAGER_GET_DATA_DEVICE = 1
        private const val WL_DATA_DEVICE_SET_SELECTION = 1
        private const val WL_DATA_DEVICE_RELEASE = 2
        private const val WL_DATA_DEVICE_RELEASE_SINCE = 2

        // wl_data_device v3 declares exactly these six events; every slot must be filled, because
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
    private val textTypes = mutableSetOf<TextMime>()

    /** The type a paste asks for: the first [TextMime] this offer lists, or null when it lists none. */
    val preferredText: TextMime? get() = TextMime.entries.firstOrNull { it in textTypes }

    fun onOffer(data: MemorySegment, offer: MemorySegment, mimeType: MemorySegment) {
        // The char* arrives with zero length because C says nothing about its extent.
        TextMime.fromWireNameOrNull(mimeType.reinterpret(Long.MAX_VALUE).getString(0))?.let(textTypes::add)
    }

    fun onSourceActions(data: MemorySegment, offer: MemorySegment, sourceActions: Int) = Unit

    fun onAction(data: MemorySegment, offer: MemorySegment, dndAction: Int) = Unit

    /** `wl_data_offer.receive`: asks the offer's source to write itself into [fd], as [type]. */
    fun receive(type: TextMime, fd: Int) {
        // wl_proxy_marshal copies the string into the message it builds, so it is only borrowed for the call.
        Arena.ofConfined().use { request ->
            LibWayland.marshal(
                proxy, WL_DATA_OFFER_RECEIVE,
                args = listOf(WlArg.Ptr(request.allocateFrom(type.wireName)), WlArg.Num(fd)),
            )
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
        const val WL_DATA_OFFER_RECEIVE = 1
        const val WL_DATA_OFFER_DESTROY = 2

        // wl_data_offer v3 declares exactly these three events; every slot must be filled, because
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
 * One copy's `wl_data_source`: offers its text under every [TextMime], and sends the same UTF-8 for each.
 *
 * @param arena holds its listener's stubs; [destroy] closes it.
 */
internal class DataSource(private val text: String, private val arena: Arena = Arena.ofShared()) {
    private val bytes = text.encodeToByteArray()

    // Set on the loop thread; ownedText reads it from any.
    @Volatile
    private var cancelled = false

    /** Its text until the compositor cancels it, as it does once another selection or a clear replaces it. */
    val ownedText: String? get() = text.takeUnless { cancelled }

    var proxy: MemorySegment = MemorySegment.NULL
        private set

    fun onTarget(data: MemorySegment, source: MemorySegment, mimeType: MemorySegment) = Unit

    fun onSend(data: MemorySegment, source: MemorySegment, mimeType: MemorySegment, fd: Int) {
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
        /** Creates a source for [text] and offers it under every [TextMime]. */
        fun create(manager: MemorySegment, text: String): DataSource {
            val proxy = LibWayland.marshal(
                manager, WL_DATA_DEVICE_MANAGER_CREATE_DATA_SOURCE, LibWayland.dataSourceInterface,
                LibWayland.proxyGetVersion(manager), listOf(WlArg.Ptr(MemorySegment.NULL)),
            )
            val source = DataSource(text).also { it.install(proxy) }
            // wl_proxy_marshal copies each string into the message it builds, so they are only borrowed for the calls.
            Arena.ofConfined().use { request ->
                TextMime.entries.forEach { type ->
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

        // wl_data_source v3 declares exactly these six events; every slot must be filled, because
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
