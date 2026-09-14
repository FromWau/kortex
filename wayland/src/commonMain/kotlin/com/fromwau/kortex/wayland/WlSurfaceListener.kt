package com.fromwau.kortex.wayland

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT

/**
 * Tracks the `wl_surface` events for one surface.
 *
 * Only `preferred_buffer_scale` carries state. The other three exist because libwayland dispatches by
 * indexing the listener struct with the event's opcode and calls straight through an empty slot.
 */
internal class WlSurfaceListener {
    @Volatile var preferredBufferScale: Int = DEFAULT_SCALE
        private set

    fun onEnter(data: MemorySegment, proxy: MemorySegment, output: MemorySegment) = Unit

    fun onLeave(data: MemorySegment, proxy: MemorySegment, output: MemorySegment) = Unit

    fun onPreferredBufferScale(data: MemorySegment, proxy: MemorySegment, factor: Int) {
        preferredBufferScale = factor
    }

    fun onPreferredBufferTransform(data: MemorySegment, proxy: MemorySegment, transform: Int) = Unit

    /** [arena] is the owning [LayerShellSurface]'s, which closes it once the `wl_surface` is destroyed. */
    fun install(arena: Arena, surface: MemorySegment) {
        val listener = arena.allocate(ADDRESS.byteSize() * EVENT_COUNT)
        listener.setAtIndex(ADDRESS, ENTER, LibWayland.upcall(arena, this, "onEnter", ENTER_DESCRIPTOR))
        listener.setAtIndex(ADDRESS, LEAVE, LibWayland.upcall(arena, this, "onLeave", LEAVE_DESCRIPTOR))
        listener.setAtIndex(
            ADDRESS, PREFERRED_BUFFER_SCALE,
            LibWayland.upcall(arena, this, "onPreferredBufferScale", PREFERRED_BUFFER_SCALE_DESCRIPTOR),
        )
        listener.setAtIndex(
            ADDRESS, PREFERRED_BUFFER_TRANSFORM,
            LibWayland.upcall(arena, this, "onPreferredBufferTransform", PREFERRED_BUFFER_TRANSFORM_DESCRIPTOR),
        )
        check(LibWayland.proxyAddListener(surface, listener, MemorySegment.NULL) == 0) {
            "wl_proxy_add_listener rejected the surface listener"
        }
    }

    companion object {
        // wl_surface v6 declares exactly these four events; every slot must be filled, because
        // libwayland indexes the struct and calls straight through it.
        private const val EVENT_COUNT = 4L
        private const val ENTER = 0L
        private const val LEAVE = 1L
        private const val PREFERRED_BUFFER_SCALE = 2L
        private const val PREFERRED_BUFFER_TRANSFORM = 3L

        /** The protocol's own starting value: a surface renders at scale 1 until the compositor says otherwise. */
        private const val DEFAULT_SCALE = 1

        private val ENTER_DESCRIPTOR = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS)
        private val LEAVE_DESCRIPTOR = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS)
        private val PREFERRED_BUFFER_SCALE_DESCRIPTOR = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT)
        private val PREFERRED_BUFFER_TRANSFORM_DESCRIPTOR = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT)
    }
}
