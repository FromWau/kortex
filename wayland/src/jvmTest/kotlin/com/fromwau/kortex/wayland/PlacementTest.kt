package com.fromwau.kortex.wayland

import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every rule a layer surface's lengths have to keep, asked of the rule itself with no compositor.
 *
 * Covered until now only through a live surface that ends with the error, which costs a desktop and a
 * compositor to learn that 0 is not a width. The rule is a function, so it can be asked directly.
 */
class PlacementTest {
    @Test
    fun `a bar spans the axis it anchors both edges of`() {
        assertEquals(Ok(Unit), placeable(width = Length.WholeAxis, anchor = BAR_ANCHOR))
    }

    @Test
    fun `spanning an axis that is not anchored at both edges is refused`() {
        assertEquals(
            Err(KortexError.UnspannableAxis(Axis.Horizontal, setOf(Edge.Top))),
            placeable(width = Length.WholeAxis, anchor = setOf(Edge.Top)),
        )
    }

    /**
     * The reason [Length] exists: both of these are a 0 in `set_size`, and they are opposite mistakes.
     *
     * While a length was a `Dp`, asking for none of an axis and asking for all of it were the same
     * request, and on a bar, whose perpendicular edges are always anchored, the one that got through was
     * "all of it". A caller whose arithmetic came out at 0 got a full-width bar and no error.
     */
    @Test
    fun `a length of nothing is refused, and is not mistaken for spanning`() {
        assertEquals(
            Err(KortexError.EmptyLength(Axis.Horizontal)),
            placeable(width = Length.Of(0.dp), anchor = BAR_ANCHOR),
        )
    }

    /** Judged on the rounded pixel, as the doc says, so a length too small to draw is refused too. */
    @Test
    fun `a length that rounds to nothing is refused`() {
        assertEquals(
            Err(KortexError.EmptyLength(Axis.Horizontal)),
            placeable(width = Length.Of(0.4.dp), anchor = BAR_ANCHOR),
        )
    }

    @Test
    fun `a negative length is its own refusal`() {
        assertEquals(
            Err(KortexError.NegativeSize(Axis.Horizontal, -2)),
            placeable(width = Length.Of((-2).dp), anchor = BAR_ANCHOR),
        )
    }

    @Test
    fun `the same rules hold down the vertical axis`() {
        assertEquals(
            Err(KortexError.EmptyLength(Axis.Vertical)),
            placeable(height = Length.Of(0.dp), anchor = BAR_ANCHOR),
        )
        assertEquals(
            Err(KortexError.UnspannableAxis(Axis.Vertical, BAR_ANCHOR)),
            placeable(height = Length.WholeAxis, anchor = BAR_ANCHOR),
        )
    }

    /** Width first, so a caller fixing one error at a time is told about them in a fixed order. */
    @Test
    fun `width is judged before height`() {
        assertEquals(
            Err(KortexError.EmptyLength(Axis.Horizontal)),
            placeable(width = Length.Of(0.dp), height = Length.Of(0.dp), anchor = BAR_ANCHOR),
        )
    }

    private fun placeable(
        width: Length = Length.Of(SIDE.dp),
        height: Length = Length.Of(SIDE.dp),
        anchor: Set<Edge>,
    ) = LayerShellSurface.requirePlaceable(
        SurfaceConfig(
            anchor = anchor,
            width = width,
            height = height,
            exclusiveZone = ExclusiveZone.Yield,
        ),
    )

    private companion object {
        /** What a top bar anchors: the edge it hugs, and both edges of the axis it spans. */
        val BAR_ANCHOR = setOf(Edge.Top, Edge.Left, Edge.Right)
        const val SIDE = 32
    }
}
