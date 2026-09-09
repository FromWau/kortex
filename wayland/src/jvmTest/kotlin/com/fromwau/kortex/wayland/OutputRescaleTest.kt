package com.fromwau.kortex.wayland

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.getOrElse
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A client cannot make a real compositor send `wl_surface.preferred_buffer_scale`, so this drives
 * [KortexBar.scaleOverride] instead. It proves the reaction is right — rebuilt buffers, `scene.size`,
 * `scene.density` — but not that a real event reaches that seam.
 */
class OutputRescaleTest {
    @Test
    fun `an observed scale change resizes the buffers and updates the density`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use {
            val bar = KortexBar.create(display, CONFIG)
                .getOrElse { error -> fail("bar creation failed: $error") }

            bar.use {
                bar.setContent { Box(Modifier.fillMaxSize().background(Color.Red)) }
                bar.pump(timeoutMillis = PUMP_MILLIS)

                val initialScale = bar.currentBufferScale
                val logicalWidth = bar.bufferSize.width / initialScale
                val logicalHeight = bar.bufferSize.height / initialScale
                val newScale = if (initialScale == 1) 2 else 1

                bar.scaleOverride = newScale
                val rescaled = bar.pump(timeoutMillis = PUMP_MILLIS) { bar.currentBufferScale == newScale }
                assertTrue(rescaled, "the observed scale change never reached bufferScale")

                assertEquals(
                    0, bar.bufferSize.width % newScale, "buffer width is not an exact multiple of the new scale",
                )
                assertEquals(
                    0, bar.bufferSize.height % newScale, "buffer height is not an exact multiple of the new scale",
                )
                assertEquals(
                    logicalWidth * newScale, bar.bufferSize.width,
                    "buffer width did not follow logicalWidth * newScale",
                )
                assertEquals(
                    logicalHeight * newScale, bar.bufferSize.height,
                    "buffer height did not follow logicalHeight * newScale",
                )
                assertEquals(
                    Density(newScale.toFloat()), bar.density,
                    "the composition's density is still the one computed at startup",
                )
            }
        }
    }

    /**
     * Wayland keeps reporting surface-local (logical) coordinates across a rescale, so a pointer whose
     * scale stayed behind delivers a plausible position that is simply in the wrong place — which no
     * assertion on buffers or density can see.
     */
    @Test
    fun `a pointer event after a scale change lands at the new scale's scene position`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use { wayland ->
            val manager = VirtualPointerManager.bind(wayland)
                .getOrElse { error -> fail("virtual pointer manager bind failed: $error") }
            val monitor = assertNotNull(Hyprctl.monitors().firstOrNull(), "hyprctl monitors reported no monitor")
            val seen = AtomicReference(Offset.Unspecified)

            KortexBar.create(wayland, CONFIG)
                .getOrElse { error -> fail("bar creation failed: $error") }
                .use { bar ->
                    bar.setContent {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(Color.Red)
                                .pointerInput(Unit) {
                                    awaitPointerEventScope {
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            // wl_pointer.enter carries the position of a cursor that
                                            // arrives from outside; no motion follows it.
                                            if (event.type !in POSITIONED) continue
                                            seen.set(event.changes.first().position)
                                        }
                                    }
                                },
                        )
                    }
                    bar.pump(timeoutMillis = PUMP_MILLIS)

                    val newScale = if (bar.currentBufferScale == 1) 2 else 1
                    bar.scaleOverride = newScale
                    assertTrue(
                        bar.pump(timeoutMillis = PUMP_MILLIS) { bar.currentBufferScale == newScale },
                        "the observed scale change never reached bufferScale",
                    )

                    val geometry = assertNotNull(Screen.geometry(NAMESPACE), "hyprctl layers did not report $NAMESPACE")
                    manager.createVirtualPointer().use { pointer ->
                        fun moveTo(x: Int, y: Int) {
                            // Screen.geometry and the virtual pointer's absolute space agree only
                            // because this suite runs against a single output pinned at the origin.
                            pointer.motionAbsolute(x, y, monitor.logicalWidth, monitor.logicalHeight)
                            pointer.frame()
                            wayland.roundtrip()
                            bar.pump(timeoutMillis = SETTLE_MILLIS)
                        }

                        // Off the bar first: the compositor re-evaluates pointer focus on motion, so a
                        // cursor already parked on these coordinates would never enter the new surface.
                        moveTo(monitor.logicalWidth / 2, monitor.logicalHeight / 2)
                        moveTo(geometry.x + PROBE_LOGICAL_X, geometry.y + PROBE_LOGICAL_Y)
                        val delivered = bar.pump(timeoutMillis = PUMP_MILLIS) { seen.get().isSpecified }
                        // Park it off the bar again: the screenshot tests sample the pixel it sits on.
                        moveTo(monitor.logicalWidth / 2, monitor.logicalHeight / 2)

                        assertNull(wayland.protocolError(), "the connection reported a protocol error")
                        assertTrue(delivered, "no pointer motion over the bar reached the composition")
                    }

                    val expected =
                        Offset((PROBE_LOGICAL_X * newScale).toFloat(), (PROBE_LOGICAL_Y * newScale).toFloat())
                    val observed = seen.get()
                    assertTrue(
                        abs(observed.x - expected.x) <= POSITION_TOLERANCE_PX &&
                            abs(observed.y - expected.y) <= POSITION_TOLERANCE_PX,
                        "surface-local $PROBE_LOGICAL_X,$PROBE_LOGICAL_Y at scale $newScale must reach the scene " +
                            "at $expected, not $observed",
                    )
                }
        }
    }

    private companion object {
        const val NAMESPACE = "kortex"
        const val BAR_HEIGHT = 32
        const val PUMP_MILLIS = 1500L
        const val SETTLE_MILLIS = 500L

        val POSITIONED = setOf(PointerEventType.Enter, PointerEventType.Move)

        // Inside the bar on both axes, and far enough from the origin that the same point at the old
        // scale lands somewhere else entirely.
        const val PROBE_LOGICAL_X = 40
        const val PROBE_LOGICAL_Y = 8

        /** wl_fixed_t rounding on the way through the compositor, not a scale's worth of slack. */
        const val POSITION_TOLERANCE_PX = 1f

        val CONFIG = SurfaceConfig(
            namespace = NAMESPACE,
            height = BAR_HEIGHT.dp,
            exclusiveZone = ExclusiveZone.Reserve(BAR_HEIGHT.dp),
        )
    }
}
