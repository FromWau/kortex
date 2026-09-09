package com.fromwau.kortex.wayland

import com.fromwau.kern.result.getOrElse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Pins the two ways a surface controls its exclusive zone beyond a plain positive number: `-1` (extend
 * through other surfaces' reservations instead of yielding to them) and the explicit edge a corner
 * anchor needs to make a positive zone meaningful at all.
 */
class ExclusiveZoneTest {
    @Test
    fun `exclusiveZone -1 covers the full output despite a positive-zone panel`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use { wayland ->
            val monitor = bindFirstOutput(wayland)

            val panel = LayerSurface.create(
                wayland,
                namespace = PANEL_NAMESPACE,
                height = PANEL_HEIGHT,
                anchor = Anchor.TOP or Anchor.LEFT or Anchor.RIGHT,
                exclusiveZone = PANEL_HEIGHT,
                output = monitor.proxy,
            ).getOrElse { error -> fail("panel creation failed: $error") }

            panel.use {
                assertTrue(panel.waitForConfigure(), "panel never configured")
                wayland.roundtrip()

                val background = LayerSurface.create(
                    wayland,
                    namespace = BACKGROUND_NAMESPACE,
                    height = 0,
                    width = 0,
                    layer = Layer.Background,
                    anchor = Anchor.TOP or Anchor.BOTTOM or Anchor.LEFT or Anchor.RIGHT,
                    exclusiveZone = -1,
                    output = monitor.proxy,
                ).getOrElse { error -> fail("background creation failed: $error") }

                background.use {
                    assertTrue(background.waitForConfigure(), "background never configured")
                    wayland.roundtrip()

                    val geometry = assertNotNull(
                        Screen.geometry(BACKGROUND_NAMESPACE), "hyprctl layers does not report $BACKGROUND_NAMESPACE",
                    )
                    val monitorLogicalWidth = monitor.geometry.width / monitor.geometry.scale
                    val monitorLogicalHeight = monitor.geometry.height / monitor.geometry.scale

                    assertEquals(monitor.geometry.x, geometry.x, "exclusiveZone = -1 was pushed off the left edge")
                    assertEquals(monitor.geometry.y, geometry.y, "exclusiveZone = -1 was pushed below the panel")
                    assertEquals(
                        monitorLogicalWidth, geometry.logicalWidth,
                        "exclusiveZone = -1 did not extend across the full width",
                    )
                    assertEquals(
                        monitorLogicalHeight, geometry.logicalHeight,
                        "exclusiveZone = -1 did not extend across the full height, staying clear of the panel instead",
                    )
                }
            }
        }
    }

    @Test
    fun `an explicit exclusiveEdge lets a corner anchor's positive zone reserve space against that edge only`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use { wayland ->
            val monitor = bindFirstOutput(wayland)

            // BOTTOM+RIGHT, not the more obvious TOP+RIGHT: a real top bar on this desktop reserves its
            // own exclusive zone, which would push a TOP-anchored surface down regardless of anything
            // this test does (see LayerGeometryTest).
            val corner = LayerSurface.create(
                wayland,
                namespace = CORNER_NAMESPACE,
                height = CORNER_HEIGHT,
                width = CORNER_WIDTH,
                anchor = Anchor.BOTTOM or Anchor.RIGHT,
                exclusiveZone = CORNER_ZONE,
                exclusiveEdge = Anchor.RIGHT,
                output = monitor.proxy,
            ).getOrElse { error -> fail("corner surface creation failed: $error") }

            corner.use {
                val configured = corner.waitForConfigure()
                // A rejected edge kills the connection, so the surface just never configures. Reading the
                // error first turns that opaque timeout into the violation that caused it.
                assertNull(
                    display.protocolError(),
                    "an exclusiveEdge the surface is actually anchored to must not raise invalid_exclusive_edge",
                )
                assertTrue(configured, "corner surface never configured")
                wayland.roundtrip()

                val cornerGeometry = assertNotNull(
                    Screen.geometry(CORNER_NAMESPACE), "hyprctl layers does not report $CORNER_NAMESPACE",
                )
                val monitorLogicalWidth = monitor.geometry.width / monitor.geometry.scale
                val monitorLogicalHeight = monitor.geometry.height / monitor.geometry.scale
                assertEquals(
                    monitor.geometry.x + monitorLogicalWidth - CORNER_WIDTH, cornerGeometry.x,
                    "corner surface did not land at the anchored right edge",
                )
                assertEquals(
                    monitor.geometry.y + monitorLogicalHeight - CORNER_HEIGHT, cornerGeometry.y,
                    "corner surface did not land at the anchored bottom edge",
                )

                // A second, unrelated surface whose own exclusiveZone is 0 (move me out of the way of
                // whoever reserves space) is the probe: it only shrinks if the corner's positive zone
                // actually reserved space, which set_exclusive_zone alone cannot do for a corner anchor.
                val probe = LayerSurface.create(
                    wayland,
                    namespace = PROBE_NAMESPACE,
                    height = 0,
                    width = 0,
                    anchor = Anchor.TOP or Anchor.BOTTOM or Anchor.LEFT or Anchor.RIGHT,
                    exclusiveZone = 0,
                    output = monitor.proxy,
                ).getOrElse { error -> fail("probe surface creation failed: $error") }

                probe.use {
                    assertTrue(probe.waitForConfigure(), "probe surface never configured")
                    wayland.roundtrip()

                    val probeGeometry = assertNotNull(
                        Screen.geometry(PROBE_NAMESPACE), "hyprctl layers does not report $PROBE_NAMESPACE",
                    )
                    assertEquals(
                        monitorLogicalWidth - CORNER_ZONE, probeGeometry.logicalWidth,
                        "the corner's exclusiveEdge = RIGHT reservation never reached the compositor",
                    )
                }
            }
        }
    }

    private companion object {
        const val PANEL_NAMESPACE = "kortex-exclusive-zone-panel"
        const val BACKGROUND_NAMESPACE = "kortex-exclusive-zone-background"
        const val CORNER_NAMESPACE = "kortex-exclusive-zone-corner"
        const val PROBE_NAMESPACE = "kortex-exclusive-zone-probe"

        const val PANEL_HEIGHT = 53

        // Distinct from each other and from PANEL_HEIGHT so a wire-order mixup (e.g. exclusiveZone
        // defaulting back to height) shows up as a wrong number rather than an accidental match.
        const val CORNER_HEIGHT = 61
        const val CORNER_WIDTH = 133
        const val CORNER_ZONE = 77
    }
}
