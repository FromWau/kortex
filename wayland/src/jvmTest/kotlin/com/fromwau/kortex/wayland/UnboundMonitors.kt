package com.fromwau.kortex.wayland

import androidx.compose.ui.unit.IntSize
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment

/**
 * Runs [block] with a monitor for each of [names] whose output no shell has bound, as a monitor that has gone is to its
 * shell, and returns what [block] returns. Each describes a current mode of [mode] at [scale], turned by [transform], a
 * `wl_output.transform` value. Nothing about them reaches a compositor.
 */
internal fun <T> withUnboundMonitors(
    vararg names: String,
    mode: IntSize = IntSize.Zero,
    scale: Int = 1,
    transform: Int = 0,
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
                transform = transform,
            )
            Arena.ofConfined().use { strings -> listener.onName(NONE, NONE, strings.allocateFrom(names[index])) }
            listener.onMode(NONE, NONE, flags = MODE_CURRENT, width = mode.width, height = mode.height, refresh = 0)
            listener.onScale(NONE, NONE, factor = scale)
            listener.onDone(NONE, NONE)
            Monitor(ShellOutput(index, NONE, listener))
        }
        return block(monitors)
    } finally {
        listeners.forEach(OutputListener::close)
    }
}

private val NONE: MemorySegment = MemorySegment.NULL

// wl_output.mode's flag for the mode in use; a mode without it is one the output merely supports.
private const val MODE_CURRENT = 0x1
