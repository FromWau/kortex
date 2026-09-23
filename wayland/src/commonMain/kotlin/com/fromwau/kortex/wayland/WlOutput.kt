package com.fromwau.kortex.wayland

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntSize
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT

/**
 * An output's identity, placement and size, as the compositor last published them. Every field describes the
 * same instant.
 *
 * @property name the compositor's short identifier for the output, e.g. what `hyprctl monitors` calls it.
 * @property description a human-readable label for the output.
 * @property x the output's position in the compositor's global logical space.
 * @property y the output's position in the compositor's global logical space.
 * @property transform how the monitor is turned and mirrored. [width] and [height] are its unturned mode, so on a
 *   monitor turned a quarter they swap on screen; [logicalWidth] and [logicalHeight] are already turned.
 * @property width the current mode's width in physical (buffer) pixels.
 * @property height the current mode's height in physical (buffer) pixels.
 * @property scale `wl_output.scale`, an integer that overstates a fractional compositor scale (Hyprland
 *   ceil-rounds it). Nothing has to divide a mode by it: [logicalWidth] and [logicalHeight] carry the true scale.
 * @property logicalWidth the output's width in logical pixels, the unit a surface's size, position and margins
 *   are in, as the compositor measures its own space: exact at a fractional scale, and already turned. On a
 *   compositor that describes none of its outputs this way, it falls back to [width] over [scale], which is
 *   neither turned nor exact below a whole-number scale.
 * @property logicalHeight the output's height in logical pixels, like [logicalWidth].
 */
public data class OutputGeometry(
    public val name: String,
    public val description: String,
    public val x: Int,
    public val y: Int,
    public val transform: OutputTransform,
    public val width: Int,
    public val height: Int,
    public val scale: Int,
    public val logicalWidth: Int,
    public val logicalHeight: Int,
)

/**
 * How the compositor turns and mirrors a monitor's picture. Turns run counter-clockwise, and a flipped transform
 * mirrors the picture around a vertical axis before it turns it.
 */
public sealed interface OutputTransform {
    /**
     * Whether the monitor is turned a quarter, 90 or 270 degrees, mirrored or not, which swaps its width and height
     * on screen. [Unrecognized] is not.
     */
    public val isQuarterTurn: Boolean
        get() = when (this) {
            Rotated90, Rotated270, Flipped90, Flipped270 -> true
            Normal, Rotated180, Flipped, Flipped180, is Unrecognized -> false
        }

    /** Neither turned nor mirrored. */
    public data object Normal : OutputTransform

    /** Turned 90 degrees. */
    public data object Rotated90 : OutputTransform

    /** Turned 180 degrees. */
    public data object Rotated180 : OutputTransform

    /** Turned 270 degrees. */
    public data object Rotated270 : OutputTransform

    /** Mirrored, and not turned. */
    public data object Flipped : OutputTransform

    /** Mirrored, then turned 90 degrees. */
    public data object Flipped90 : OutputTransform

    /** Mirrored, then turned 180 degrees. */
    public data object Flipped180 : OutputTransform

    /** Mirrored, then turned 270 degrees. */
    public data object Flipped270 : OutputTransform

    /** A transform kortex does not know, with the number the compositor sent for it. */
    public data class Unrecognized(public val wireValue: Int) : OutputTransform
}

/**
 * Reads a `wl_output`'s geometry (position and transform), current mode, name, description and scale,
 * so a caller can centre a surface on an output and identify which output it is.
 *
 * Every wl_output event is double-buffered: the compositor may re-send any of them independently, and
 * the set is only coherent once `done` arrives. Events accumulate into pending fields here and
 * [geometry] is replaced atomically on `done`. At version 3 a `zxdg_output_v1`'s own details arrive under
 * the same `done`, so [xdgOutput] is read there too.
 *
 * A monitor that is reconfigured while it is on screen can publish one geometry carrying a fresh mode and
 * scale beside a logical size the compositor has not yet resent, because Hyprland schedules the second
 * `done` for a later turn of its loop. The next `done` corrects it. Nothing observes it at startup or on a
 * hotplug, where no monitor is published until the round trip has already returned the final values.
 */
