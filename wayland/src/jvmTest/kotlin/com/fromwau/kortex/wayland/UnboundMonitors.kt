package com.fromwau.kortex.wayland

import androidx.compose.ui.unit.IntSize
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment

/**
 * Runs [block] with a monitor for each of [names] whose output no shell has bound, as a monitor that has gone is to its
 * shell, and returns what [block] returns. Each describes a current mode of [mode] at [scale], turned by [transform].
 * Nothing about them reaches a compositor.
 */
internal fun <T> withUnboundMonitors(
    vararg names: String,
    mode: IntSize = IntSize.Zero,
    scale: Int = 1,
    transform: OutputTransform = OutputTransform.Normal,
    block: (List<Monitor>) -> T,
): T {
    val listeners = names.map { OutputListener() }
    try {
        val monitors = listeners.mapIndexed { index, listener ->
            // NULL make and model are never read: the listener keeps only the position and the transform.
            listener.onGeometry(
                NONE,
                NONE,
                x = 0,
                y = 0,
                physicalWidth = 0,
                physicalHeight = 0,
                subpixel = 0,
                make = NONE,
                model = NONE,
                transform = wireValueOf(transform),
            )
            Arena.ofConfined().use { strings -> listener.onName(NONE, NONE, strings.allocateFrom(names[index])) }
            listener.onMode(NONE, NONE, flags = MODE_CURRENT, width = mode.width, height = mode.height, refresh = 0)
            listener.onScale(NONE, NONE, factor = scale)
            listener.onDone(NONE, NONE)
            Monitor(ShellOutput(index, NONE, listener, xdgOutput = null))
        }
        return block(monitors)
    } finally {
        listeners.forEach(OutputListener::close)
    }
}

/** The number `wl_output.transform` carries for each transform, as wayland.xml lists them. */
internal val TRANSFORM_WIRE_VALUES: Map<OutputTransform, Int> = mapOf(
    OutputTransform.Normal to 0,
    OutputTransform.Rotated90 to 1,
    OutputTransform.Rotated180 to 2,
    OutputTransform.Rotated270 to 3,
    OutputTransform.Flipped to 4,
    OutputTransform.Flipped90 to 5,
    OutputTransform.Flipped180 to 6,
    OutputTransform.Flipped270 to 7,
)

/** One past wl_output.transform's last value: a number no compositor of this protocol version sends. */
internal const val UNLISTED_TRANSFORM_WIRE_VALUE = 8

private fun wireValueOf(transform: OutputTransform): Int = when (transform) {
    is OutputTransform.Unrecognized -> transform.wireValue
    else -> TRANSFORM_WIRE_VALUES.getValue(transform)
}

private val NONE: MemorySegment = MemorySegment.NULL

// wl_output.mode's flag for the mode in use; a mode without it is one the output merely supports.
private const val MODE_CURRENT = 0x1
