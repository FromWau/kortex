package com.fromwau.kortex.wayland

import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.getOrElse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
                SurfaceConfig(
                    namespace = PANEL_NAMESPACE,
                    anchor = setOf(Edge.Top, Edge.Left, Edge.Right),
                    width = SPAN_ANCHORED_AXIS.dp,
                    height = PANEL_HEIGHT.dp,
                    exclusiveZone = ExclusiveZone.Reserve(PANEL_HEIGHT.dp),
                ),
                output = monitor.proxy,
            ).getOrElse { error -> fail("panel creation failed: $error") }

            panel.use {
                panel.waitForConfigure().getOrElse { error -> fail("panel never configured: $error") }
                wayland.roundtrip()

                val background = LayerShellSurface.create(
                    wayland,
                    SurfaceConfig(
                        namespace = BACKGROUND_NAMESPACE,
                        layer = Layer.Background,
                        anchor = setOf(Edge.Top, Edge.Bottom, Edge.Left, Edge.Right),
                        width = SPAN_ANCHORED_AXIS.dp,
                        height = SPAN_ANCHORED_AXIS.dp,
                        exclusiveZone = ExclusiveZone.Overlap,
                    ),
                    output = monitor.proxy,
                ).getOrElse { error -> fail("background creation failed: $error") }

                background.use {
                    background.waitForConfigure().getOrElse { error -> fail("background never configured: $error") }
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
                SurfaceConfig(
                    namespace = CORNER_NAMESPACE,
                    anchor = setOf(Edge.Bottom, Edge.Right),
                    width = CORNER_WIDTH.dp,
                    height = CORNER_HEIGHT.dp,
                    exclusiveZone = ExclusiveZone.Reserve(CORNER_ZONE.dp),
                    exclusiveEdge = Edge.Right,
                ),
                output = monitor.proxy,
            ).getOrElse { error -> fail("corner surface creation failed: $error") }

            corner.use {
                corner.waitForConfigure().getOrElse { error -> fail("corner surface never configured: $error") }
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
                    SurfaceConfig(
                        namespace = PROBE_NAMESPACE,
                        anchor = setOf(Edge.Top, Edge.Bottom, Edge.Left, Edge.Right),
                        width = SPAN_ANCHORED_AXIS.dp,
                        height = SPAN_ANCHORED_AXIS.dp,
                        exclusiveZone = ExclusiveZone.Yield,
                    ),
                    output = monitor.proxy,
                ).getOrElse { error -> fail("probe surface creation failed: $error") }

                probe.use {
                    probe.waitForConfigure().getOrElse { error -> fail("probe surface never configured: $error") }
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
                val result =
                    LayerShellSurface.create(wayland, topBar(ROUNDED_NAMESPACE, ExclusiveZone.Reserve(amount)))

                when (result) {
                    is Ok -> fail("Reserve($amount) reserves nothing and must be rejected: ${result.value}")
                    is Err -> assertEquals(KortexError.InvalidExclusiveZone(amount), result.error)
                }
            }

            // A pixel is the smallest reservation that means what it says, so it must still be accepted.
            val smallest = LayerShellSurface
                .create(wayland, topBar(ROUNDED_NAMESPACE, ExclusiveZone.Reserve(1.dp)))
                .getOrElse { error -> fail("a one-pixel reservation must be accepted: $error") }
            smallest.use {
                smallest.waitForConfigure().getOrElse { error -> fail("the compositor never configured it: $error") }
            }
        }
    }

    @Test
    fun `an exclusiveEdge the anchor does not pin is rejected before any request is sent`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use { wayland ->
            val anchor = setOf(Edge.Bottom, Edge.Right)
            val result = LayerShellSurface.create(
                wayland,
                SurfaceConfig(
                    namespace = REJECTED_NAMESPACE,
                    anchor = anchor,
                    width = CORNER_WIDTH.dp,
                    height = CORNER_HEIGHT.dp,
                    exclusiveZone = ExclusiveZone.Reserve(CORNER_ZONE.dp),
                    exclusiveEdge = Edge.Top,
                ),
            )

            when (result) {
                is Ok -> fail("an exclusiveEdge the anchor does not pin must be rejected: ${result.value}")
                is Err -> assertEquals(KortexError.InvalidExclusiveEdge(Edge.Top, anchor), result.error)
            }

            // The rejection must happen before any request reaches the compositor, leaving the
            // connection itself unharmed; prove it by using it normally right after.
            val sanity = LayerShellSurface
                .create(wayland, topBar(REJECTED_NAMESPACE, ExclusiveZone.Yield))
                .getOrElse { error -> fail("the connection was left unusable after the rejection: $error") }
            sanity.use {
                sanity.waitForConfigure()
                    .getOrElse { error -> fail("connection did not survive the rejection: $error") }
            }
        }
    }

    /** A bar across the top of the output, the shape whose zone alone is under test. */
    private fun topBar(namespace: String, exclusiveZone: ExclusiveZone) = SurfaceConfig(
        namespace = namespace,
        anchor = setOf(Edge.Top, Edge.Left, Edge.Right),
        width = SPAN_ANCHORED_AXIS.dp,
        height = CORNER_HEIGHT.dp,
        exclusiveZone = exclusiveZone,
    )

    /**
     * The one case in this file that needs no compositor. [LayerShellSurface.requireSupported] is what stands
     * between a corner anchor and a layer shell too old to be told which of the two edges the zone is measured
     * from; below version 5 the compositor deduces the edge from the anchor, which at a corner it cannot do.
     * Hyprland advertises 5, so nothing on this desktop can negotiate a version that reaches it, and the
     * version is passed in rather than bound.
     */
    @Test
    fun `an exclusiveEdge is rejected against a layer shell too old to carry it`() {
        val corner = SurfaceConfig(
            namespace = REJECTED_NAMESPACE,
            anchor = setOf(Edge.Bottom, Edge.Right),
            width = CORNER_WIDTH.dp,
            height = CORNER_HEIGHT.dp,
            exclusiveZone = ExclusiveZone.Reserve(CORNER_ZONE.dp),
            exclusiveEdge = Edge.Bottom,
        )

        assertEquals(
            Err(KortexError.ExclusiveEdgeUnsupported(Edge.Bottom, TOO_OLD_FOR_EXCLUSIVE_EDGE)),
            LayerShellSurface.requireSupported(corner, TOO_OLD_FOR_EXCLUSIVE_EDGE),
            "a shell below set_exclusive_edge's own version must not be sent one",
        )
        assertEquals(
            Ok(Unit),
            LayerShellSurface.requireSupported(corner, LayerShellProtocol.SET_EXCLUSIVE_EDGE_SINCE),
            "the version the request arrives in must take it",
        )
        assertEquals(
            Ok(Unit),
            LayerShellSurface.requireSupported(corner.copy(exclusiveEdge = null), TOO_OLD_FOR_EXCLUSIVE_EDGE),
            "a config naming no edge asks nothing of the version",
        )
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

        // Derived, not written down: a raised SET_EXCLUSIVE_EDGE_SINCE must keep this below it.
        const val TOO_OLD_FOR_EXCLUSIVE_EDGE = LayerShellProtocol.SET_EXCLUSIVE_EDGE_SINCE - 1
    }
}
