package com.fromwau.kortex.wayland

import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Reads the wl_output.release requests `ReleaseOutputProbe`'s child JVM sends on the wire, since nothing
 * in-process shows a request leaving the client. The probe runs in a child because libwayland reads
 * `WAYLAND_DEBUG` once per process and never clears it, which would log every later connection in this JVM.
 */
class OutputReleaseWireTest {
    @Test
    fun `every bound output is released on shell close`() {
        val (exitCode, stderr) = runProbe(ProbeMode.ShellCloseOnly)
        val raw = stderr.joinToString("\n")
        assertEquals(0, exitCode, "probe exited $exitCode; stderr:\n$raw")

        val boundAt = stderr.indexOf(PROBE_MARKER_BOUND)
        val shellClosedAt = stderr.indexOf(PROBE_MARKER_SHELL_CLOSED)
        assertTrue(
            boundAt >= 0 && shellClosedAt > boundAt,
            "markers missing or out of order (bound=$boundAt closed=$shellClosedAt); stderr:\n$raw",
        )

        val boundBeforeFirstMarker = outputIds(stderr.subList(0, boundAt), NEW_OUTPUT_ID)
        assertTrue(
            boundBeforeFirstMarker.isNotEmpty(),
            "no wl_output was bound before the first marker; stderr:\n$raw",
        )

        val releasedOnClose = outputIds(stderr.subList(boundAt + 1, shellClosedAt), RELEASE_LINE)
        assertEquals(
            boundBeforeFirstMarker, releasedOnClose,
            "not every output bound before the first marker was released on shell close; stderr:\n$raw",
        )
    }

    @Hotplug
    @Test
    fun `every bound output is released, the hotplugged one on removal and the rest on shell close`() {
        val outputsBefore = Hyprctl.monitorNames()
        try {
            val (exitCode, stderr) = runProbe(ProbeMode.HotplugThenShellClose)
            val raw = stderr.joinToString("\n")
            assertEquals(0, exitCode, "probe exited $exitCode; stderr:\n$raw")

            val boundAt = stderr.indexOf(PROBE_MARKER_BOUND)
            val hotplugDoneAt = stderr.indexOf(PROBE_MARKER_HOTPLUG_DONE)
            val shellClosedAt = stderr.indexOf(PROBE_MARKER_SHELL_CLOSED)
            assertTrue(
                boundAt >= 0 && hotplugDoneAt > boundAt && shellClosedAt > hotplugDoneAt,
                "markers missing or out of order (bound=$boundAt hotplug=$hotplugDoneAt closed=$shellClosedAt); " +
                    "stderr:\n$raw",
            )

            val boundBeforeFirstMarker = outputIds(stderr.subList(0, boundAt), NEW_OUTPUT_ID)
            val betweenFirstAndSecond = stderr.subList(boundAt + 1, hotplugDoneAt)
            val betweenSecondAndThird = stderr.subList(hotplugDoneAt + 1, shellClosedAt)

            assertTrue(
                boundBeforeFirstMarker.isNotEmpty(),
                "no wl_output was bound before the first marker; stderr:\n$raw",
            )

            val hotplugBound = outputIds(betweenFirstAndSecond, NEW_OUTPUT_ID)
            assertEquals(
                1, hotplugBound.size,
                "expected exactly one wl_output bind between the first and second markers, got $hotplugBound; " +
                    "stderr:\n$raw",
            )

            val hotplugReleased = outputIds(betweenFirstAndSecond, RELEASE_LINE)
            assertEquals(
                hotplugBound, hotplugReleased,
                "the hotplugged output was not released between the first and second markers; stderr:\n$raw",
            )

            val releasedOnClose = outputIds(betweenSecondAndThird, RELEASE_LINE)
            assertEquals(
                boundBeforeFirstMarker, releasedOnClose,
                "not every output bound before the first marker was released on shell close; stderr:\n$raw",
            )
        } finally {
            // A killed child never runs its own finally, so the headless output it made can survive it.
            // hyprctl refuses to remove a real monitor, and one may have connected meanwhile.
            (Hyprctl.monitorNames() - outputsBefore)
                .filter { it.startsWith(HEADLESS_PREFIX) }
                .forEach(Hyprctl::removeHeadlessOutput)
        }
    }

    private fun runProbe(mode: ProbeMode): Pair<Int, List<String>> {
        val javaExecutable = ProcessHandle
            .current()
            .info()
            .command()
            .orElseThrow { IllegalStateException("could not resolve the running JVM's own java executable") }
        val classpath = System.getProperty("java.class.path")

        val builder = ProcessBuilder(
            javaExecutable, "--enable-native-access=ALL-UNNAMED", "-cp", classpath, PROBE_MAIN_CLASS, mode.name,
        )
        builder.environment()["WAYLAND_DEBUG"] = "client"
        builder.environment()["NO_COLOR"] = "1"
        builder.environment().remove("FORCE_COLOR")
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD)

        val process = builder.start()
        val stderr = mutableListOf<String>()
        val reader = Thread { process.errorStream.bufferedReader().forEachLine { stderr += it } }
        reader.start()

        val finished = process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        reader.join(READER_JOIN_TIMEOUT_MILLIS)
        assertTrue(finished, "probe child did not exit within ${PROBE_TIMEOUT_SECONDS}s and was killed")
        // Only a reader seen to have terminated has its appends to stderr ordered before the read below.
        assertFalse(
            reader.isAlive,
            "the probe's stderr reader was still running ${READER_JOIN_TIMEOUT_MILLIS}ms after the probe exited",
        )

        return process.exitValue() to stderr.toList()
    }

    private fun outputIds(
        lines: List<String>,
        pattern: Regex,
    ): Set<Int> = lines
        .mapNotNull { pattern.find(it) }
        .map { it.groupValues[1].toInt() }
        .toSet()

    private companion object {
        const val PROBE_MAIN_CLASS = "com.fromwau.kortex.wayland.ReleaseOutputProbeKt"
        const val PROBE_TIMEOUT_SECONDS = 10L
        const val READER_JOIN_TIMEOUT_MILLIS = 2_000L
        const val HEADLESS_PREFIX = "HEADLESS-"

        // wl_registry.bind's new_id carries no static interface, so wl_closure_print names it
        // "[unknown]" rather than "wl_output"; the interface argument just ahead of it is what pins this.
        val NEW_OUTPUT_ID = Regex("""wl_registry#\d+\.bind\(\d+, "wl_output", \d+, new id \[unknown]#(\d+)\)""")
        val RELEASE_LINE = Regex("""-> wl_output#(\d+)\.release\(\)""")
    }
}
