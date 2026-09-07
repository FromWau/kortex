package com.fromwau.kortex.wayland

import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT

/**
 * `wl_surface.frame` pacing.
 *
 * The listener struct and its upcall stub are allocated once and reused for every callback proxy: a
 * stub per frame would leak one upcall per frame into the global arena.
 */
internal class FrameClock(private val surface: MemorySegment) {
    private var pending: MemorySegment = MemorySegment.NULL
    private var onFrame: ((Long) -> Unit)? = null

    private val listener: MemorySegment = LibWayland.arena.allocate(ADDRESS.byteSize()).also {
        it.setAtIndex(ADDRESS, 0L, LibWayland.upcall(this, "onDone", DONE_DESCRIPTOR))
    }

    /** Requests exactly one frame. A second request while one is outstanding is ignored. */
    fun request(onFrame: (Long) -> Unit) {
        if (!pending.equals(MemorySegment.NULL)) return
        this.onFrame = onFrame
        pending = LibWayland.marshal(
            surface, WL_SURFACE_FRAME, LibWayland.callbackInterface,
            LibWayland.proxyGetVersion(surface), listOf(WlArg.Ptr(MemorySegment.NULL)),
        )
        check(LibWayland.proxyAddListener(pending, listener, MemorySegment.NULL) == 0) {
            "wl_proxy_add_listener rejected the frame callback"
        }
    }

    fun onDone(data: MemorySegment, callback: MemorySegment, callbackData: Int) {
        val handler = onFrame
        // A wl_callback fires once and is then dead; the proxy has to be released or every frame leaks one.
        LibWayland.proxyDestroy(pending)
        pending = MemorySegment.NULL
        onFrame = null
        // callback_data is milliseconds with an undefined origin; Compose only needs monotonic nanos.
        handler?.invoke(callbackData.toUInt().toLong() * NANOS_PER_MILLI)
    }

    private companion object {
        private const val WL_SURFACE_FRAME = 3
        private const val NANOS_PER_MILLI = 1_000_000L
        private val DONE_DESCRIPTOR = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT)
    }
}
