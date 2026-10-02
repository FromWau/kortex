package com.fromwau.kortex.bar.system

import com.fromwau.kern.dirs.FileError
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
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

        val times = readings.map { reading -> assertIs<Ok<String>>(reading).value }.map(::parseCpuTimes)
        val earlier = assertIs<Ok<CpuTimes>>(times.first()).value
        val later = assertIs<Ok<CpuTimes>>(times.last()).value
        assertTrue(later.total > earlier.total, "the jiffy counters did not move: $earlier then $later")
    }

    @Test
    fun `procfs without an interval is refused, naming the filesystem, rather than never updating`() =
        runBlocking {
            val first = withTimeout(TIMEOUT) { Path("/proc/meminfo").watchText().first() }

            val refusal = assertIs<WatchError.Unwatchable>(assertIs<Err<WatchError>>(first).error)
            assertEquals(Pseudofilesystem.Proc, refusal.filesystem)
        }

    @Test
    fun `sysfs without an interval is refused the same way`() = runBlocking {
        val sensor = findCpuSensor()
        if (sensor !is Ok) return@runBlocking

        val first = withTimeout(TIMEOUT) { sensor.value.input.watchText().first() }

        val refusal = assertIs<WatchError.Unwatchable>(assertIs<Err<WatchError>>(first).error)
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

            assertEquals(listOf("one"), readings.map { reading -> assertIs<Ok<String>>(reading).value })
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

            val missing = assertIs<WatchError.Unreadable>(assertIs<Err<WatchError>>(readings.first()).error)
            assertIs<FileError.NotFound>(missing.cause)
            assertEquals("here now", assertIs<Ok<String>>(readings.last()).value)
        }

    @Test
    fun `maxBytes turns an oversized file into kern's own TooLarge`() {
        runBlocking {
            val first = withTimeout(TIMEOUT) {
                Path("/proc/meminfo").readTextEvery(100.milliseconds, maxBytes = 8).first()
            }

            val failure = assertIs<WatchError.Unreadable>(assertIs<Err<WatchError>>(first).error)
            assertIs<FileError.TooLarge>(failure.cause)
        }
    }
}

private val TIMEOUT = 5.seconds
