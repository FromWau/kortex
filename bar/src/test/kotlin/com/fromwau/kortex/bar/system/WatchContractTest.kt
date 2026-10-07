package com.fromwau.kortex.bar.system

import com.fromwau.kern.dirs.FileError
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import com.fromwau.kortex.watch.Pseudofilesystem
import com.fromwau.kortex.watch.WatchError
import com.fromwau.kortex.watch.readTextEvery
import com.fromwau.kortex.watch.watchText
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path

/**
 * What kortex's `:watch` promises this bar, checked against the running kernel rather than a fake.
 *
 * These are the promises every widget here is built on, and the bar is wrong in a way no compiler catches
 * if one of them stops holding: that procfs needs the interval overload, that the interval overload emits
 * nothing for an unchanged file, and that a missing file is a value rather than the end of the flow.
 */
class WatchContractTest {
    private val scratch = Files.createTempDirectory("kortex-bar-qa")

    @AfterTest
    fun removeScratch() {
        scratch.toFile().deleteRecursively()
    }

    @Test
    fun `procfs on the interval overload reads again and again`() = runBlocking {
        val readings = withTimeout(TIMEOUT) {
            Path("/proc/stat").readTextEvery(100.milliseconds).take(2).toList()
        }

        val times = readings.map { reading -> reading.assertSuccess() }.map(::parseCpuTimes)
        val earlier = times.first().assertSuccess()
        val later = times.last().assertSuccess()
        assertTrue(later.total > earlier.total, "the jiffy counters did not move: $earlier then $later")
    }

    @Test
    fun `procfs without an interval is refused, naming the filesystem, rather than never updating`() =
        runBlocking {
            val first = withTimeout(TIMEOUT) { Path("/proc/meminfo").watchText().first() }

            val refusal = first.assertError<WatchError.Unwatchable>()
            assertEquals(Pseudofilesystem.Proc, refusal.filesystem)
        }

    @Test
    fun `sysfs without an interval is refused the same way`() = runBlocking {
        val sensor = findCpuSensor()
        if (sensor !is Ok) return@runBlocking

        val first = withTimeout(TIMEOUT) { sensor.value.input.watchText().first() }

        val refusal = first.assertError<WatchError.Unwatchable>()
        assertEquals(Pseudofilesystem.Sysfs, refusal.filesystem)
    }

    @Test
    fun `a refusal is the last value and the flow ends behind it`() = runBlocking {
        val everything = withTimeout(TIMEOUT) { Path("/proc/meminfo").watchText().toList() }

        assertEquals(1, everything.size, "expected the refusal alone, got $everything")
    }

    @Test
    fun `an unchanged file emits nothing on the second read, so a widget needs no distinctUntilChanged`() =
        runBlocking {
            val file = scratch.resolve("steady").also { it.toFile().writeText("one") }

            val readings = mutableListOf<Result<String, WatchError>>()
            runCatching {
                withTimeout(500) {
                    Path(file.toString()).readTextEvery(50.milliseconds).toList(readings)
                }
            }

            assertEquals(listOf("one"), readings.map { reading -> reading.assertSuccess() })
        }

    @Test
    fun `a file that is not there is a value, not the end of the flow, and the contents arrive when it appears`() =
        runBlocking {
            val file = scratch.resolve("late")

            val collected = mutableListOf<Result<String, WatchError>>()
            withTimeout(TIMEOUT) {
                coroutineScope {
                    val reading = launch {
                        Path(file.toString()).readTextEvery(50.milliseconds).take(2).toList(collected)
                    }
                    delay(150)
                    file.toFile().writeText("here now")
                    reading.join()
                }
            }
            val readings = collected

            val missing = readings.first().assertError<WatchError.Unreadable>()
            assertIs<FileError.NotFound>(missing.cause)
            assertEquals("here now", readings.last().assertSuccess())
        }

    @Test
    fun `maxBytes turns an oversized file into kern's own TooLarge`() {
        runBlocking {
            val first = withTimeout(TIMEOUT) {
                Path("/proc/meminfo").readTextEvery(100.milliseconds, maxBytes = 8).first()
            }

            val failure = first.assertError<WatchError.Unreadable>()
            assertIs<FileError.TooLarge>(failure.cause)
        }
    }
}

private val TIMEOUT = 5.seconds
