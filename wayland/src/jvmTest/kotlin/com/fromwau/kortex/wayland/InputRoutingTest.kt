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
 * The compositor sends a pointer event to every `wl_pointer` the client holds, and each surface holds its own, so
 * a surface has to take only the events for its own `wl_surface`.
 */
class InputRoutingTest {
    @Test
    fun `a click on one surface does not reach a sibling surface at the same local position`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }
        val topClicks = AtomicInteger()
        val bottomClicks = AtomicInteger()

        display.use { wayland ->
            val manager = VirtualPointerManager.bind(wayland)
                .getOrElse { error -> fail("virtual pointer manager bind failed: $error") }
            val monitor = assertNotNull(Hyprctl.monitors().firstOrNull(), "hyprctl monitors reported no usable monitor")

            onBareSurface(wayland, TOP_CONFIG) { top, topScene ->
                onBareSurface(wayland, BOTTOM_CONFIG) { bottom, bottomScene ->
                    topScene.setContent { Box(Modifier.size(TARGET_DP.dp).clickable { topClicks.incrementAndGet() }) }
                    bottomScene.setContent {
                        Box(Modifier.size(TARGET_DP.dp).clickable { bottomClicks.incrementAndGet() })
                    }

                    // Each bare surface runs its own loop queue, so both are pumped for either to act on input.
                    fun settle() {
                        top.pumpOrFail(SETTLE_MILLIS)
                        bottom.pumpOrFail(SETTLE_MILLIS)
                    }
                    settle()

                    val geometry =
                        assertNotNull(Screen.geometry(TOP_NAMESPACE), "hyprctl layers did not report the top panel")
                    manager.createVirtualPointer().use { pointer ->
                        // Off both panels first: a cursor already parked on the target would never enter it.
                        pointer.moveTo(monitor, monitor.logicalWidth / 2, monitor.logicalHeight / 2)
                        settle()
                        pointer.clickAt(monitor, geometry.x + TARGET_DP / 2, geometry.y + TARGET_DP / 2)
                        top.pumpOrFail(PUMP_TIMEOUT_MILLIS) { topClicks.get() == 1 }
                        settle()
                        pointer.moveTo(monitor, monitor.logicalWidth / 2, monitor.logicalHeight / 2)
                        settle()
                    }

                    assertTrue(topClicks.get() == 1, "the click never reached the panel it was made on")
                    assertEquals(0, bottomClicks.get(), "a click on the top panel also clicked the bottom panel")
                }
            }
        }
    }

    private companion object {
        const val TOP_NAMESPACE = "kortex-routing-top"
        const val BOTTOM_NAMESPACE = "kortex-routing-bottom"
        const val PANEL_HEIGHT = 32
        const val TARGET_DP = 16
        const val SETTLE_MILLIS = 250L
        const val PUMP_TIMEOUT_MILLIS = 4000L

        val TOP_CONFIG = SurfaceConfig.panel(Edge.Top, PANEL_HEIGHT.dp).copy(namespace = TOP_NAMESPACE)
        val BOTTOM_CONFIG = SurfaceConfig.panel(Edge.Bottom, PANEL_HEIGHT.dp).copy(namespace = BOTTOM_NAMESPACE)
    }
}
