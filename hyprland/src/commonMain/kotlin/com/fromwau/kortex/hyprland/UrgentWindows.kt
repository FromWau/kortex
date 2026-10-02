package com.fromwau.kortex.hyprland

import java.util.concurrent.atomic.AtomicReference

/**
 * The windows that asked for attention and have not had focus since, kept from the events alone.
 *
 * Hyprland answers no query with a window's urgency: it marks one on `urgent` and clears the mark when the
 * window takes focus, so following the same two events, and a close, is the only way to know it.
 */
internal class UrgentWindows {
    // Read where the monitors are read and written where events arrive, which are two coroutines.
    private val windows = AtomicReference<Set<WindowAddress>>(emptySet())

    val addresses: Set<WindowAddress> get() = windows.get()

    /** Takes [event] into account, answering whether the set changed and so whether to read again. */
    fun observe(event: Tick.Event): Boolean {
        // Events name a window in bare hex, where every query writes it with 0x in front.
        val window = WindowAddress("0x${event.data}")
        val before = windows.get()
        val after = when (event.name) {
            URGENT -> windows.updateAndGet { it + window }
            FOCUSED, CLOSED -> windows.updateAndGet { it - window }
            else -> return false
        }
        return after != before
    }

    private companion object {
        const val URGENT = "urgent"
        const val FOCUSED = "activewindowv2"
        const val CLOSED = "closewindow"
    }
}
