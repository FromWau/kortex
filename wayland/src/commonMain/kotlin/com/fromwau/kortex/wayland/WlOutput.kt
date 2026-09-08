package com.fromwau.kortex.wayland

import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT

/** Fills the listener struct without reading anything; kortex binds a `wl_output` only to aim a surface at it. */
internal class OutputListener {
    fun onGeometry(
        data: MemorySegment,
        proxy: MemorySegment,
        x: Int,
        y: Int,
        physicalWidth: Int,
        physicalHeight: Int,
        subpixel: Int,
        make: MemorySegment,
        model: MemorySegment,
        transform: Int,
    ) = Unit

    fun onMode(data: MemorySegment, proxy: MemorySegment, flags: Int, width: Int, height: Int, refresh: Int) = Unit

    fun onDone(data: MemorySegment, proxy: MemorySegment) = Unit

    fun onScale(data: MemorySegment, proxy: MemorySegment, factor: Int) = Unit

    fun onName(data: MemorySegment, proxy: MemorySegment, name: MemorySegment) = Unit

    fun onDescription(data: MemorySegment, proxy: MemorySegment, description: MemorySegment) = Unit

    fun install(output: MemorySegment) {
        val listener = LibWayland.arena.allocate(ADDRESS.byteSize() * EVENT_COUNT)
        listener.setAtIndex(ADDRESS, GEOMETRY, LibWayland.upcall(this, "onGeometry", GEOMETRY_DESCRIPTOR))
        listener.setAtIndex(ADDRESS, MODE, LibWayland.upcall(this, "onMode", MODE_DESCRIPTOR))
        listener.setAtIndex(ADDRESS, DONE, LibWayland.upcall(this, "onDone", DONE_DESCRIPTOR))
        listener.setAtIndex(ADDRESS, SCALE, LibWayland.upcall(this, "onScale", SCALE_DESCRIPTOR))
        listener.setAtIndex(ADDRESS, NAME, LibWayland.upcall(this, "onName", NAME_DESCRIPTOR))
        listener.setAtIndex(ADDRESS, DESCRIPTION, LibWayland.upcall(this, "onDescription", DESCRIPTION_DESCRIPTOR))
        check(LibWayland.proxyAddListener(output, listener, MemorySegment.NULL) == 0) {
            "wl_proxy_add_listener rejected the output listener"
        }
    }

    companion object {
        // wl_output v4 declares exactly these six events; every slot must be filled, because
        // libwayland indexes the struct and calls straight through it.
        private const val EVENT_COUNT = 6L
        private const val GEOMETRY = 0L
        private const val MODE = 1L
        private const val DONE = 2L
        private const val SCALE = 3L
        private const val NAME = 4L
        private const val DESCRIPTION = 5L

        private val GEOMETRY_DESCRIPTOR = FunctionDescriptor.ofVoid(
            ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT,
        )
        private val MODE_DESCRIPTOR =
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT)
        private val DONE_DESCRIPTOR = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS)
        private val SCALE_DESCRIPTOR = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT)
        private val NAME_DESCRIPTOR = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS)
        private val DESCRIPTION_DESCRIPTOR = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS)
    }
}
