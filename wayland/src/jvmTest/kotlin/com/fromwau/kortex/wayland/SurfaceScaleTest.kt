package com.fromwau.kortex.wayland

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.getOrElse
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A bar's scale must match the monitor its own surface landed on, which is what makes a mixed-DPI
 * setup render correctly.
 *
 * Hyprland answers `get_layer_surface` with `preferred_buffer_scale`, so the value is in before the
 * first configure; nothing here has to wait for a rescale.
 */
class SurfaceScaleTest {
    @Test
    fun `the bar renders at the buffer scale its own monitor asks for`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use {
            val bar = KortexSurface.create(display, CONFIG)
                .getOrElse { error -> fail("bar creation failed: $error") }

            bar.use {
                bar.setContent { Box(Modifier.fillMaxSize().background(Color.Red)) }
                bar.pump(timeoutMillis = PUMP_MILLIS)
                // The first commit may still be in flight; force it through before reading hyprctl.
                display.roundtrip()

                assertRendersAtItsMonitorScale(bar, NAMESPACE)
            }
        }
    }

    /**
     * The single-monitor leg above cannot fail on a uniformly scaled setup, so this hotplugs a second
     * output — Hyprland scales a headless one on its own — and pins each bar to its own monitor.
     */
    @Test
    fun `a second bar on a differently scaled output renders at that output's scale`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use { wayland ->
            val before = wayland.globals.count { it.interfaceName == WL_OUTPUT }
            val headless = Hyprctl.createHeadlessOutput()
            try {
                wayland.roundtrip()
                val outputs = wayland.globals.filter { it.interfaceName == WL_OUTPUT }
                assertTrue(outputs.size > before, "the headless output $headless never reached the registry")

                val bars = outputs.mapIndexed { index, global ->
                    val namespace = "$NAMESPACE-$index"
                    val output = wayland.bind(global, LibWayland.outputInterface, WlVersion.OUTPUT)
                    // A wl_output proxy with no listener crashes on its first event.
                    OutputListener().install(output)
                    val bar = KortexSurface.create(wayland, CONFIG.copy(namespace = namespace), output = output)
                        .getOrElse { error -> fail("bar creation failed on output ${global.name}: $error") }
                    namespace to bar
                }

                try {
                    bars.forEach { (_, bar) ->
                        bar.setContent { Box(Modifier.fillMaxSize().background(Color.Red)) }
                        bar.pump(timeoutMillis = PUMP_MILLIS)
                    }
                    wayland.roundtrip()
                    bars.forEach { (namespace, bar) -> assertRendersAtItsMonitorScale(bar, namespace) }
                    // Hyprland's own default is what makes the headless output a different scale; if
                    // that ever changed, every assertion above would still pass on a uniform setup.
                    assertTrue(
                        bars.distinctBy { (_, bar) -> bar.currentBufferScale }.size > 1,
                        "every bar came up at the same scale, so this leg no longer covers mixed DPI",
                    )
                } finally {
                    bars.forEach { (_, bar) -> bar.close() }
                }
            } finally {
                // Guarantees the virtual output never survives a failed assertion above.
                Hyprctl.removeHeadlessOutput(headless)
            }
        }
    }

    private fun assertRendersAtItsMonitorScale(bar: KortexSurface, namespace: String) {
        val geometry = assertNotNull(Screen.geometry(namespace), "hyprctl layers did not report $namespace")
        val monitor = geometry.monitor
        val reported = assertNotNull(
            Hyprctl.monitors().firstOrNull { it.name == monitor }?.scale,
            "hyprctl monitors reported no scale for $monitor",
        )
        // wl_surface's scale is an integer and Hyprland rounds a fraction up, so the buffer never
        // holds fewer pixels than the output asks for.
        val scale = ceil(reported).toInt()
        println("BAR $namespace ${bar.bufferSize} at $geometry on $monitor scale $reported")

        assertEquals(
            scale, bar.currentBufferScale,
            "$namespace is rendering at a scale $monitor never asked for; it reports $reported",
        )
        assertEquals(
            geometry.logicalWidth * scale, bar.bufferSize.width,
            "buffer width is not $namespace's logical width at $monitor's scale",
        )
        assertEquals(
            geometry.logicalHeight * scale, bar.bufferSize.height,
            "buffer height is not $namespace's logical height at $monitor's scale",
        )
    }

    private companion object {
        const val NAMESPACE = "kortex"
        const val WL_OUTPUT = "wl_output"
        const val BAR_HEIGHT = 32
        const val PUMP_MILLIS = 1500L

        val CONFIG = SurfaceConfig(
            namespace = NAMESPACE,
            height = BAR_HEIGHT.dp,
            exclusiveZone = ExclusiveZone.Reserve(BAR_HEIGHT.dp),
        )
    }
}
