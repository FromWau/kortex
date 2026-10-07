package com.fromwau.kortex.dbus

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists

/**
 * A `dbus-daemon` of a test's own, on a socket nobody else uses, which the test can kill and start again.
 *
 * It is the real daemon rather than a fake, because a fake bus agrees with whatever kortex gets wrong. It
 * runs with the session configuration and binds the same socket path every time it starts, so a connection
 * opened after [restart] reaches the new daemon at the address the old one had.
 */
public class PrivateBus : AutoCloseable {
    private val folder: Path = Files.createTempDirectory("kortex-bus")

    /** The socket a connection opens, the same across restarts. */
    public val socket: String = folder.resolve("bus").toString()

    private var daemon: Process = start()

    /** Ends the daemon the way a crash does: every connection to it loses its socket at once. */
    public fun kill() {
        daemon.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
        Path.of(socket).deleteIfExists()
    }

    public fun restart() {
        kill()
        daemon = start()
    }

    override fun close() {
        kill()
        folder.toFile().deleteRecursively()
    }

    private fun start(): Process {
        val process = ProcessBuilder(
            "dbus-daemon",
            "--session",
            "--nofork",
            "--nopidfile",
            "--address=unix:path=$socket",
            "--print-address",
        ).redirectErrorStream(true).start()
        // It prints its address once it is listening, which is the moment a connection can be opened.
        val address = process.inputReader().readLine()
        check(address != null && Path.of(socket).exists()) {
            "dbus-daemon did not start: ${process.inputReader().readText()}"
        }
        return process
    }
}
