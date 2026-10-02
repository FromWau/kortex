package com.fromwau.kortex.bar.system

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.flatMap
import com.fromwau.kern.result.map
import com.fromwau.kern.result.mapError
import com.fromwau.kortex.bar.BarError
import com.fromwau.kortex.watch.readTextEvery
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.io.files.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** What the machine itself is doing, as the bar's widgets want it. */
interface SystemMetrics {
    /** The share of the machine's processors that is busy, between 0 and 1. */
    val cpuLoad: Flow<Result<Float, BarError>>

    /** How much memory is in use. */
    val memory: Flow<Result<MemoryUse, BarError>>

    /** How fast bytes are moving in each direction. */
    val network: Flow<Result<NetworkRate, BarError>>

    /** How hot the CPU is, and what the machine calls that reading. */
    val cpuTemperature: Flow<Result<Temperature, BarError>>
}

/**
 * [SystemMetrics] read off procfs and sysfs with kortex's `:watch`.
 *
 * Every file here is one the kernel makes up as it is read, so each is polled on an interval rather than
 * waited on: the overload that waits for the operating system answers `WatchError.Unwatchable` for these.
 *
 * [cpuLoad] and [network] are differences between two reads, so each has nothing to show until it has read
 * twice, one interval apart.
 */
class ProcfsMetrics(
    private val cpuInterval: Duration = 1.seconds,
    private val memoryInterval: Duration = 2.seconds,
    private val networkInterval: Duration = 2.seconds,
    private val temperatureInterval: Duration = 5.seconds,
    private val proc: Path = Path("/proc"),
    private val sensor: Result<CpuSensor, BarError> = findCpuSensor(),
) : SystemMetrics {
    override val cpuLoad: Flow<Result<Float, BarError>>
        get() = reads(Path(proc, "stat"), cpuInterval, ::parseCpuTimes)
            .differences { earlier, later, _ ->
                CpuTimes.load(earlier, later).mapError(BarError::Unparseable)
            }

    override val memory: Flow<Result<MemoryUse, BarError>>
        get() = reads(Path(proc, "meminfo"), memoryInterval, ::parseMemoryUse)

    override val network: Flow<Result<NetworkRate, BarError>>
        get() = reads(Path(proc, "net", "dev"), networkInterval, ::parseNetworkTotals)
            .differences { earlier, later, elapsed ->
                Ok(NetworkRate.between(earlier, later, elapsed.toDouble(DurationUnit.SECONDS)))
            }

    override val cpuTemperature: Flow<Result<Temperature, BarError>>
        get() = when (sensor) {
            // A machine with no CPU sensor has nothing to poll, so this is the one widget that is settled
            // at startup and never changes its mind.
            is Err -> flowOf(Err(sensor.error))

            is Ok -> reads(sensor.value.input, temperatureInterval, ::parseMilliCelsius).map { reading ->
                reading.map { celsius -> Temperature(sensor.value.label, celsius) }
            }
        }

    private fun <T> reads(
        path: Path,
        every: Duration,
        parse: (String) -> Result<T, ParseFailure>,
    ): Flow<Result<T, BarError>> = path.readTextEvery(every).map { read ->
        read.mapError(BarError::Unreadable).flatMap { text -> parse(text).mapError(BarError::Unparseable) }
    }
}

/**
 * Each consecutive pair of successful readings, as [delta] makes one value of the two, with how long
 * actually passed between them.
 *
 * The elapsed time is measured rather than assumed to be the polling interval, because `:watch` emits
 * nothing for a read that found no change: an idle network interface reports the same counters for a
 * minute and then one change, and a rate computed against the interval instead of the gap would
 * overstate that minute's traffic many times over.
 *
 * A failure is passed on and forgets the reading before it, so the next pair is two real readings.
 */
private fun <S, T> Flow<Result<S, BarError>>.differences(
    delta: (earlier: S, later: S, elapsed: Duration) -> Result<T, BarError>,
): Flow<Result<T, BarError>> = flow {
    var previous: Pair<S, TimeMark>? = null
    collect { reading ->
        when (reading) {
            is Err -> {
                previous = null
                emit(Err(reading.error))
            }

            is Ok -> {
                val taken = TimeSource.Monotonic.markNow()
                previous?.let { (earlier, before) -> emit(delta(earlier, reading.value, before.elapsedNow())) }
                previous = reading.value to taken
            }
        }
    }
}
