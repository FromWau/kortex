package com.fromwau.kortex.bar.system

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kortex.bar.BarError
import java.io.IOException
import java.nio.file.Files
import kotlinx.io.files.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText

/** The hwmon file a machine's CPU temperature is read from, and what that reading is called. */
data class CpuSensor(
    val input: Path,
    val label: String,
)

/**
 * The CPU's temperature sensor under [hwmon], or [BarError.NoSensor] when this machine exposes none.
 *
 * Which `hwmonN` is the CPU is not fixed: the number depends on probe order, so the directories are read
 * and matched on the driver name each one states. Among a driver's several inputs the one labelled for the
 * package is preferred, since on AMD `temp1` is `Tctl`, an offset control value, while `Tccd1` is a real
 * die reading.
 *
 * This looks at directory entries rather than watching a file, so `:watch` has nothing to do here; the
 * reading itself is what gets watched, once this has said which file it is.
 */
fun findCpuSensor(hwmon: java.nio.file.Path = java.nio.file.Path.of(HWMON)): Result<CpuSensor, BarError> {
    val drivers = try {
        hwmon.listDirectoryEntries("hwmon*")
            .mapNotNull { directory -> directory.driverName()?.let { name -> name to directory } }
    } catch (unreadable: IOException) {
        return Err(BarError.NoSensor("$hwmon (${unreadable.message})"))
    }

    for (driver in CPU_DRIVERS) {
        val directory = drivers.firstOrNull { (name, _) -> name == driver }?.second ?: continue
        val inputs = try {
            directory.listDirectoryEntries("temp*_input")
        } catch (unreadable: IOException) {
            continue
        }

        val labels = inputs.associateWith { input -> input.labelBeside() }
        val preferred = PREFERRED_LABELS.firstNotNullOfOrNull { wanted ->
            labels.entries.firstOrNull { (_, label) -> wanted.equals(label, ignoreCase = true) }
        }
        val chosen = preferred ?: labels.entries.minByOrNull { (input, _) -> input.fileName.toString() } ?: continue

        return Ok(CpuSensor(input = Path(chosen.key.toString()), label = chosen.value ?: driver))
    }

    return Err(BarError.NoSensor("$hwmon, among ${CPU_DRIVERS.joinToString()}"))
}

private fun java.nio.file.Path.driverName(): String? = resolve("name").readTextOrNull()?.trim()

private fun java.nio.file.Path.labelBeside(): String? = parent
    ?.resolve(fileName.toString().replace("_input", "_label"))
    ?.readTextOrNull()
    ?.trim()

private fun java.nio.file.Path.readTextOrNull(): String? = try {
    if (Files.isReadable(this)) readText() else null
} catch (unreadable: IOException) {
    null
}

private const val HWMON = "/sys/class/hwmon"

/** Drivers that report a CPU package temperature, in the order they are worth preferring. */
private val CPU_DRIVERS = listOf("k10temp", "coretemp", "zenpower", "cpu_thermal", "acpitz")

/** What a package-wide reading is called, across drivers. */
private val PREFERRED_LABELS = listOf("Tccd1", "Tdie", "Package id 0", "Tctl")
