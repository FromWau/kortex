package com.fromwau.kortex.bar.system

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.IError
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result

/** Why a procfs file's text did not turn into the reading a widget wanted. */
sealed interface ParseFailure : IError {
    /** No line in the file started with [prefix]. */
    data class MissingLine(val prefix: String) : ParseFailure

    /** [field] held [text], which is not the number it has to be. */
    data class NotANumber(val field: String, val text: String) : ParseFailure

    /** [field] held [value], which a reading cannot be made of. */
    data class OutOfRange(val field: String, val value: Long) : ParseFailure
}

/**
 * The jiffies the kernel has counted against each state of the whole machine, as `/proc/stat`'s first line
 * reports them. A load is the difference between two of these, never one on its own.
 */
data class CpuTimes(
    val busy: Long,
    val total: Long,
) {
    companion object {
        /**
         * The share of [later] - [earlier] that was spent working, or [ParseFailure.OutOfRange] when the
         * counters went backwards, which a suspend or a counter reset does.
         */
        fun load(earlier: CpuTimes, later: CpuTimes): Result<Float, ParseFailure> {
            val elapsed = later.total - earlier.total
            val worked = later.busy - earlier.busy
            return when {
                elapsed <= 0L || worked < 0L -> Err(ParseFailure.OutOfRange("cpu", elapsed))
                else -> Ok((worked.toFloat() / elapsed.toFloat()).coerceIn(0f, 1f))
            }
        }
    }
}

/** How much of the machine's memory is in use, in kibibytes as `/proc/meminfo` states them. */
data class MemoryUse(
    val usedKib: Long,
    val totalKib: Long,
) {
    val fraction: Float get() = if (totalKib <= 0L) 0f else (usedKib.toFloat() / totalKib.toFloat())
}

/** The byte counters of every interface in `/proc/net/dev`, summed over the ones that carry real traffic. */
data class NetworkTotals(
    val receivedBytes: Long,
    val sentBytes: Long,
)

/** Bytes per second in each direction, between two [NetworkTotals] one interval apart. */
data class NetworkRate(
    val downBytesPerSecond: Long,
    val upBytesPerSecond: Long,
) {
    companion object {
        fun between(earlier: NetworkTotals, later: NetworkTotals, seconds: Double): NetworkRate {
            if (seconds <= 0.0) return NetworkRate(0L, 0L)
            val down = ((later.receivedBytes - earlier.receivedBytes) / seconds).toLong()
            val up = ((later.sentBytes - earlier.sentBytes) / seconds).toLong()
            return NetworkRate(down.coerceAtLeast(0L), up.coerceAtLeast(0L))
        }
    }
}

/** The aggregate `cpu` line of `/proc/stat`'s [text]: `cpu user nice system idle iowait irq softirq steal …`. */
fun parseCpuTimes(text: String): Result<CpuTimes, ParseFailure> {
    val fields = text.lineSequence()
        .firstOrNull { line -> line.startsWith("cpu ") }
        ?.split(' ')
        ?.drop(1)
        ?.filter(String::isNotEmpty)
        ?: return Err(ParseFailure.MissingLine("cpu "))

    val jiffies = fields.map { field ->
        field.toLongOrNull() ?: return Err(ParseFailure.NotANumber("cpu", field))
    }
    // Idle and iowait sit at positions 3 and 4 and are the only two that are not work.
    val idle = jiffies.getOrElse(IDLE) { 0L } + jiffies.getOrElse(IOWAIT) { 0L }
    val total = jiffies.sum()
    return Ok(CpuTimes(busy = total - idle, total = total))
}

/** A sensor's temperature and the name the machine gave it, which differs per driver. */
data class Temperature(val label: String, val celsius: Celsius)

/** A temperature in whole degrees Celsius, which is all a bar has room for. */
@JvmInline
value class Celsius(val degrees: Int)

/** An hwmon `temp*_input`, which holds one integer: the reading in thousandths of a degree Celsius. */
fun parseMilliCelsius(text: String): Result<Celsius, ParseFailure> {
    val reading = text.trim()
    val millidegrees = reading.toIntOrNull() ?: return Err(ParseFailure.NotANumber("temp_input", reading))
    return Ok(Celsius(millidegrees / MILLIDEGREES_IN_DEGREE))
}

/** `MemTotal` and `MemAvailable` out of `/proc/meminfo`'s [text], as what is in use out of what there is. */
fun parseMemoryUse(text: String): Result<MemoryUse, ParseFailure> {
    val total = text.kibibytesOf("MemTotal:") ?: return Err(ParseFailure.MissingLine("MemTotal:"))
    val available = text.kibibytesOf("MemAvailable:") ?: return Err(ParseFailure.MissingLine("MemAvailable:"))
    return when (val kib = total.toLongOrNull()) {
        null -> Err(ParseFailure.NotANumber("MemTotal", total))
        else -> when (val free = available.toLongOrNull()) {
            null -> Err(ParseFailure.NotANumber("MemAvailable", available))
            else -> Ok(MemoryUse(usedKib = (kib - free).coerceAtLeast(0L), totalKib = kib))
        }
    }
}

/**
 * `/proc/net/dev`'s [text], summed over every interface but the loopback, whose traffic never left the
 * machine and would otherwise double every local transfer.
 */
fun parseNetworkTotals(text: String): Result<NetworkTotals, ParseFailure> {
    var received = 0L
    var sent = 0L
    var sawAnInterface = false

    for (line in text.lineSequence()) {
        val separator = line.indexOf(':')
        if (separator < 0) continue
        val name = line.take(separator).trim()
        if (name.isEmpty() || name == "lo") continue

        val counters = line.drop(separator + 1).split(' ').filter(String::isNotEmpty)
        val down = counters.getOrNull(RECEIVE_BYTES) ?: continue
        val up = counters.getOrNull(TRANSMIT_BYTES) ?: continue
        received += down.toLongOrNull() ?: return Err(ParseFailure.NotANumber(name, down))
        sent += up.toLongOrNull() ?: return Err(ParseFailure.NotANumber(name, up))
        sawAnInterface = true
    }

    return if (sawAnInterface) Ok(NetworkTotals(received, sent)) else Err(ParseFailure.MissingLine("<interface>:"))
}

private fun String.kibibytesOf(prefix: String): String? = lineSequence()
    .firstOrNull { line -> line.startsWith(prefix) }
    ?.removePrefix(prefix)
    ?.trim()
    ?.removeSuffix(" kB")
    ?.trim()

private const val IDLE = 3
private const val IOWAIT = 4
private const val MILLIDEGREES_IN_DEGREE = 1_000

// Receive takes the first eight counters, so transmitted bytes are the ninth.
private const val RECEIVE_BYTES = 0
private const val TRANSMIT_BYTES = 8
