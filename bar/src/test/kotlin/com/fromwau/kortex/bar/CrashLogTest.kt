package com.fromwau.kortex.bar

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.getOrElse
import com.fromwau.kortex.compose.ContentFailure
import com.fromwau.kortex.wayland.KortexError
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

class CrashLogTest {
    @Test
    fun `the crash log sits under XDG_STATE_HOME when it is set`() {
        val env = mapOf("XDG_STATE_HOME" to "/custom/state", "HOME" to "/home/whoever")

        assertEquals(Path.of("/custom/state/kortex-bar/crash.log"), crashLogPath(env))
    }

    @Test
    fun `the crash log falls back to HOME's state dir when XDG_STATE_HOME is unset`() {
        val env = mapOf("HOME" to "/home/whoever")

        assertEquals(Path.of("/home/whoever/.local/state/kortex-bar/crash.log"), crashLogPath(env))
    }

    @Test
    fun `the crash log falls back to HOME's state dir when XDG_STATE_HOME is empty`() {
        val env = mapOf("XDG_STATE_HOME" to "", "HOME" to "/home/whoever")

        assertEquals(Path.of("/home/whoever/.local/state/kortex-bar/crash.log"), crashLogPath(env))
    }

    @Test
    fun `the crash log falls back to HOME's state dir when XDG_STATE_HOME is relative`() {
        val env = mapOf("XDG_STATE_HOME" to "relative/state", "HOME" to "/home/whoever")

        assertEquals(Path.of("/home/whoever/.local/state/kortex-bar/crash.log"), crashLogPath(env))
    }

    @Test
    fun `appending two crashes keeps both, each with its own stack trace`() {
        val dir = Files.createTempDirectory("kortex-bar-test")
        try {
            val path = dir.resolve("crash.log")
            val first = KortexError.SurfaceCrashed("bar", ContentFailure.Composition(RuntimeException("first boom")))
            val second = KortexError.SurfaceCrashed("menu", ContentFailure.KeyInput(RuntimeException("second boom")))

            appendCrash(path, first).getOrElse { failure -> fail("appendCrash failed unexpectedly: $failure") }
            appendCrash(path, second).getOrElse { failure -> fail("appendCrash failed unexpectedly: $failure") }

            val log = Files.readString(path)
            val headers = log.lineSequence().mapNotNull { CRASH_HEADER.matchEntire(it) }.toList()

            assertEquals(2, headers.size, "expected one ISO-8601-headed entry per crash; log:\n$log")
            assertEquals("bar", headers[0].groups["surface"]?.value)
            assertEquals("Composition", headers[0].groups["kind"]?.value)
            assertTrue("first boom" in log)
            assertEquals("menu", headers[1].groups["surface"]?.value)
            assertEquals("KeyInput", headers[1].groups["kind"]?.value)
            assertTrue("second boom" in log)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `appending a crash creates missing parent directories`() {
        val dir = Files.createTempDirectory("kortex-bar-test")
        try {
            val path = dir.resolve("nested/sub/crash.log")
            val crash = KortexError.SurfaceCrashed("bar", ContentFailure.PointerInput(RuntimeException("boom")))

            appendCrash(path, crash).getOrElse { failure -> fail("appendCrash failed unexpectedly: $failure") }

            assertTrue(Files.exists(path))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `appending a crash to a path that is a directory returns an Err instead of throwing`() {
        val dir = Files.createTempDirectory("kortex-bar-test")
        try {
            val crash = KortexError.SurfaceCrashed("bar", ContentFailure.Composition(RuntimeException("boom")))

            val result = appendCrash(dir, crash)

            assertIs<Err<CrashLogWriteFailed>>(result)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private companion object {
        // The header crashLogEntry writes for each crash: an ISO-8601 instant, the surface name, then the kind.
        val CRASH_HEADER = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z (?<surface>\S+) (?<kind>\w+)""")
    }
}
