package com.fromwau.kortex.bar.system

import java.time.LocalDateTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * The local date and time, now and again at the top of every second.
 *
 * It sleeps to the next second boundary rather than for a whole second, so the readout changes when the
 * clock does instead of drifting a fraction of a second further behind on every tick.
 */
fun secondTicks(now: () -> LocalDateTime = LocalDateTime::now): Flow<LocalDateTime> = flow {
    while (true) {
        val reading = now()
        emit(reading)
        delay(MILLIS_IN_SECOND - (reading.nano / NANOS_IN_MILLI) % MILLIS_IN_SECOND)
    }
}

private const val MILLIS_IN_SECOND = 1_000L
private const val NANOS_IN_MILLI = 1_000_000L
