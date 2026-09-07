package com.fromwau.kortex.wayland

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.getOrElse
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt
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
            val monitor = assertNotNull(primaryMonitorExtent(), "hyprctl monitors reported no usable monitor")

            val clicks = AtomicInteger()

            KortexBar.create(it, namespace = NAMESPACE, height = BAR_HEIGHT.dp)
                .getOrElse { error -> fail("bar creation failed: $error") }
                .use { bar ->
                    bar.setContent {
                        Box(Modifier.size(TARGET_DP.dp).clickable { clicks.incrementAndGet() })
                    }
                    it.roundtrip()

                    val geometry =
                        assertNotNull(Screen.geometry(NAMESPACE), "hyprctl layers did not report $NAMESPACE")
                    // Screen.geometry and the virtual pointer's absolute space agree only because this
                    // suite runs against a single output pinned at the compositor's origin.
                    val targetX = geometry.x + TARGET_DP / 2
                    val targetY = geometry.y + TARGET_DP / 2

                    manager.createVirtualPointer().use { pointer ->
                        pointer.motionAbsolute(targetX, targetY, monitor.logicalWidth, monitor.logicalHeight)
                        pointer.frame()
                        it.roundtrip()

                        pointer.button(BTN_LEFT, pressed = true)
                        pointer.frame()
                        it.roundtrip()

                        pointer.button(BTN_LEFT, pressed = false)
                        pointer.frame()
                        it.roundtrip()
                    }

                    val delivered = bar.pump(timeoutMillis = PUMP_TIMEOUT_MILLIS) { clicks.get() == 1 }

                    val protocolError = it.protocolError()
                    if (protocolError != null) {
                        fail("wayland protocol error while driving the virtual pointer: $protocolError")
                    }
                    assertTrue(
                        delivered, "a virtual-pointer click at $targetX,$targetY never reached the composable",
                    )
                    assertEquals(1, clicks.get(), "one press and release must be one click")
                }
        }
    }

    /** The first monitor's logical (post-scale) size, the same space [Screen.geometry] reports in. */
    private data class MonitorExtent(val logicalWidth: Int, val logicalHeight: Int)

    private fun primaryMonitorExtent(): MonitorExtent? {
        val json = ProcessBuilder("hyprctl", "monitors", "-j").redirectErrorStream(true)
            .start().inputStream.bufferedReader().readText()
        val width = Regex("\"width\": (\\d+)").find(json)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val height = Regex("\"height\": (\\d+)").find(json)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val scale = Regex("\"scale\": ([0-9.]+)").find(json)?.groupValues?.get(1)?.toFloatOrNull() ?: 1f
        return MonitorExtent((width / scale).roundToInt(), (height / scale).roundToInt())
    }

    private companion object {
        const val NAMESPACE = "kortex"
        const val BAR_HEIGHT = 32
        const val TARGET_DP = 16
        const val PUMP_TIMEOUT_MILLIS = 3000L

        // linux/input-event-codes.h
        const val BTN_LEFT = 0x110
    }
}
