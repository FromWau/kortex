package com.fromwau.kortex.wayland

import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT

/**
 * An output's identity and placement, as `wl_output` publishes it once per `done`.
 *
 * @property name the compositor's short identifier for the output, e.g. what `hyprctl monitors` calls it.
 * @property description a human-readable label for the output.
 * @property x the output's position in the compositor's global logical space.
 * @property y the output's position in the compositor's global logical space.
 * @property transform `wl_output.transform`'s wire value: 0 is unrotated, and higher values are the
 *   rotations and flips the protocol enumerates from there.
 * @property width the current mode's width in physical (buffer) pixels; divide by [scale] for the
 *   logical size a `configure` reports, such as [SurfaceConfig.contextMenu]'s `outputSize`.
 * @property height the current mode's height in physical (buffer) pixels, like [width].
 * @property scale `wl_output.scale`, an integer that overstates a fractional compositor scale (Hyprland
 *   ceil-rounds it).
 */
public data class OutputGeometry(
    public val name: String,
    public val description: String,
    public val x: Int,
    public val y: Int,
    public val transform: Int,
    public val width: Int,
    public val height: Int,
    public val scale: Int,
)

/**
 * Reads a `wl_output`'s geometry (position and transform), current mode, name, description and scale,
 * so a caller can centre a surface on an output and identify which output it is.
 *
 * Every wl_output event is double-buffered: the compositor may re-send any of them independently, and
 * the set is only coherent once `done` arrives. Events accumulate into pending fields here and
 * [geometry] is replaced atomically on `done`, so a reader never observes half an update.
 */
internal class OutputListener {
    @Volatile
    var geometry: OutputGeometry? = null
        private set

    private var pendingX = 0
    private var pendingY = 0
    private var pendingTransform = 0
    private var pendingName = ""
    private var pendingDescription = ""
    private var pendingWidth = 0
    private var pendingHeight = 0
    private var pendingScale = DEFAULT_SCALE

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
    ) {
        pendingX = x
        pendingY = y
        pendingTransform = transform
    }

    fun onMode(data: MemorySegment, proxy: MemorySegment, flags: Int, width: Int, height: Int, refresh: Int) {
        // A compositor sends every supported mode; only the one flagged current is the active one.
        if (flags and MODE_CURRENT == 0) return
        pendingWidth = width
        pendingHeight = height
    }

    fun onDone(data: MemorySegment, proxy: MemorySegment) {
        geometry = OutputGeometry(
            name = pendingName,
            description = pendingDescription,
            x = pendingX,
            y = pendingY,
            transform = pendingTransform,
            width = pendingWidth,
            height = pendingHeight,
            scale = pendingScale,
        )
    }

    fun onScale(data: MemorySegment, proxy: MemorySegment, factor: Int) {
        pendingScale = factor
    }

    fun onName(data: MemorySegment, proxy: MemorySegment, name: MemorySegment) {
        // The char* arrives with zero length because C says nothing about its extent.
        pendingName = name.reinterpret(Long.MAX_VALUE).getString(0)
    }

    fun onDescription(data: MemorySegment, proxy: MemorySegment, description: MemorySegment) {
        pendingDescription = description.reinterpret(Long.MAX_VALUE).getString(0)
    }

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

        private const val MODE_CURRENT = 0x1

        // wl_output.xml: "the client should assume a scale of 1" if the event is never sent.
        private const val DEFAULT_SCALE = 1
    }
}
