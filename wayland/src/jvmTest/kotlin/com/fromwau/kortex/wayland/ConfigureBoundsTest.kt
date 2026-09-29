package com.fromwau.kortex.wayland

import androidx.compose.ui.unit.IntSize
import java.lang.foreign.MemorySegment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What a window reads out of `xdg_toplevel.configure_bounds`, the largest size a compositor recommends for it.
 *
 * Driven here rather than against a compositor, because Hyprland 0.56.2 sends none: a window's whole life on
 * the wire in `WindowDragWireTest` carries not one, so a live assertion would read the same whether the event
 * reached the window or not.
 *
 * Needs no compositor: the event carries two ints and nothing else.
 */
class ConfigureBoundsTest {
    @Test
    fun `a window recommends no size until the compositor has recommended one`() {
        val listener = XdgToplevelListener(WIDTH, HEIGHT)

        assertNull(
            listener.recommendedMaxSize,
            "a window nobody has told anything reports a recommendation it was never given",
        )
    }

    @Test
    fun `a window reports the size the compositor recommended`() {
        val listener = XdgToplevelListener(WIDTH, HEIGHT)

        listener.onConfigureBounds(NULL, NULL, BOUND_WIDTH, BOUND_HEIGHT)

        assertEquals(
            IntSize(BOUND_WIDTH, BOUND_HEIGHT), listener.recommendedMaxSize,
            "the recommended size did not reach the window, or reached it transposed",
        )
    }

    @Test
    fun `an axis the compositor recommends nothing for stays a zero rather than becoming no recommendation`() {
        val listener = XdgToplevelListener(WIDTH, HEIGHT)

        listener.onConfigureBounds(NULL, NULL, 0, BOUND_HEIGHT)

        assertEquals(
            IntSize(0, BOUND_HEIGHT), listener.recommendedMaxSize,
            "a recommendation for one axis alone was read as a recommendation for neither",
        )
    }

    private companion object {
        val NULL: MemorySegment = MemorySegment.NULL
        const val WIDTH = 640
        const val HEIGHT = 480
        // Distinct from each other and from the window's own size, so a transposed or copied axis shows.
        const val BOUND_WIDTH = 1731
        const val BOUND_HEIGHT = 974
    }
}
