package com.fromwau.kortex.wayland

import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.getOrElse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Pins the two ways a surface controls its exclusive zone beyond reserving a plain amount:
 * [ExclusiveZone.Overlap] (extend through other surfaces' reservations instead of yielding to them)
 * and the explicit edge a corner anchor needs to make a reservation meaningful at all.
 */
class ExclusiveZoneTest {
    @Test
    fun `ExclusiveZone Overlap covers the full output despite a reserving panel`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use { wayland ->
            val monitor = bindFirstOutput(wayland)

            val panel = LayerShellSurface.create(
                wayland,
                namespace = PANEL_NAMESPACE,
                height = PANEL_HEIGHT,
                anchor = setOf(Edge.Top, Edge.Left, Edge.Right),
                exclusiveZone = ExclusiveZone.Reserve(PANEL_HEIGHT.dp),
                output = monitor.proxy,
            ).getOrElse { error -> fail("panel creation failed: $error") }

            panel.use {
                assertTrue(panel.waitForConfigure(), "panel never configured")
                wayland.roundtrip()

                val background = LayerShellSurface.create(
                    wayland,
                    namespace = BACKGROUND_NAMESPACE,
                    height = 0,
                    width = 0,
                    layer = Layer.Background,
                    anchor = setOf(Edge.Top, Edge.Bottom, Edge.Left, Edge.Right),
                    exclusiveZone = ExclusiveZone.Overlap,
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

                    assertEquals(monitor.geometry.x, geometry.x, "Overlap was pushed off the left edge")
                    assertEquals(monitor.geometry.y, geometry.y, "Overlap was pushed below the panel")
                    assertEquals(
                        monitorLogicalWidth, geometry.logicalWidth,
                        "Overlap did not extend across the full width",
                    )
                    assertEquals(
                        monitorLogicalHeight, geometry.logicalHeight,
                        "Overlap did not extend across the full height, staying clear of the panel instead",
                    )
                }
            }
        }
    }

    @Test
    fun `an explicit exclusiveEdge lets a corner anchor reserve space against that edge only`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use { wayland ->
            val monitor = bindFirstOutput(wayland)

            // Bottom+Right, not the more obvious Top+Right: a real top bar on this desktop reserves its
            // own exclusive zone, which would push a Top-anchored surface down regardless of anything
            // this test does (see LayerGeometryTest).
            val corner = LayerShellSurface.create(
                wayland,
                namespace = CORNER_NAMESPACE,
                height = CORNER_HEIGHT,
                width = CORNER_WIDTH,
                anchor = setOf(Edge.Bottom, Edge.Right),
                exclusiveZone = ExclusiveZone.Reserve(CORNER_ZONE.dp),
                exclusiveEdge = Edge.Right,
                output = monitor.proxy,
            ).getOrElse { error -> fail("corner surface creation failed: $error") }

            corner.use {
                val configured = corner.waitForConfigure()
                // A rejected edge kills the connection, so the surface just never configures. Reading the
                // error first turns that opaque timeout into the violation that caused it.
                assertEquals(
                    Ok(Unit),
                    display.requireAlive(),
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

                // A second, unrelated surface that yields (move me out of the way of whoever reserves
                // space) is the probe: it only shrinks if the corner's reservation actually took, which
                // set_exclusive_zone alone cannot do for a corner anchor.
                val probe = LayerShellSurface.create(
                    wayland,
                    namespace = PROBE_NAMESPACE,
                    height = 0,
                    width = 0,
                    anchor = setOf(Edge.Top, Edge.Bottom, Edge.Left, Edge.Right),
                    exclusiveZone = ExclusiveZone.Yield,
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
                        "the corner's exclusiveEdge = Right reservation never reached the compositor",
                    )
                }
            }
        }
    }

    @Test
    fun `a Reserve that rounds away to nothing is rejected rather than silently becoming another case`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use { wayland ->
            for (amount in ROUNDING_TO_NOTHING) {
                val result = LayerShellSurface.create(
                    wayland,
                    namespace = ROUNDED_NAMESPACE,
                    height = CORNER_HEIGHT,
                    exclusiveZone = ExclusiveZone.Reserve(amount),
                )

                when (result) {
                    is Ok -> fail("Reserve($amount) reserves nothing and must be rejected: ${result.value}")
                    is Err -> assertEquals(KortexError.InvalidExclusiveZone(amount), result.error)
                }
            }

            // A pixel is the smallest reservation that means what it says, so it must still be accepted.
            val smallest = LayerShellSurface.create(
                wayland,
                namespace = ROUNDED_NAMESPACE,
                height = CORNER_HEIGHT,
                exclusiveZone = ExclusiveZone.Reserve(1.dp),
            ).getOrElse { error -> fail("a one-pixel reservation must be accepted: $error") }
            smallest.use { assertTrue(smallest.waitForConfigure(), "the compositor never configured it") }
        }
    }

    @Test
    fun `an exclusiveEdge the anchor does not pin is rejected before any request is sent`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use { wayland ->
            val anchor = setOf(Edge.Bottom, Edge.Right)
            val result = LayerShellSurface.create(
                wayland,
                namespace = REJECTED_NAMESPACE,
                height = CORNER_HEIGHT,
                width = CORNER_WIDTH,
                anchor = anchor,
                exclusiveZone = ExclusiveZone.Reserve(CORNER_ZONE.dp),
                exclusiveEdge = Edge.Top,
            )

            when (result) {
                is Ok -> fail("an exclusiveEdge the anchor does not pin must be rejected: ${result.value}")
                is Err -> assertEquals(KortexError.InvalidExclusiveEdge(Edge.Top, anchor), result.error)
            }

            // The rejection must happen before any request reaches the compositor, leaving the
            // connection itself unharmed; prove it by using it normally right after.
            val sanity = LayerShellSurface.create(
                wayland, namespace = REJECTED_NAMESPACE, height = CORNER_HEIGHT,
                exclusiveZone = ExclusiveZone.Yield,
            ).getOrElse { error -> fail("the connection was left unusable after the rejection: $error") }
            sanity.use { assertTrue(sanity.waitForConfigure(), "connection did not survive the rejection") }
        }
    }

    private companion object {
        const val PANEL_NAMESPACE = "kortex-exclusive-zone-panel"
        const val BACKGROUND_NAMESPACE = "kortex-exclusive-zone-background"
        const val CORNER_NAMESPACE = "kortex-exclusive-zone-corner"
        const val PROBE_NAMESPACE = "kortex-exclusive-zone-probe"
        const val REJECTED_NAMESPACE = "kortex-exclusive-zone-rejected"
        const val ROUNDED_NAMESPACE = "kortex-exclusive-zone-rounded"

        // 0 is Yield's wire value and -1 is Overlap's; 0.4 covers the rounding itself, which a guard
        // reading the Dp rather than the wire value would let through.
        val ROUNDING_TO_NOTHING = listOf(0.dp, 0.4.dp, (-1).dp)

        const val PANEL_HEIGHT = 53

        // Distinct from each other and from PANEL_HEIGHT so a wire-order mixup (e.g. the zone taking
        // the height's place) shows up as a wrong number rather than an accidental match.
        const val CORNER_HEIGHT = 61
        const val CORNER_WIDTH = 133
        const val CORNER_ZONE = 77
    }
}
