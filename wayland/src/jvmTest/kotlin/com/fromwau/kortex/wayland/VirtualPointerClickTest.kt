package com.fromwau.kortex.wayland

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.getOrElse
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Drives a click through the real path: a `zwlr_virtual_pointer_v1` synthesises the input, the
 * compositor turns it into `wl_pointer` events on this client's own seat, and [PointerInput] forwards
 * them into Compose. [InputDeliveryTest] starts at the listener seam; this covers the hop before it.
 */
class VirtualPointerClickTest {
    @Test
    fun `a virtual pointer click through the compositor reaches a composable`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use {
            val manager = VirtualPointerManager.bind(it)
                .getOrElse { error -> fail("virtual pointer manager bind failed: $error") }
            val monitor = assertNotNull(Hyprctl.monitors().firstOrNull(), "hyprctl monitors reported no usable monitor")

            val clicks = AtomicInteger()

            onBareSurface(it, CONFIG) { bar, scene ->
                scene.setContent {
                    Box(Modifier.size(TARGET_DP.dp).clickable { clicks.incrementAndGet() })
                }
                it.roundtrip()

                val geometry =
                    assertNotNull(Screen.geometry(NAMESPACE), "hyprctl layers did not report $NAMESPACE")
                // Screen.geometry and the virtual pointer's absolute space agree only because this
                // suite runs against a single output pinned at the compositor's origin.
                val targetX = geometry.x + TARGET_DP / 2
                val targetY = geometry.y + TARGET_DP / 2

                val delivered = manager.createVirtualPointer().use { pointer ->
                    fun moveTo(x: Int, y: Int) {
                        pointer.moveTo(monitor, x, y)
                        it.roundtrip()
                    }

                    // Off the bar first: the compositor re-evaluates pointer focus on motion, so a
                    // cursor already parked on these coordinates would never enter the new surface.
                    moveTo(monitor.logicalWidth / 2, monitor.logicalHeight - 1)
                    pointer.clickAt(monitor, targetX, targetY)
                    it.roundtrip()

                    val landed = bar.pumpOrFail(timeoutMillis = PUMP_TIMEOUT_MILLIS) { clicks.get() == 1 }
                    // Off it again: a cursor left on a target would deny the next test's own move
                    // here an enter, the same hazard the first move above avoids.
                    moveTo(monitor.logicalWidth / 2, monitor.logicalHeight - 1)
                    landed
                }

                it.requireAlive().getOrElse { error ->
                    fail("wayland protocol error while driving the virtual pointer: $error")
                }
                assertTrue(
                    delivered, "a virtual-pointer click at $targetX,$targetY never reached the composable",
                )
                assertEquals(1, clicks.get(), "one press and release must be one click")
            }
        }
    }

    private companion object {
        const val NAMESPACE = "kortex"
        const val BAR_HEIGHT = 32
        const val TARGET_DP = 16
        const val PUMP_TIMEOUT_MILLIS = 3000L

        val CONFIG = SurfaceConfig.panel(edge = Edge.Top, thickness = BAR_HEIGHT.dp).copy(namespace = NAMESPACE)
    }
}
