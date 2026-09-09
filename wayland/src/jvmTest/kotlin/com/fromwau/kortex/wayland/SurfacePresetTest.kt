package com.fromwau.kortex.wayland

import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.getOrElse
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Pins that [SurfaceConfig.panel], [SurfaceConfig.dock], [SurfaceConfig.desktopBackground],
 * [SurfaceConfig.lockScreen], [SurfaceConfig.osd] and [SurfaceConfig.appMenu] assemble the layer,
 * anchor and exclusive zone each promises, and that reaches the compositor rather than being dropped
 * or replaced by a default on the way through.
 */
class SurfacePresetTest {
    @Test
    fun `panel spans its edge and reserves exactly its own thickness`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use { wayland ->
            val output = bindFirstOutput(wayland)
            // The usable area, not the raw output: another surface (a real desktop's own bar) may
            // already reserve space this panel has to land clear of.
            val before = monitor(output.geometry.name)
            val config = SurfaceConfig.panel(edge = Edge.Top, thickness = PANEL_THICKNESS.dp)
                .copy(namespace = PANEL_NAMESPACE)

            val panel = KortexSurface.create(wayland, config, output = output.proxy)
                .getOrElse { error -> fail("panel creation failed: $error") }

            panel.use {
                wayland.roundtrip()

                val geometry = assertNotNull(
                    Screen.geometry(PANEL_NAMESPACE), "hyprctl layers does not report $PANEL_NAMESPACE",
                )

                assertEquals(Layer.Top, geometry.layer, "panel did not land on Layer.Top")
                assertEquals(before.usableX, geometry.x, "panel did not sit clear of what already reserves space")
                assertEquals(before.usableY, geometry.y, "panel did not sit clear of what already reserves space")
                assertEquals(before.usableWidth, geometry.logicalWidth, "panel did not span the edge it anchors")
                assertEquals(PANEL_THICKNESS, geometry.logicalHeight, "panel's extent is not its own thickness")

                val after = awaitReserved(output.geometry.name) { it.usableY - before.usableY == PANEL_THICKNESS }
                assertEquals(
                    PANEL_THICKNESS, after.usableY - before.usableY,
                    "panel did not reserve exactly its own thickness",
                )
            }
        }
    }

    @Test
    fun `dock reserves its own thickness against the edge it is anchored to, on demand for the keyboard`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use { wayland ->
            val output = bindFirstOutput(wayland)
            // The usable area, not the raw output: another surface (a real desktop's own bar) may
            // already reserve space this dock has to land clear of.
            val before = monitor(output.geometry.name)
            val config = SurfaceConfig.dock(edge = Edge.Left, thickness = DOCK_THICKNESS.dp)
                .copy(namespace = DOCK_NAMESPACE)
            assertEquals(KeyboardInteractivity.OnDemand, config.keyboard, "dock did not ask for keyboard on demand")

            val dock = KortexSurface.create(wayland, config, output = output.proxy)
                .getOrElse { error -> fail("dock creation failed: $error") }

            dock.use {
                wayland.roundtrip()

                val geometry = assertNotNull(
                    Screen.geometry(DOCK_NAMESPACE), "hyprctl layers does not report $DOCK_NAMESPACE",
                )

                assertEquals(before.usableX, geometry.x, "dock did not sit clear of what already reserves space")
                assertEquals(before.usableY, geometry.y, "dock did not sit clear of what already reserves space")
                assertEquals(DOCK_THICKNESS, geometry.logicalWidth, "dock's extent is not its own thickness")
                assertEquals(before.usableHeight, geometry.logicalHeight, "dock did not span the edge it anchors")

                val after = awaitReserved(output.geometry.name) { it.usableX - before.usableX == DOCK_THICKNESS }
                assertEquals(
                    DOCK_THICKNESS, after.usableX - before.usableX,
                    "dock did not reserve exactly its own thickness against its anchored edge",
                )
            }
        }
    }

    @Test
    fun `desktopBackground covers the whole output at level 0 and reserves nothing`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use { wayland ->
            val output = bindFirstOutput(wayland)
            val before = monitor(output.geometry.name)
            val config = SurfaceConfig.desktopBackground().copy(namespace = BACKGROUND_NAMESPACE)

            val background = KortexSurface.create(wayland, config, output = output.proxy)
                .getOrElse { error -> fail("background creation failed: $error") }

            background.use {
                wayland.roundtrip()

                val geometry = assertNotNull(
                    Screen.geometry(BACKGROUND_NAMESPACE), "hyprctl layers does not report $BACKGROUND_NAMESPACE",
                )
                val monitorLogicalWidth = output.geometry.width / output.geometry.scale
                val monitorLogicalHeight = output.geometry.height / output.geometry.scale

                assertEquals(Layer.Background, geometry.layer, "desktopBackground did not land at level 0")
                assertEquals(output.geometry.x, geometry.x, "desktopBackground did not cover the whole output")
                assertEquals(output.geometry.y, geometry.y, "desktopBackground did not cover the whole output")
                assertEquals(
                    monitorLogicalWidth, geometry.logicalWidth,
                    "desktopBackground did not cover the whole output",
                )
                assertEquals(
                    monitorLogicalHeight, geometry.logicalHeight,
                    "desktopBackground did not cover the whole output",
                )

                val after = monitor(output.geometry.name)
                assertEquals(before.reserved, after.reserved, "desktopBackground reserved screen space")
            }
        }
    }

    @Test
    fun `lockScreen covers the whole output above everything else and takes keyboard focus exclusively`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use { wayland ->
            val output = bindFirstOutput(wayland)
            val before = monitor(output.geometry.name)
            val config = SurfaceConfig.lockScreen().copy(namespace = LOCK_NAMESPACE)
            assertEquals(
                KeyboardInteractivity.Exclusive, config.keyboard,
                "lockScreen did not ask for exclusive keyboard focus",
            )

            val lock = KortexSurface.create(wayland, config, output = output.proxy)
                .getOrElse { error -> fail("lockScreen creation failed: $error") }

            lock.use {
                wayland.roundtrip()

                val geometry = assertNotNull(
                    Screen.geometry(LOCK_NAMESPACE), "hyprctl layers does not report $LOCK_NAMESPACE",
                )
                val monitorLogicalWidth = output.geometry.width / output.geometry.scale
                val monitorLogicalHeight = output.geometry.height / output.geometry.scale

                assertEquals(Layer.Overlay, geometry.layer, "lockScreen did not land above everything else")
                assertEquals(output.geometry.x, geometry.x, "lockScreen did not cover the whole output")
                assertEquals(output.geometry.y, geometry.y, "lockScreen did not cover the whole output")
                assertEquals(monitorLogicalWidth, geometry.logicalWidth, "lockScreen did not cover the whole output")
                assertEquals(monitorLogicalHeight, geometry.logicalHeight, "lockScreen did not cover the whole output")

                val after = monitor(output.geometry.name)
                assertEquals(before.reserved, after.reserved, "lockScreen reserved screen space")
            }
        }
    }

    @Test
    fun `osd is centred in the usable area, within a pixel of rounding, and reserves nothing`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use { wayland ->
            val output = bindFirstOutput(wayland)
            // The usable area, not the raw output: yielding centres an unanchored surface in what is
            // left after another surface's own exclusive zone, not the output's true centre.
            val before = monitor(output.geometry.name)
            val config = SurfaceConfig.osd(OSD_WIDTH.dp, OSD_HEIGHT.dp).copy(namespace = OSD_NAMESPACE)

            val osd = KortexSurface.create(wayland, config, output = output.proxy)
                .getOrElse { error -> fail("osd creation failed: $error") }

            osd.use {
                wayland.roundtrip()

                val geometry = assertNotNull(
                    Screen.geometry(OSD_NAMESPACE), "hyprctl layers does not report $OSD_NAMESPACE",
                )
                assertEquals(Layer.Overlay, geometry.layer, "osd did not land above every other layer")
                assertEquals(OSD_WIDTH, geometry.logicalWidth, "osd is not its own requested width")
                assertEquals(OSD_HEIGHT, geometry.logicalHeight, "osd is not its own requested height")
                assertCentredInUsableArea(before, OSD_WIDTH, OSD_HEIGHT, geometry)

                val after = monitor(output.geometry.name)
                assertEquals(before.reserved, after.reserved, "osd reserved screen space")
            }
        }
    }

    @Test
    fun `appMenu centres like osd and additionally takes keyboard focus on demand`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use { wayland ->
            val output = bindFirstOutput(wayland)
            val before = monitor(output.geometry.name)
            val config = SurfaceConfig.appMenu(APP_MENU_WIDTH.dp, APP_MENU_HEIGHT.dp)
                .copy(namespace = APP_MENU_NAMESPACE)
            assertEquals(
                KeyboardInteractivity.OnDemand, config.keyboard, "appMenu did not ask for keyboard on demand",
            )

            val appMenu = KortexSurface.create(wayland, config, output = output.proxy)
                .getOrElse { error -> fail("appMenu creation failed: $error") }

            appMenu.use {
                wayland.roundtrip()

                val geometry = assertNotNull(
                    Screen.geometry(APP_MENU_NAMESPACE), "hyprctl layers does not report $APP_MENU_NAMESPACE",
                )
                assertCentredInUsableArea(before, APP_MENU_WIDTH, APP_MENU_HEIGHT, geometry)
            }
        }
    }

    private fun assertCentredInUsableArea(before: Monitor, width: Int, height: Int, geometry: LayerGeometry) {
        val expectedX = before.usableX + (before.usableWidth - width) / 2
        val expectedY = before.usableY + (before.usableHeight - height) / 2
        assertTrue(
            abs(geometry.x - expectedX) <= 1,
            "expected x within a pixel of $expectedX, got ${geometry.x}",
        )
        assertTrue(
            abs(geometry.y - expectedY) <= 1,
            "expected y within a pixel of $expectedY, got ${geometry.y}",
        )
    }

    private fun monitor(name: String): Monitor =
        assertNotNull(Hyprctl.monitors().firstOrNull { it.name == name }, "hyprctl lost monitor $name")

    /** Polls [monitorName]'s reservation until [predicate] holds, since a reservation lands a frame late. */
    private fun awaitReserved(monitorName: String, predicate: (Monitor) -> Boolean): Monitor {
        val deadline = System.nanoTime() + SETTLE_TIMEOUT_MILLIS * NANOS_PER_MILLI
        var reported = monitor(monitorName)
        while (!predicate(reported) && System.nanoTime() < deadline) {
            Thread.sleep(POLL_INTERVAL_MILLIS)
            reported = monitor(monitorName)
        }
        return reported
    }

    private companion object {
        const val PANEL_NAMESPACE = "kortex-preset-panel"
        const val DOCK_NAMESPACE = "kortex-preset-dock"
        const val BACKGROUND_NAMESPACE = "kortex-preset-background"
        const val LOCK_NAMESPACE = "kortex-preset-lock"
        const val OSD_NAMESPACE = "kortex-preset-osd"
        const val APP_MENU_NAMESPACE = "kortex-preset-app-menu"

        // Distinct from each other and from the sizes other tests use, so a preset reaching the wrong
        // field shows up as a wrong number rather than an accidental match.
        const val PANEL_THICKNESS = 71
        const val DOCK_THICKNESS = 89
        const val OSD_WIDTH = 233
        const val OSD_HEIGHT = 47
        const val APP_MENU_WIDTH = 311
        const val APP_MENU_HEIGHT = 199

        const val SETTLE_TIMEOUT_MILLIS = 2000L
        const val POLL_INTERVAL_MILLIS = 100L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
