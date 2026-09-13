package com.fromwau.kortex.wayland

import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue

/** How a child-JVM probe ended: its exit code and every line it printed, in the order it printed them. */
internal data class ProbeResult(val exitCode: Int, val output: List<String>)

/**
 * Runs [mainClass] in a child JVM, on this JVM's own test classpath with native access enabled, so a throw
 * escaping a libwayland callback ends that JVM instead of the one running the tests.
 *
 * @param whileRunning run once the process has started, before waiting for it to exit; a test that must
 *   act on the probe while it is still alive, e.g. clicking a surface it placed, does so here.
 */
internal fun runProbe(mainClass: String, whileRunning: () -> Unit = {}): ProbeResult {
    val javaExecutable = ProcessHandle
        .current()
        .info()
        .command()
        .orElseThrow { IllegalStateException("could not resolve the running JVM's own java executable") }
    val process = ProcessBuilder(
        javaExecutable, "--enable-native-access=ALL-UNNAMED", "-cp", System.getProperty("java.class.path"),
        mainClass,
    ).redirectErrorStream(true).start()
    val output = Collections.synchronizedList(mutableListOf<String>())
    val reader = Thread { process.inputStream.bufferedReader().forEachLine { output += it } }
    reader.start()

    var finished = false
    try {
        whileRunning()
        finished = process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    } finally {
        if (!finished) process.destroyForcibly().waitFor()
        reader.join(READER_JOIN_MILLIS)
    }
    assertTrue(
        finished,
        "the probe did not exit within ${PROBE_TIMEOUT_SECONDS}s; output:\n${output.joinToString("\n")}",
    )
    return ProbeResult(process.exitValue(), output.toList())
}

private const val PROBE_TIMEOUT_SECONDS = 20L
private const val READER_JOIN_MILLIS = 2_000L
