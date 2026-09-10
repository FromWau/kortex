package com.fromwau.kortex.wayland

import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Reads the wl_output.release requests `ReleaseOutputProbe`'s child JVM sends on the wire; nothing
 * in-process shows a request leaving the client (see WAYLAND_DEBUG in the branch's verified facts).
 */
class OutputReleaseWireTest {
    @Test
    fun `every bound output is released, the hotplugged one on removal and the rest on shell close`() {
        val outputsBefore = Hyprctl.monitorNames()
        try {
            val (exitCode, stderr) = runProbe()
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

            val boundBeforeFirstMarker = boundIds(stderr.subList(0, boundAt))
            val betweenFirstAndSecond = stderr.subList(boundAt + 1, hotplugDoneAt)
            val betweenSecondAndThird = stderr.subList(hotplugDoneAt + 1, shellClosedAt)

            assertTrue(
                boundBeforeFirstMarker.isNotEmpty(),
                "no wl_output was bound before the first marker; stderr:\n$raw",
            )

            val hotplugBound = boundIds(betweenFirstAndSecond)
            assertEquals(
                1, hotplugBound.size,
                "expected exactly one wl_output bind between the first and second markers, got $hotplugBound; " +
                    "stderr:\n$raw",
            )

            val hotplugReleased = releasedIds(betweenFirstAndSecond)
            assertEquals(
                hotplugBound, hotplugReleased,
                "the hotplugged output was not released between the first and second markers; stderr:\n$raw",
            )

            val releasedOnClose = releasedIds(betweenSecondAndThird)
            assertEquals(
                boundBeforeFirstMarker, releasedOnClose,
                "not every output bound before the first marker was released on shell close; stderr:\n$raw",
            )
        } finally {
            // A killed child never runs its own finally, so the headless output it made can survive it.
            (Hyprctl.monitorNames() - outputsBefore).forEach(Hyprctl::removeHeadlessOutput)
        }
    }

    private fun runProbe(): Pair<Int, List<String>> {
        val javaExecutable = ProcessHandle.current().info().command()
            .orElseThrow { IllegalStateException("could not resolve the running JVM's own java executable") }
        val classpath = System.getProperty("java.class.path")

        val builder = ProcessBuilder(
            javaExecutable, "--enable-native-access=ALL-UNNAMED", "-cp", classpath, PROBE_MAIN_CLASS,
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

        return process.exitValue() to stderr.toList()
    }

    private fun boundIds(lines: List<String>): Set<Int> =
        lines.mapNotNull { NEW_OUTPUT_ID.find(it)?.groupValues?.get(1)?.toInt() }.toSet()

    private fun releasedIds(lines: List<String>): Set<Int> =
        lines.mapNotNull { RELEASE_LINE.find(it)?.groupValues?.get(1)?.toInt() }.toSet()

    private companion object {
        const val PROBE_MAIN_CLASS = "com.fromwau.kortex.wayland.ReleaseOutputProbeKt"
        const val PROBE_TIMEOUT_SECONDS = 10L
        const val READER_JOIN_TIMEOUT_MILLIS = 2_000L

        // wl_registry.bind's new_id carries no static interface, so wl_closure_print names it
        // "[unknown]" rather than "wl_output"; the interface argument just ahead of it is what pins this.
        val NEW_OUTPUT_ID = Regex("""wl_registry#\d+\.bind\(\d+, "wl_output", \d+, new id \[unknown]#(\d+)\)""")
        val RELEASE_LINE = Regex("""-> wl_output#(\d+)\.release\(\)""")
    }
}
