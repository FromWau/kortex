package com.fromwau.kortex.wayland

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.getOrElse
import com.fromwau.kortex.compose.LocalKortexSurface
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A read of a surface's own `monitor.geometry` or `LocalKortexSurface.current.size` at first composition also
 * passes with a plain field behind the value. These drive each value's writer after content has already
 * composed once, and prove the same reader recomposes with the change.
 */
class RecompositionTest {
    @Test
    fun `content reading its surface's monitor geometry recomposes when the output republishes it`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }
        val composed = CopyOnWriteArrayList<OutputGeometry>()

        display.use { wayland ->
            val content: @Composable KortexApplicationScope.() -> Unit = {
                val monitors by rememberMonitors()
                monitors.firstOrNull()?.let { monitor ->
                    TestSurface(NAMESPACE, monitor = monitor) {
                        val geometry = monitor.geometry
                        SideEffect { composed += geometry }
                        Box(Modifier.fillMaxSize())
                    }
                }
            }
            val shell = KortexShell.createApplicationOrFail(wayland, content)

            shell.useOrFail {
                val sawRealGeometry = shell.pumpOrFail(PUMP_TIMEOUT_MILLIS) { composed.isNotEmpty() }
                assertTrue(sawRealGeometry, "content never composed with the output's real geometry")

                val monitor = assertNotNull(
                    shell.monitors.value.firstOrNull(),
                    "the application listed no monitor to fabricate an event group for",
                )
                val listener = monitor.output.listener

                // Stands in for a compositor re-send, straight into the listener: nothing here reaches the
                // wire, so no real wl_output is added, removed or changed.
                val strings = Arena.ofAuto()
                listener.onGeometry(
                    NONE, NONE, X, Y, 0, 0, 0, strings.allocateFrom("make"), strings.allocateFrom("model"), TRANSFORM,
                )
                listener.onMode(NONE, NONE, flags = MODE_CURRENT, width = WIDTH, height = HEIGHT, refresh = 0)
                listener.onScale(NONE, NONE, factor = SCALE)
                listener.onName(NONE, NONE, strings.allocateFrom(NAME))
                listener.onDescription(NONE, NONE, strings.allocateFrom(DESCRIPTION))
                listener.onDone(NONE, NONE)

                val recomposed = shell.pumpOrFail(PUMP_TIMEOUT_MILLIS) { composed.any { it.name == NAME } }
                assertTrue(recomposed, "content never recomposed after the output republished its geometry")
            }
        }
    }

    @Test
    fun `content reading LocalKortexSurface size recomposes when requestSize changes it`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }
        val composed = CopyOnWriteArrayList<IntSize>()

        display.use {
            onBareSurface(display, SURFACE_CONFIG) { surface, scene ->
                scene.setContent {
                    val size = LocalKortexSurface.current.size
                    SideEffect { composed += size }
                    Box(Modifier.fillMaxSize())
                }
                val composedOnce = surface.pumpOrFail(timeoutMillis = PUMP_TIMEOUT_MILLIS) { composed.isNotEmpty() }
                assertTrue(composedOnce, "content never composed at all")

                surface.requestSize(SPAN_ANCHORED_AXIS.dp, RESIZED_HEIGHT.dp)
                    .getOrElse { error -> fail("the resize was rejected before it reached the compositor: $error") }

                val recomposed = surface.pumpOrFail(timeoutMillis = PUMP_TIMEOUT_MILLIS) {
                    composed.any { it.height == RESIZED_HEIGHT }
                }
                assertTrue(recomposed, "content never recomposed after requestSize changed the handle's size")
            }
        }
    }

    private companion object {
        const val NAMESPACE = "kortex-recompose-output-test"
        const val SURFACE_NAMESPACE = "kortex-recompose-size-test"
        const val SURFACE_HEIGHT = 32
        const val RESIZED_HEIGHT = 64
        const val PUMP_TIMEOUT_MILLIS = 4000L

        const val MODE_CURRENT = 0x1
        const val X = 4321
        const val Y = 8765
        const val WIDTH = 6001
        const val HEIGHT = 3001
        const val SCALE = 5
        const val TRANSFORM = 2
        const val NAME = "SYNTH-RECOMPOSE-1"
        const val DESCRIPTION = "Synthetic output for the recomposition test"
        val NONE: MemorySegment = MemorySegment.NULL

        val SURFACE_CONFIG = SurfaceConfig
            .panel(edge = Edge.Top, thickness = SURFACE_HEIGHT.dp)
            .copy(namespace = SURFACE_NAMESPACE)
    }
}
