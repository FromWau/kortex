package com.fromwau.kortex.wayland

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Which layer a surface sits in. Other windows tile around anything below `Overlay`.
 *
 * The wire values are `zwlr_layer_shell_v1.layer`'s own, not this declaration's order.
 */
public enum class Layer(internal val value: Int) {
    Background(0),
    Bottom(1),
    Top(2),
    Overlay(3),
}

/**
 * An edge a layer surface can be anchored to. Anchoring both edges of an [Axis] spans that axis.
 *
 * The wire bits are `zwlr_layer_surface_v1.anchor`'s own, not this declaration's order.
 */
public enum class Edge(internal val bit: Int) {
    Top(1),
    Bottom(2),
    Left(4),
    Right(8),
}

/** One of a surface's two axes, each spanned by anchoring both of its [Edge]s. */
public enum class Axis { Horizontal, Vertical }

/**
 * Whether a surface can take keyboard focus.
 *
 * The wire values are `zwlr_layer_surface_v1.keyboard_interactivity`'s own, not this declaration's order.
 */
public enum class KeyboardInteractivity(internal val value: Int) {
    None(0),
    Exclusive(1),
    OnDemand(2),
}

/**
 * Insets from the anchor point, in `set_margin`'s wire order (top, right, bottom, left) — not the CSS
 * order a reader may assume. A margin on an edge that is not anchored has no effect.
 */
public data class Margins(
    public val top: Dp = 0.dp,
    public val right: Dp = 0.dp,
    public val bottom: Dp = 0.dp,
    public val left: Dp = 0.dp,
) {
    public companion object {
        public val None: Margins = Margins()
    }
}

/**
 * How a surface interacts with the screen space other surfaces reserve.
 *
 * [Reserve] is meaningful only when the anchor pins one edge, or an edge and both edges perpendicular
 * to it, or when a corner anchor names the edge to measure from; anything else reserves nothing.
 */
public sealed interface ExclusiveZone {
    /** Reserves [amount] of screen space, measured inward from the anchored edge, for this surface alone. */
    public data class Reserve(public val amount: Dp) : ExclusiveZone

    /** Reserves nothing, and asks to be moved clear of the surfaces that do. */
    public data object Yield : ExclusiveZone

    /**
     * Reserves nothing, and asks not to be moved at all but extended to its anchored edges instead,
     * across whatever other surfaces reserve. The wallpaper and lock-screen case.
     */
    public data object Overlap : ExclusiveZone
}
