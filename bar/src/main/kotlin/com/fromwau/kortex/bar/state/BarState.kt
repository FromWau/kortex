package com.fromwau.kortex.bar.state

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kortex.tray.ItemAddress
import com.fromwau.kortex.bar.BarError
import com.fromwau.kortex.bar.system.MemoryUse
import com.fromwau.kortex.bar.system.NetworkRate
import com.fromwau.kortex.bar.system.Temperature
import java.time.LocalDateTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/**
 * What one widget has to show: nothing yet, a reading, or why there is none.
 *
 * [Pending] is what a widget shows before its first reading arrives, which is not the same as a widget
 * whose source failed, and a bar that drew them the same would look broken for its first second.
 */
sealed interface Reading<out T> {
    data object Pending : Reading<Nothing>

    data class Value<out T>(val value: T) : Reading<T>

    data class Unavailable(val error: BarError) : Reading<Nothing>
}

/** Where the focus timer has got to, as the bar draws it. */
sealed interface TimerFace {
    /** Not started. Clicking starts a session. */
    data object Idle : TimerFace

    /** Counting down, with [secondsLeft] to go. Clicking pauses. */
    data class Counting(val secondsLeft: Long) : TimerFace

    /** Held at [secondsLeft]. Clicking resumes from there. */
    data class Paused(val secondsLeft: Long) : TimerFace

    /** The session is over and is saying so until it is clicked away. */
    data object Elapsed : TimerFace
}

/** Everything one bar draws, and nothing about how it is drawn. */
data class BarState(
    val clock: Reading<LocalDateTime>,
    val showDetail: Boolean,
    val cpuLoad: Reading<Float>,
    val memory: Reading<MemoryUse>,
    val network: Reading<NetworkRate>,
    val temperature: Reading<Temperature>,
    val timer: TimerFace,
    val tray: Reading<List<TrayEntry>>,
    /** Which tray item the pointer is over, whose hover text the tray widget shows beside its icons. */
    val hoveredTray: ItemAddress?,
    val notifications: Reading<List<Posted>>,
    /** Which colours the bar draws with, cycled by a click rather than read from anywhere. */
    val scheme: BarScheme,
    /**
     * Who holds the tray's registry, where it is not this shell.
     *
     * Null when this shell is the registry, which is the case worth saying nothing about. A name here is
     * the bar admitting its tray belongs to another process, which is the difference between an empty
     * tray a person can explain and one they cannot.
     */
    val trayRegistry: String?,
) {
    companion object {
        /** The bar before any source has answered. */
        val Pending: BarState = BarState(
            clock = Reading.Pending,
            showDetail = false,
            cpuLoad = Reading.Pending,
            memory = Reading.Pending,
            network = Reading.Pending,
            temperature = Reading.Pending,
            timer = TimerFace.Idle,
            tray = Reading.Pending,
            hoveredTray = null,
            notifications = Reading.Pending,
            scheme = BarScheme.Dark,
            trayRegistry = null,
        )
    }
}

/** [this] as a [Reading], which is what the widgets are drawn from. */
fun <T> Result<T, BarError>.asReading(): Reading<T> = when (this) {
    is Ok -> Reading.Value(value)
    is Err -> Reading.Unavailable(error)
}

/**
 * [this] as a flow of [Reading]s that starts with [Reading.Pending].
 *
 * Without the leading value a `combine` over several of these emits nothing until the slowest source has
 * answered, so every widget on the bar would wait for the slowest one.
 */
fun <T> Flow<Result<T, BarError>>.readings(): Flow<Reading<T>> =
    map { result -> result.asReading() }.pendingFirst()

/** [this] with [Reading.Pending] in front, for a source that already answers in [Reading]s. */
fun <T> Flow<Reading<T>>.pendingFirst(): Flow<Reading<T>> = onStart { emit(Reading.Pending) }
