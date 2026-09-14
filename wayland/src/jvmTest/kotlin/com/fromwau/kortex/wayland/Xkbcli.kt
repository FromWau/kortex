package com.fromwau.kortex.wayland

import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.readText
import kotlin.test.fail

/** Compiles keymaps with `xkbcli`, so a test can type under layouts the compositor's keyboard does not use. */
internal object Xkbcli {
    /** The full text of the keymap for [layouts], such as `us,ru`; a missing or failing `xkbcli` fails the test. */
    fun compileKeymap(layouts: String): String {
        // Files rather than pipes: a keymap outgrows a pipe's buffer, and only a file lets the wait be bounded.
        val keymap = createTempFile("kortex-keymap", ".xkb")
        val errors = createTempFile("kortex-keymap", ".err")
        try {
            val process = start(layouts, keymap, errors)
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                fail("xkbcli compile-keymap --layout $layouts did not finish within $TIMEOUT_SECONDS s")
            }
            val exit = process.exitValue()
            if (exit != 0) fail("xkbcli compile-keymap --layout $layouts exited $exit: ${errors.readText()}")
            return keymap.readText()
        } finally {
            keymap.deleteIfExists()
            errors.deleteIfExists()
        }
    }

    private fun start(layouts: String, keymap: Path, errors: Path): Process = try {
        // Named rather than left to xkbcli's defaults, so the keymap is the same on every machine.
        ProcessBuilder("xkbcli", "compile-keymap", "--rules", "evdev", "--model", "pc105", "--layout", layouts)
            .redirectOutput(keymap.toFile())
            .redirectError(errors.toFile())
            .start()
    } catch (missing: IOException) {
        fail("xkbcli, which compiles this test's keymaps, could not be run: ${missing.message}")
    }

    private const val TIMEOUT_SECONDS = 10L
}
