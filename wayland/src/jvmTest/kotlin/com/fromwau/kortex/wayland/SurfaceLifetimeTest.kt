package com.fromwau.kortex.wayland

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.getOrElse
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Runs many surface lifetimes on one connection while a sibling surface stays live through all of them.
 *
 * Every surface binds its own `wl_shm`, a second `wl_shm` for the cursor theme, a `wl_compositor`, a
 * cursor `wl_surface` and a native `wl_cursor_theme`. Releasing one of those twice, sending a request the
 * negotiated version does not have, or freeing the theme while the compositor still holds one of its
 * buffers corrupts the connection instead of failing an assertion, so what is asserted here is that the
 * connection is still healthy and the sibling still takes input once the surfaces beside it are gone.
 */
class SurfaceLifetimeTest {
    @Test
    fun `a sibling surface still takes a click after many surfaces are created and closed beside it`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }
        val clicks = AtomicInteger()

        display.use { wayland ->
            val manager = VirtualPointerManager.bind(wayland)
                .getOrElse { error -> fail("virtual pointer manager bind failed: $error") }
            val monitor = assertNotNull(Hyprctl.monitors().firstOrNull(), "hyprctl monitors reported no usable monitor")

            KortexSurface.create(wayland, PANEL_CONFIG)
                .getOrElse { error -> fail("panel creation failed: $error") }
                .use { panel ->
                    panel.setContent { Box(Modifier.size(TARGET_DP.dp).clickable { clicks.incrementAndGet() }) }
                    panel.pump(SETTLE_MILLIS)

                    repeat(CYCLES) { cycle ->
                        val menu = KortexSurface.create(wayland, MENU_CONFIG)
                            .getOrElse { error -> fail("menu creation failed on cycle $cycle: $error") }
                        menu.setContent { Box(Modifier.fillMaxSize()) }
                        menu.pump(SETTLE_MILLIS)
                        // The sibling drives the connection while the menu goes, so its own queue and any
                        // event the teardown produces are serviced on exactly the path a running host uses.
                        panel.serviceTick()
                        menu.close()
                        panel.pump(SETTLE_MILLIS)
                        assertNull(
                            wayland.protocolError(),
                            "the connection reported a protocol error on teardown cycle $cycle",
                        )
                    }

                    val geometry =
                        assertNotNull(Screen.geometry(PANEL_NAMESPACE), "the panel went away with the menus")

                    val delivered = manager.createVirtualPointer().use { pointer ->
                        fun moveTo(x: Int, y: Int) {
                            pointer.moveTo(monitor, x, y)
                            panel.pump(SETTLE_MILLIS)
                        }

                        // Off the panel first: the compositor re-evaluates pointer focus on motion, so a
                        // cursor already parked on these coordinates would never enter the surface.
                        moveTo(monitor.logicalWidth / 2, monitor.logicalHeight - 1)
                        moveTo(geometry.x + TARGET_DP / 2, geometry.y + TARGET_DP / 2)

                        pointer.button(BTN_LEFT, pressed = true)
                        pointer.frame()
                        panel.pump(SETTLE_MILLIS)
                        pointer.button(BTN_LEFT, pressed = false)
                        pointer.frame()

                        val landed = panel.pump(PUMP_TIMEOUT_MILLIS) { clicks.get() == 1 }
                        // Off it again: a cursor left on a target would deny the next test's own move
                        // here an enter, the same hazard the first move above avoids.
                        moveTo(monitor.logicalWidth / 2, monitor.logicalHeight - 1)
                        landed
                    }

                    assertNull(wayland.protocolError(), "the connection reported a protocol error after the click")
                    assertTrue(delivered, "a click never reached the panel that outlived $CYCLES surfaces")
                    assertEquals(1, clicks.get(), "one press and release must be one click")
                }
        }
    }

    /**
     * Each of these owns proxies, or a native handle, that a second release would give back twice, and
     * the `KortexSurface` latch guarding them there is not something a direct caller of these classes has.
     */
    @Test
    fun `closing the shm, the cursor theme, the cursor surface and a layer surface twice releases nothing twice`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use { wayland ->
            val shm = Shm.bind(wayland).getOrElse { error -> fail("shm bind failed: $error") }
            val theme = WlCursorTheme.load(wayland, scale = 1)
                .getOrElse { error -> fail("cursor theme load failed: $error") }
            val cursorSurface = WlCursorSurface.create(wayland)
                .getOrElse { error -> fail("cursor surface creation failed: $error") }
            val layer = LayerSurface.create(
                wayland,
                namespace = LAYER_NAMESPACE,
                height = LAYER_HEIGHT,
                exclusiveZone = ExclusiveZone.Yield,
            ).getOrElse { error -> fail("layer surface creation failed: $error") }

            cursorSurface.close()
            cursorSurface.close()
            // The theme's buffers belong to the surface just destroyed; the round trip is what lets it go.
            wayland.roundtrip()
            theme.close()
            theme.close()
            shm.close()
            shm.close()
            layer.close()
            layer.close()
            wayland.roundtrip()

            assertNull(wayland.protocolError(), "a second close gave back something the first already released")
        }
    }

    private companion object {
        const val LAYER_NAMESPACE = "kortex-lifetime-layer"
        const val LAYER_HEIGHT = 24
        const val PANEL_NAMESPACE = "kortex-lifetime-panel"
        const val MENU_NAMESPACE = "kortex-lifetime-menu"

        const val PANEL_HEIGHT = 32
        const val MENU_SIZE = 160
        const val TARGET_DP = 16

        /** Enough turns that a leak or a double release is past chance, without stretching the run. */
        const val CYCLES = 6

        const val SETTLE_MILLIS = 250L
        const val PUMP_TIMEOUT_MILLIS = 4000L

        // linux/input-event-codes.h
        const val BTN_LEFT = 0x110

        val PANEL_CONFIG = SurfaceConfig.panel(Edge.Top, PANEL_HEIGHT.dp).copy(namespace = PANEL_NAMESPACE)

        /** An app menu, not an OSD, so each surface that goes owns a `wl_keyboard` as well as a `wl_pointer`. */
        val MENU_CONFIG = SurfaceConfig.appMenu(MENU_SIZE.dp, MENU_SIZE.dp).copy(namespace = MENU_NAMESPACE)
    }
}
