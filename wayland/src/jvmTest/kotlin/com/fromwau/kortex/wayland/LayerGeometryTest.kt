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
 * Pins `set_size`'s explicit width and `set_margin`, whose wire order (top, right, bottom, left) is
 * not the CSS order a reader might assume.
 *
 * Placement is computed from the output's own `wl_output` geometry rather than a hardcoded screen
 * size, so it holds on whichever monitor the test runs against. The anchor is Bottom+Right rather than
 * the more obvious Top+Left because a desktop's own top bar reserves an exclusive zone, which shifts
 * anything anchored to Top.
 */
class LayerGeometryTest {
    @Test
    fun `an explicitly sized, margined, corner-anchored surface lands exactly on the anchor plus margin`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use { wayland ->
            val monitor = bindFirstOutput(wayland)

            val bar = LayerShellSurface.create(
                wayland,
                SurfaceConfig(
                    namespace = NAMESPACE,
                    anchor = setOf(Edge.Bottom, Edge.Right),
                    width = WIDTH.dp,
                    height = HEIGHT.dp,
                    margins = Margins(
                        top = IGNORED_MARGIN.dp, right = MARGIN_RIGHT.dp,
                        bottom = MARGIN_BOTTOM.dp, left = IGNORED_MARGIN.dp,
                    ),
                    exclusiveZone = ExclusiveZone.Yield,
                ),
                output = monitor.proxy,
            ).getOrElse { error -> fail("layer surface creation failed: $error") }

            bar.use {
                bar.waitForConfigure()
                    .getOrElse { error -> fail("compositor never configured the layer surface: $error") }
                wayland.roundtrip()

                val geometry = assertNotNull(Screen.geometry(NAMESPACE), "hyprctl layers does not report $NAMESPACE")

                val monitorLogicalWidth = monitor.geometry.width / monitor.geometry.scale
                val monitorLogicalHeight = monitor.geometry.height / monitor.geometry.scale
                val expectedX = monitor.geometry.x + monitorLogicalWidth - WIDTH - MARGIN_RIGHT
                val expectedY = monitor.geometry.y + monitorLogicalHeight - HEIGHT - MARGIN_BOTTOM

                assertEquals(
                    expectedX, geometry.x, "x: explicit width and/or the right margin never reached the compositor",
                )
                assertEquals(expectedY, geometry.y, "y: the bottom margin never reached the compositor")
                assertEquals(WIDTH, geometry.logicalWidth, "explicit width did not reach the compositor")
                assertEquals(HEIGHT, geometry.logicalHeight, "height regressed")
            }
        }
    }

    @Test
    fun `a bar left at its default width still spans the output`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use { wayland ->
            val monitor = bindFirstOutput(wayland)

            val bar = LayerShellSurface.create(
                wayland,
                topBar(DEFAULT_NAMESPACE, DEFAULT_HEIGHT, ExclusiveZone.Reserve(DEFAULT_HEIGHT.dp)),
                output = monitor.proxy,
            ).getOrElse { error -> fail("layer surface creation failed: $error") }

            bar.use {
                bar.waitForConfigure()
                    .getOrElse { error -> fail("compositor never configured the layer surface: $error") }
                wayland.roundtrip()

                val geometry = assertNotNull(
                    Screen.geometry(DEFAULT_NAMESPACE), "hyprctl layers does not report $DEFAULT_NAMESPACE",
                )
                val monitorLogicalWidth = monitor.geometry.width / monitor.geometry.scale

                assertEquals(monitor.geometry.x, geometry.x, "default bar is not flush with the output's left edge")
                assertEquals(
                    monitorLogicalWidth, geometry.logicalWidth,
                    "leaving width at 0 no longer spans the output",
                )
            }
        }
    }

    @Test
    fun `width left at 0 without both horizontal edges anchored is rejected before any request is sent`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use { wayland ->
            val result = LayerShellSurface.create(
                wayland,
                SurfaceConfig(
                    namespace = REJECTED_NAMESPACE,
                    anchor = setOf(Edge.Top),
                    width = SPAN_ANCHORED_AXIS.dp,
                    height = HEIGHT.dp,
                    exclusiveZone = ExclusiveZone.Reserve(HEIGHT.dp),
                ),
            )

            when (result) {
                is Ok ->
                    fail("a 0-width surface without both horizontal edges anchored must be rejected: ${result.value}")
                is Err -> assertEquals(KortexError.UnspannableAxis(Axis.Horizontal, setOf(Edge.Top)), result.error)
            }

            // The rejection must happen before any request reaches the compositor, leaving the
            // connection itself unharmed; prove it by using it normally right after.
            val sanity = LayerShellSurface
                .create(wayland, topBar(REJECTED_NAMESPACE, HEIGHT, ExclusiveZone.Reserve(HEIGHT.dp)))
                .getOrElse { error -> fail("the connection was left unusable after the rejection: $error") }
            sanity.use {
                sanity.waitForConfigure()
                    .getOrElse { error -> fail("connection did not survive the rejection: $error") }
            }
        }
    }

    @Test
    fun `height left at 0 without both vertical edges anchored is rejected before any request is sent`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use { wayland ->
            // Anchored Left and Right, so the horizontal axis is spannable and only height can be rejected.
            val horizontal = setOf(Edge.Left, Edge.Right)
            val result = LayerShellSurface.create(
                wayland,
                SurfaceConfig(
                    namespace = REJECTED_NAMESPACE,
                    anchor = horizontal,
                    width = SPAN_ANCHORED_AXIS.dp,
                    height = SPAN_ANCHORED_AXIS.dp,
                    exclusiveZone = ExclusiveZone.Yield,
                ),
            )

            when (result) {
                is Ok ->
                    fail("a 0-height surface without both vertical edges anchored must be rejected: ${result.value}")
                is Err -> assertEquals(KortexError.UnspannableAxis(Axis.Vertical, horizontal), result.error)
            }

            val sanity = LayerShellSurface
                .create(wayland, topBar(REJECTED_NAMESPACE, HEIGHT, ExclusiveZone.Reserve(HEIGHT.dp)))
                .getOrElse { error -> fail("the connection was left unusable after the rejection: $error") }
            sanity.use {
                sanity.waitForConfigure()
                    .getOrElse { error -> fail("connection did not survive the rejection: $error") }
            }
        }
    }

    @Test
    fun `a width below 0 is rejected before any request is sent`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use { wayland ->
            // Anchored to Top alone: should the width go out, Hyprland answers its commit by ending the connection.
            val result = LayerShellSurface.create(
                wayland,
                SurfaceConfig(
                    namespace = REJECTED_NAMESPACE,
                    anchor = setOf(Edge.Top),
                    width = NEGATIVE_WIDTH.dp,
                    height = HEIGHT.dp,
                    exclusiveZone = ExclusiveZone.Yield,
                ),
            )

            when (result) {
                is Ok -> fail("a surface with a width below 0 must be rejected: ${result.value}")
                is Err -> assertEquals(KortexError.NegativeSize(Axis.Horizontal, NEGATIVE_WIDTH), result.error)
            }

            val sanity = LayerShellSurface
                .create(wayland, topBar(REJECTED_NAMESPACE, HEIGHT, ExclusiveZone.Reserve(HEIGHT.dp)))
                .getOrElse { error -> fail("the connection was left unusable after the rejection: $error") }
            sanity.use {
                sanity.waitForConfigure()
                    .getOrElse { error -> fail("connection did not survive the rejection: $error") }
            }
        }
    }

    @Test
    fun `setSize leaving an axis at 0 is rejected against the anchor the surface was created with`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use { wayland ->
            val bar = LayerShellSurface
                .create(wayland, pinnedToTop(RESIZED_NAMESPACE))
                .getOrElse { error -> fail("layer surface creation failed: $error") }

            bar.use {
                bar.waitForConfigure()
                    .getOrElse { error -> fail("compositor never configured the layer surface: $error") }

                when (val result = bar.setSize(SPAN_ANCHORED_AXIS, HEIGHT)) {
                    is Ok -> fail("a 0 width on a surface anchored to Top alone must be rejected")
                    is Err ->
                        assertEquals(KortexError.UnspannableAxis(Axis.Horizontal, setOf(Edge.Top)), result.error)
                }

                // A rejected request must not have reached the wire at all, so committing after it is
                // harmless; had set_size gone out, this is where invalid_size would come back.
                bar.commit()
                wayland.roundtrip()
                assertEquals(Ok(Unit), wayland.requireAlive(), "the rejected set_size still reached the compositor")
            }
        }
    }

    @Test
    fun `setSize to a height below 0 is rejected before any request is sent`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use { wayland ->
            val bar = LayerShellSurface
                .create(wayland, pinnedToTop(RESIZED_NAMESPACE))
                .getOrElse { error -> fail("layer surface creation failed: $error") }

            bar.use {
                bar.waitForConfigure()
                    .getOrElse { error -> fail("compositor never configured the layer surface: $error") }

                when (val result = bar.setSize(WIDTH, NEGATIVE_HEIGHT)) {
                    is Ok -> fail("a height below 0 must be rejected")
                    is Err -> assertEquals(KortexError.NegativeSize(Axis.Vertical, NEGATIVE_HEIGHT), result.error)
                }

                bar.commit()
                wayland.roundtrip()
                assertEquals(Ok(Unit), wayland.requireAlive(), "the rejected set_size still reached the compositor")
            }
        }
    }

    @Test
    fun `anchoring all four edges lets both axes be left at 0`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use { wayland ->
            val monitor = bindFirstOutput(wayland)

            val bar = LayerShellSurface.create(
                wayland,
                SurfaceConfig(
                    namespace = SPANNING_NAMESPACE,
                    anchor = setOf(Edge.Top, Edge.Bottom, Edge.Left, Edge.Right),
                    width = SPAN_ANCHORED_AXIS.dp,
                    height = SPAN_ANCHORED_AXIS.dp,
                    exclusiveZone = ExclusiveZone.Yield,
                ),
                output = monitor.proxy,
            ).getOrElse { error -> fail("a fully anchored surface must be allowed to omit both axes: $error") }

            bar.use {
                bar.waitForConfigure()
                    .getOrElse { error -> fail("compositor never configured the layer surface: $error") }
                wayland.roundtrip()

                val geometry = assertNotNull(
                    Screen.geometry(SPANNING_NAMESPACE), "hyprctl layers does not report $SPANNING_NAMESPACE",
                )
                // An exclusive zone of 0 asks to be moved clear of surfaces that do reserve space, so the
                // assigned size is whatever the compositor has left, not necessarily the whole output.
                assertTrue(
                    geometry.logicalWidth in 1..(monitor.geometry.width / monitor.geometry.scale),
                    "omitting width left the compositor no size to assign: ${geometry.logicalWidth}",
                )
                assertTrue(
                    geometry.logicalHeight in 1..(monitor.geometry.height / monitor.geometry.scale),
                    "omitting height left the compositor no size to assign: ${geometry.logicalHeight}",
                )
            }
        }
    }

    /** A bar across the top of the output, spanning its width. */
    private fun topBar(namespace: String, height: Int, exclusiveZone: ExclusiveZone) = SurfaceConfig(
        namespace = namespace,
        anchor = setOf(Edge.Top, Edge.Left, Edge.Right),
        width = SPAN_ANCHORED_AXIS.dp,
        height = height.dp,
        exclusiveZone = exclusiveZone,
    )

    /** Both axes explicit and only Top anchored, so nothing but a later size can be rejected. */
    private fun pinnedToTop(namespace: String) = SurfaceConfig(
        namespace = namespace,
        anchor = setOf(Edge.Top),
        width = WIDTH.dp,
        height = HEIGHT.dp,
        exclusiveZone = ExclusiveZone.Yield,
    )

    private companion object {
        const val NAMESPACE = "kortex-layer-geometry"
        const val DEFAULT_NAMESPACE = "kortex-layer-geometry-default"
        const val REJECTED_NAMESPACE = "kortex-layer-geometry-rejected"
        const val RESIZED_NAMESPACE = "kortex-layer-geometry-resized"
        const val SPANNING_NAMESPACE = "kortex-layer-geometry-spanning"
        const val HEIGHT = 96
        const val WIDTH = 240
        const val MARGIN_RIGHT = 24
        const val MARGIN_BOTTOM = 18

        // Distinct from MARGIN_RIGHT/MARGIN_BOTTOM: a set_margin wire-order mixup that sent this value
        // where the right or bottom margin belongs would otherwise go unnoticed, since top/left aren't
        // anchored here and their own margins must have no effect at all.
        const val IGNORED_MARGIN = 41

        const val DEFAULT_HEIGHT = 32
        const val NEGATIVE_WIDTH = -WIDTH
        const val NEGATIVE_HEIGHT = -HEIGHT
    }
}
