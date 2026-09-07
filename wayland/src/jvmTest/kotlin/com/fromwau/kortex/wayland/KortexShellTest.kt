package com.fromwau.kortex.wayland

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.getOrElse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Hotplugs an output under a live [KortexShell], to prove it keeps exactly one bar per output, on
 * distinct outputs, and that tearing one bar down leaves its sibling rendering.
 */
class KortexShellTest {
    @Test
    fun `one bar per output, created and destroyed as outputs come and go`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use { wayland ->
            val shell = KortexShell.create(wayland, namespace = NAMESPACE, height = BAR_HEIGHT.dp) {
                Box(Modifier.fillMaxSize().background(Color(GREY, GREY, GREY)))
            }.getOrElse { error -> fail("shell creation failed: $error") }

            shell.use {
                assertEquals(1, shell.activeBars.size, "expected exactly one bar before any hotplug")

                var pending: String? = null
                try {
                    val outputName = Hyprctl.createHeadlessOutput()
                    pending = outputName

                    val grew = shell.pump(PUMP_TIMEOUT_MILLIS) { shell.activeBars.size == 2 }
                    assertTrue(grew, "shell never grew a second bar after hyprctl output create headless")

                    val namespaces = awaitKortexLayerCount(2)
                    assertEquals(2, namespaces.size, "expected two distinct kortex layer namespaces, got $namespaces")
                    val monitors = namespaces.map(::monitorForNamespace)
                    assertTrue(monitors.all { it != null }, "one of the bars never mapped to a monitor: $monitors")
                    assertNotEquals(monitors[0], monitors[1], "both bars ended up reported on the same output")

                    Hyprctl.removeHeadlessOutput(outputName)
                    pending = null

                    val shrank = shell.pump(PUMP_TIMEOUT_MILLIS) { shell.activeBars.size == 1 }
                    assertTrue(shrank, "shell never dropped back to one bar after hyprctl output remove")

                    // A torn-down sibling must not take the surviving bar with it.
                    val remaining = awaitKortexLayerCount(1)
                    assertEquals(1, remaining.size, "expected exactly one kortex layer namespace, got $remaining")
                    assertRendersGrey(remaining.single())
                } finally {
                    // Guarantees the virtual output never survives a failed assertion above.
                    pending?.let(Hyprctl::removeHeadlessOutput)
                }
            }
        }
    }

    private fun assertRendersGrey(namespace: String) {
        val geometry = assertNotNull(Screen.geometry(namespace), "hyprctl did not report $namespace")
        val pixel = Screen.settledPixel(geometry)
        assertEquals(MID_GREY, pixel, "the surviving bar at $namespace is not showing the composed colour")
    }

    /**
     * Polls `hyprctl layers -j` until it reports [count] kortex namespaces or [timeoutMillis] elapses.
     *
     * A bar counted in [KortexShell.activeBars] can still be a commit or two from appearing in
     * Hyprland's own layer list.
     */
    private fun awaitKortexLayerCount(count: Int, timeoutMillis: Long = HYPRCTL_SETTLE_MILLIS): List<String> {
        val deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLI
        var namespaces = kortexLayerNamespaces()
        while (namespaces.size != count && System.nanoTime() < deadline) {
            Thread.sleep(HYPRCTL_POLL_MILLIS)
            namespaces = kortexLayerNamespaces()
        }
        return namespaces
    }

    /** Every namespace on screen that KortexShell could have produced, i.e. "$NAMESPACE-<output name>". */
    private fun kortexLayerNamespaces(): List<String> =
        Regex("\"namespace\": \"($NAMESPACE-[^\"]*)\"").findAll(Hyprctl.run("layers", "-j"))
            .map { it.groupValues[1] }
            .toList()

    /** Which Hyprland monitor reported a layer with [namespace], by slicing hyprctl's per-monitor JSON blocks. */
    private fun monitorForNamespace(namespace: String): String? {
        val json = Hyprctl.run("layers", "-j")
        val headers = MONITOR_HEADER.findAll(json).toList()
        for (i in headers.indices) {
            val start = headers[i].range.last
            val end = if (i + 1 < headers.size) headers[i + 1].range.first else json.length
            if ("\"namespace\": \"$namespace\"" in json.substring(start, end)) return headers[i].groupValues[1]
        }
        return null
    }

    private companion object {
        val MONITOR_HEADER = Regex("\"([^\"]+)\":\\s*\\{\\s*\"levels\"")
        const val NAMESPACE = "kortex"
        const val BAR_HEIGHT = 32
        const val GREY = 128
        const val MID_GREY = 0xFF808080.toInt()
        const val PUMP_TIMEOUT_MILLIS = 4000L
        const val HYPRCTL_SETTLE_MILLIS = 2000L
        const val HYPRCTL_POLL_MILLIS = 100L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