internal class OutputListener {
    private val arena: Arena = Arena.ofShared()

    /** What this output's `zxdg_output_v1` reports, if the shell took one; empty until it does. */
    val xdgOutput: XdgOutputListener = XdgOutputListener()

    // Snapshot state, not a plain field: an output may publish again at any time, and content reading
    // its geometry through a Monitor has to recompose when it does.
    var geometry: OutputGeometry? by mutableStateOf(null)
        private set

    private var pendingX = 0
    private var pendingY = 0
    private var pendingTransform: OutputTransform = OutputTransform.Normal
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
        pendingTransform = outputTransform(transform)
    }

    fun onMode(data: MemorySegment, proxy: MemorySegment, flags: Int, width: Int, height: Int, refresh: Int) {
        // A compositor sends every supported mode; only the one flagged current is the active one.
        if (flags and MODE_CURRENT == 0) return
        pendingWidth = width
        pendingHeight = height
    }

    fun onDone(data: MemorySegment, proxy: MemorySegment) {
        // No zxdg_output_v1: the mode over the scale, coerced since a throw here would cross native frames.
        val scale = pendingScale.coerceAtLeast(DEFAULT_SCALE)
        val logicalSize = xdgOutput.logicalSize ?: IntSize(pendingWidth / scale, pendingHeight / scale)
        val logicalPosition = xdgOutput.logicalPosition
        geometry = OutputGeometry(
            name = pendingName,
            description = pendingDescription,
            x = logicalPosition?.x ?: pendingX,
            y = logicalPosition?.y ?: pendingY,
            transform = pendingTransform,
            width = pendingWidth,
            height = pendingHeight,
            scale = pendingScale,
            logicalWidth = logicalSize.width,
            logicalHeight = logicalSize.height,
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
        val listener = arena.allocate(ADDRESS.byteSize() * EVENT_COUNT)
        listener.setAtIndex(ADDRESS, GEOMETRY, LibWayland.upcall(arena, this, "onGeometry", GEOMETRY_DESCRIPTOR))
        listener.setAtIndex(ADDRESS, MODE, LibWayland.upcall(arena, this, "onMode", MODE_DESCRIPTOR))
        listener.setAtIndex(ADDRESS, DONE, LibWayland.upcall(arena, this, "onDone", DONE_DESCRIPTOR))
        listener.setAtIndex(ADDRESS, SCALE, LibWayland.upcall(arena, this, "onScale", SCALE_DESCRIPTOR))
        listener.setAtIndex(ADDRESS, NAME, LibWayland.upcall(arena, this, "onName", NAME_DESCRIPTOR))
        listener.setAtIndex(
            ADDRESS, DESCRIPTION,
            LibWayland.upcall(arena, this, "onDescription", DESCRIPTION_DESCRIPTOR),
        )
        check(LibWayland.proxyAddListener(output, listener, MemorySegment.NULL) == 0) {
            "wl_proxy_add_listener rejected the output listener"
        }
    }

    /** Frees all six stubs; only [ShellOutput.destroy] may call it, and only after the `wl_output` is gone. */
    fun close() {
        arena.close()
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

        // wayland.xml's wl_output.transform numbering. Never throws: it runs inside a libwayland callback.
        private fun outputTransform(wireValue: Int): OutputTransform = when (wireValue) {
            0 -> OutputTransform.Normal
            1 -> OutputTransform.Rotated90
            2 -> OutputTransform.Rotated180
            3 -> OutputTransform.Rotated270
            4 -> OutputTransform.Flipped
            5 -> OutputTransform.Flipped90
            6 -> OutputTransform.Flipped180
            7 -> OutputTransform.Flipped270
            else -> OutputTransform.Unrecognized(wireValue)
        }
    }
}
