package com.fromwau.kortex.wayland

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.getOrElse
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Gives a `wl_pointer` back while the `wl_seat` it came from stays bound, and clicks again afterwards. The
 * compositor still sends this client pointer events, so a pointer whose listener stubs were freed before its
 * proxy went would be dispatched through freed code and take the test worker with it.
 *
 * [SurfaceTeardownTest] cannot see that: the surface it closes gives its own seat back as well, and Hyprland
 * 0.56.2 sends nothing more to a released seat, whatever became of the pointer taken from it.
 */
class PointerReleaseOrderTest {
    @Test
    fun `a released pointer takes no further button, and the connection lives`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use { wayland ->
            val manager = VirtualPointerManager.bind(wayland)
                .getOrElse { error -> fail("virtual pointer manager bind failed: $error") }
            val monitor = assertNotNull(Hyprctl.monitors().firstOrNull(), "hyprctl monitors reported no usable monitor")

            onBareSurface(wayland, CONFIG) { osd, osdScene ->
                osdScene.setContent { Box(Modifier.fillMaxSize()) }
                // The compositor sends a click to a surface that has a buffer, which is one frame away.
                osd.pumpOrFail(SETTLE_MILLIS)
                wayland.roundtrip()
                val placed = assertNotNull(Screen.geometry(NAMESPACE), "hyprctl layers did not report $NAMESPACE")
                val targetX = placed.x + placed.logicalWidth / 2
                val targetY = placed.y + placed.logicalHeight / 2

                onOwnPointer(wayland) { pointer, buttons ->
                    manager.createVirtualPointer().use { virtual ->
                        try {
                            // Off the surface first: the compositor re-evaluates pointer focus on motion, so a
                            // cursor already parked on the target never enters it.
                            virtual.moveTo(monitor, monitor.logicalWidth / 2, monitor.logicalHeight - 1)
                            osd.pumpOrFail(SETTLE_MILLIS)

                            virtual.clickAt(monitor, targetX, targetY)
                            assertTrue(
                                osd.pumpOrFail(PUMP_MILLIS) { buttons.isNotEmpty() },
                                "no button reached the pointer bound here, so giving it back proves nothing",
                            )
                            val beforeRelease = buttons.size

                            pointer.release()
                            virtual.clickAt(monitor, targetX, targetY)
                            osd.pumpOrFail(SETTLE_MILLIS)

                            assertEquals(
                                beforeRelease, buttons.size,
                                "a button reached the pointer after it was given back",
                            )
                            wayland.requireAlive().getOrElse { error ->
                                fail("wayland protocol error after the pointer was given back: $error")
                            }
                        } finally {
                            // Unconditional, so a failure above still cannot leave the cursor on the target and
                            // deny the next test's own move here its enter.
                            virtual.moveTo(monitor, monitor.logicalWidth / 2, monitor.logicalHeight - 1)
                            osd.pumpOrFail(SETTLE_MILLIS)
                        }
                    }
                }
            }
        }
    }

    /**
     * Runs [block] on a pointer taken from a seat of this test's own, which stays bound around it, and on the
     * serial of every button that pointer is handed.
     */
    private fun onOwnPointer(
        display: WaylandDisplay,
        block: (pointer: PointerInput, buttons: List<Int>) -> Unit,
    ) {
        val buttons = CopyOnWriteArrayList<Int>()
        val seat = Seat.bind(display).getOrElse { error -> fail("seat bind failed: $error") }
        try {
            onScene(IntSize(SIDE, SIDE)) { scene, raster ->
                scene.setContent { Box(Modifier.fillMaxSize()) }
                scene.render(raster.canvas.asComposeCanvas(), 0L)
                val pointer = assertNotNull(
                    seat.attachPointer(scene, scale = 1f, onInputSerial = { buttons += it }),
                    "the seat announced no pointer",
                )
                try {
                    block(pointer, buttons)
                } finally {
                    // Idempotent, and before the scene closes: an event still in flight would otherwise reach a
                    // composition that is being disposed.
                    pointer.release()
                }
            }
        } finally {
            seat.release()
        }
    }

    private companion object {
        const val NAMESPACE = "kortex-pointer-release"
        const val SIDE = 64
        const val OSD_DP = 120
        const val SETTLE_MILLIS = 300L
        const val PUMP_MILLIS = 3000L

        val CONFIG = SurfaceConfig.osd(OSD_DP.dp, OSD_DP.dp).copy(namespace = NAMESPACE)
    }
}
