package com.fromwau.kortex.wayland

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Which layer a surface sits in, numbered as `zwlr_layer_shell_v1.layer` numbers them. Other windows
 * tile around anything below `Overlay`.
 */
public enum class Layer(internal val wireValue: Int) {
    Background(0),
    Bottom(1),
    Top(2),
    Overlay(3),
}

/**
 * An edge a layer surface can be anchored to, carrying `zwlr_layer_surface_v1.anchor`'s bit for it.
 * Anchoring both edges of an [Axis] spans that axis.
 */
public enum class Edge(internal val bit: Int) {
    Top(1),
    Bottom(2),
    Left(4),
    Right(8),
}

/** One of a surface's two axes, each spanned by anchoring both of its [Edge]s. */
public enum class Axis { Horizontal, Vertical }

/** Whether a surface can take keyboard focus, numbered as `zwlr_layer_surface_v1.keyboard_interactivity`. */
public enum class KeyboardInteractivity(internal val wireValue: Int) {
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
    /**
     * Reserves [amount] of screen space, measured inward from the anchored edge, for this surface alone.
     *
     * [amount] must round to at least one logical pixel: reserving none of the screen is [Yield], and
     * reserving less than none is [Overlap].
     */
    public data class Reserve(public val amount: Dp) : ExclusiveZone

    /** Reserves nothing, and asks to be moved clear of the surfaces that do. */
    public data object Yield : ExclusiveZone

    /**
     * Reserves nothing, and asks not to be moved at all but extended to its anchored edges instead,
     * across whatever other surfaces reserve. The wallpaper and lock-screen case.
     */
    public data object Overlap : ExclusiveZone
}

/**
 * What kind of surface to put on screen: where it sits, how much of the output it takes, and what it
 * reserves from the rest of the desktop.
 *
 * @property namespace what the compositor calls the surface, e.g. in `hyprctl layers`.
 * @property anchor the edges the surface is pinned to; pinning both edges of an [Axis] spans that axis.
 * @property width 0 asks the compositor to choose, which requires [anchor] to pin both [Edge.Left] and
 *   [Edge.Right].
 * @property height 0 asks the compositor to choose, like [width], and requires both [Edge.Top] and
 *   [Edge.Bottom].
 * @property exclusiveZone what the surface reserves of the space the compositor tiles other windows
 *   into; what is sensible depends on [anchor], so it has no default.
 * @property keyboard whether the surface can take keyboard focus. Set once and never changed: Hyprland
 *   does not return the keyboard to the focused window when a layer surface drops its interactivity
 *   (hyprwm/Hyprland#8293).
 * @property exclusiveEdge which anchored edge [exclusiveZone] is measured from; only needed when
 *   [anchor] pins a corner, since the protocol cannot deduce one edge from two perpendicular ones.
 */
public data class SurfaceConfig(
    public val namespace: String = "kortex",
    public val layer: Layer = Layer.Top,
    public val anchor: Set<Edge> = setOf(Edge.Top, Edge.Left, Edge.Right),
    public val width: Dp = 0.dp,
    public val height: Dp = 32.dp,
    public val margins: Margins = Margins.None,
    public val exclusiveZone: ExclusiveZone,
    public val keyboard: KeyboardInteractivity = KeyboardInteractivity.None,
    public val exclusiveEdge: Edge? = null,
)
