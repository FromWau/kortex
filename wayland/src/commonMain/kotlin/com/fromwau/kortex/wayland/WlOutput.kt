package com.fromwau.kortex.wayland

import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT

/** Binds every `wl_output` global to read the compositor-reported scale factor. */
public object WlOutput {
    /** The scale factor to render at, read once. See [Handle.scale] for the fallback rule. */
    public fun detectScale(display: WaylandDisplay): Int = bind(display).scale

    /**
     * Binds every `wl_output` global and keeps its listener installed, unlike [detectScale], so
     * [Handle.scale] can be re-read after startup to observe a later `wl_output.scale` event.
     */
    internal fun bind(display: WaylandDisplay): Handle {
        val outputs = display.globals.filter { it.interfaceName == "wl_output" }
        val listeners = outputs.map { global ->
            val listener = OutputListener()
            listener.install(display.bind(global, LibWayland.outputInterface, WlVersion.OUTPUT))
            listener
        }
        if (listeners.isNotEmpty()) display.roundtrip()
        return Handle(listeners)
    }

    /**
     * The live scale factor across every bound output.
     *
     * Falls back to 1 when the compositor advertises no output or when outputs disagree; picking the
     * output a surface is actually on needs `wl_surface.enter`, which is not implemented here.
     */
    internal class Handle(private val listeners: List<OutputListener>) {
        val scale: Int get() = listeners.map { it.scale }.distinct().singleOrNull() ?: DEFAULT_SCALE
    }

    private const val DEFAULT_SCALE = 1
}

internal class OutputListener {
    @Volatile var scale: Int = 1
        private set

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

    fun onScale(data: MemorySegment, proxy: MemorySegment, factor: Int) {
        scale = factor
    }

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
