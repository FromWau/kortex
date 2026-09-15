package com.fromwau.kortex.wayland

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins [SurfaceConfig.contextMenu]'s flip: which [MenuAnchor] corner it picks for a point near each
 * edge and each corner of the output, and the boundary right at a menu's own width or height, where a
 * one-pixel difference must change the answer. Also pins that [ContextMenu] flips against its monitor's
 * logical size, its mode's size over its scale.
 *
 * A pure function of its three inputs, so every case here is exact math against [OUTPUT] and
 * [MENU_SIZE], with no compositor involved.
 */
class MenuAnchorTest {
    @Test
    fun `a point well clear of every edge opens down and right, unflipped`() {
        val config = SurfaceConfig.contextMenu(at = IntOffset(CLEAR_X, CLEAR_Y), MENU_SIZE, OUTPUT)

        assertEquals(setOf(Edge.Top, Edge.Left), config.anchor, "an unflipped menu must anchor Top+Left")
        assertEquals(Margins(top = CLEAR_Y.dp, left = CLEAR_X.dp), config.margins)
    }

    @Test
    fun `a point near the right edge flips horizontally only`() {
        val at = IntOffset(OUTPUT.width - CLEAR_MARGIN, CLEAR_Y)
        val config = SurfaceConfig.contextMenu(at, MENU_SIZE, OUTPUT)

        assertEquals(setOf(Edge.Top, Edge.Right), config.anchor, "only the horizontal axis should have flipped")
        assertEquals(Margins(top = CLEAR_Y.dp, right = (OUTPUT.width - at.x).dp), config.margins)
    }

    @Test
    fun `a point near the bottom edge flips vertically only`() {
        val at = IntOffset(CLEAR_X, OUTPUT.height - CLEAR_MARGIN)
        val config = SurfaceConfig.contextMenu(at, MENU_SIZE, OUTPUT)

        assertEquals(setOf(Edge.Bottom, Edge.Left), config.anchor, "only the vertical axis should have flipped")
        assertEquals(Margins(bottom = (OUTPUT.height - at.y).dp, left = CLEAR_X.dp), config.margins)
    }

    @Test
    fun `a point near the bottom-right corner flips both axes`() {
        val at = IntOffset(OUTPUT.width - CLEAR_MARGIN, OUTPUT.height - CLEAR_MARGIN)
        val config = SurfaceConfig.contextMenu(at, MENU_SIZE, OUTPUT)

        assertEquals(setOf(Edge.Bottom, Edge.Right), config.anchor, "both axes should have flipped")
        assertEquals(
            Margins(bottom = (OUTPUT.height - at.y).dp, right = (OUTPUT.width - at.x).dp),
            config.margins,
        )
    }

    @Test
    fun `a point near the top-left corner never flips`() {
        val at = IntOffset(CLEAR_MARGIN, CLEAR_MARGIN)
        val config = SurfaceConfig.contextMenu(at, MENU_SIZE, OUTPUT)

        assertEquals(setOf(Edge.Top, Edge.Left), config.anchor, "the corner nearest the default opening stays put")
        assertEquals(Margins(top = at.y.dp, left = at.x.dp), config.margins)
    }

    @Test
    fun `a menu exactly as wide as the room left of the point does not flip`() {
        val at = IntOffset(OUTPUT.width - MENU_SIZE.width, CLEAR_Y)
        val config = SurfaceConfig.contextMenu(at, MENU_SIZE, OUTPUT)

        assertEquals(setOf(Edge.Top, Edge.Left), config.anchor, "a menu that fits exactly must not flip")
    }

    @Test
    fun `a menu one pixel wider than the room left of the point flips`() {
        val at = IntOffset(OUTPUT.width - MENU_SIZE.width + 1, CLEAR_Y)
        val config = SurfaceConfig.contextMenu(at, MENU_SIZE, OUTPUT)

        assertEquals(setOf(Edge.Top, Edge.Right), config.anchor, "one pixel too wide must flip")
    }

    @Test
    fun `a menu larger than the output still flips towards the inside on both axes`() {
        val huge = IntSize(OUTPUT.width + OVERSIZE, OUTPUT.height + OVERSIZE)
        val at = IntOffset(CLEAR_X, CLEAR_Y)
        val config = SurfaceConfig.contextMenu(at, huge, OUTPUT)

        assertEquals(
            setOf(Edge.Bottom, Edge.Right), config.anchor,
            "a menu too large to fit on either axis still resolves to a single, consistent corner",
        )
        assertEquals(
            Margins(bottom = (OUTPUT.height - at.y).dp, right = (OUTPUT.width - at.x).dp), config.margins,
        )
    }

    @Test
    fun `a ContextMenu near its monitor's bottom-right corner flips both axes, against the monitor's logical size`() {
        val mode = IntSize(OUTPUT.width * SCALE, OUTPUT.height * SCALE)
        withUnboundMonitors(MONITOR_NAME, mode = mode, scale = SCALE) { (monitor) ->
            val at = IntOffset(OUTPUT.width - CLEAR_MARGIN, OUTPUT.height - CLEAR_MARGIN)
            val menu = object : ContextMenu<Nothing>(monitor = monitor, at = at, size = MENU_SIZE) {
                @Composable
                override fun invoke() = Unit
            }

            assertEquals(
                setOf(Edge.Bottom, Edge.Right),
                menu.anchor,
                "a point this near the monitor's logical bottom-right corner did not flip both axes",
            )
            assertEquals(
                Margins(bottom = (OUTPUT.height - at.y).dp, right = (OUTPUT.width - at.x).dp),
                menu.margins,
                "the menu's margins were not measured from the monitor's logical edges",
            )
        }
    }

    private companion object {
        val OUTPUT = IntSize(1920, 1080)
        val MENU_SIZE = IntSize(200, 100)

        const val CLEAR_MARGIN = 10
        const val CLEAR_X = 50
        const val CLEAR_Y = 50
        const val OVERSIZE = 500

        // A monitor whose mode is OUTPUT at this scale, so OUTPUT is its logical size.
        const val SCALE = 2
        const val MONITOR_NAME = "MENU-1"
    }
}
