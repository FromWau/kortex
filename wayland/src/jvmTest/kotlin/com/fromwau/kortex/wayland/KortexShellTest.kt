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
 * Hotplugs an output under a live [KortexShell], to prove a per-output spec keeps exactly one surface
 * per output, on distinct outputs, and that tearing one down leaves its sibling rendering.
 */
class KortexShellTest {
    @Test
    fun `one surface per output, created and destroyed as outputs come and go`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use { wayland ->
            val panel = SurfaceSpec(CONFIG) { Box(Modifier.fillMaxSize().background(Color(GREY, GREY, GREY))) }
            val shell = KortexShell.create(wayland, panel)
                .getOrElse { error -> fail("shell creation failed: $error") }

            shell.use {
                assertEquals(1, shell.activeSurfaces.size, "expected exactly one surface before any hotplug")

                var pending: String? = null
                try {
                    val outputName = Hyprctl.createHeadlessOutput()
                    pending = outputName

                    val grew = shell.pump(PUMP_TIMEOUT_MILLIS) { shell.activeSurfaces.size == 2 }
                    assertTrue(grew, "shell never grew a second surface after hyprctl output create headless")

                    val namespaces = awaitKortexLayerCount(2)
                    assertEquals(2, namespaces.size, "expected two distinct kortex layer namespaces, got $namespaces")
                    val monitors = namespaces.map { Screen.geometry(it)?.monitor }
                    assertTrue(monitors.all { it != null }, "one surface never mapped to a monitor: $monitors")
                    assertNotEquals(monitors[0], monitors[1], "both surfaces ended up reported on the same output")

                    Hyprctl.removeHeadlessOutput(outputName)
                    pending = null

                    val shrank = shell.pump(PUMP_TIMEOUT_MILLIS) { shell.activeSurfaces.size == 1 }
                    assertTrue(shrank, "shell never dropped back to one surface after hyprctl output remove")

                    // A torn-down sibling must not take the surviving surface with it.
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
        assertEquals(MID_GREY, pixel, "the surviving surface at $namespace is not showing the composed colour")
    }

    /**
     * Polls `hyprctl layers -j` until it reports [count] kortex namespaces or [timeoutMillis] elapses.
     *
     * A surface counted in [KortexShell.activeSurfaces] can still be a commit or two from appearing in
     * Hyprland's own layer list.
     */
    private fun awaitKortexLayerCount(count: Int, timeoutMillis: Long = HYPRCTL_SETTLE_MILLIS): Set<String> {
        val deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLI
        var namespaces = kortexLayerNamespaces()
        while (namespaces.size != count && System.nanoTime() < deadline) {
            Thread.sleep(HYPRCTL_POLL_MILLIS)
            namespaces = kortexLayerNamespaces()
        }
        return namespaces
    }

    /**
     * Every distinct namespace on screen that KortexShell could have produced, i.e.
     * "$NAMESPACE-<output name>" — distinct because a namespace mid-hotplug can transiently be
     * reported under two monitors, and that must count as one namespace, not two.
     */
    private fun kortexLayerNamespaces(): Set<String> =
        Hyprctl.layers().values
            .flatMap { it.levels.values.flatten() }
            .map { it.namespace }
            .filterTo(mutableSetOf()) { it.startsWith("$NAMESPACE-") }

    private companion object {
        const val NAMESPACE = "kortex"
        const val SURFACE_HEIGHT = 32
        const val GREY = 128
        const val MID_GREY = 0xFF808080.toInt()
        const val PUMP_TIMEOUT_MILLIS = 4000L
        const val HYPRCTL_SETTLE_MILLIS = 2000L
        const val HYPRCTL_POLL_MILLIS = 100L
        const val NANOS_PER_MILLI = 1_000_000L

        val CONFIG = SurfaceConfig.panel(edge = Edge.Top, thickness = SURFACE_HEIGHT.dp).copy(namespace = NAMESPACE)
    }
}
