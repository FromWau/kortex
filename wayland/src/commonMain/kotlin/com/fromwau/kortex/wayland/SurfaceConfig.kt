package com.fromwau.kortex.wayland

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Which layer a surface sits in, numbered as `zwlr_layer_shell_v1.layer` numbers them. Purely a
 * z-depth: whether other windows tile around a surface is [ExclusiveZone]'s to say, at any layer.
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

/** The two edges that bound this axis. */
internal val Axis.edges: Set<Edge>
    get() = when (this) {
        Axis.Horizontal -> setOf(Edge.Left, Edge.Right)
        Axis.Vertical -> setOf(Edge.Top, Edge.Bottom)
    }

/** Whether a surface can take keyboard focus, numbered as `zwlr_layer_surface_v1.keyboard_interactivity`. */
public enum class KeyboardInteractivity(internal val wireValue: Int) {
    None(0),
    Exclusive(1),
    OnDemand(2),
}

/**
 * Insets from the anchor point, top, right, bottom and left, in the order CSS writes them. A margin on an edge that
 * is not anchored has no effect.
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
 * Every setting of a layer surface but its monitor, as [LayerSurface] places one, and every one of them reaches a
 * surface already placed. The companion's presets write each kind's placement rule once, for the preset composables
 * to pass on.
 *
 * [anchor], [width], [height] and [exclusiveZone] have no default: each is only sensible in the light of the others,
 * so a default would shape a surface nobody asked for.
 *
 * @property namespace what the compositor calls the surface, e.g. in `hyprctl layers`.
 * @property anchor the edges the surface is pinned to; pinning both edges of an [Axis] spans that axis.
 *   Pinning nothing centres the surface, which then needs an explicit [width] and [height].
 * @property width 0 asks the compositor to choose, which requires [anchor] to pin both [Edge.Left] and
 *   [Edge.Right]; without them you get [KortexError.UnspannableAxis].
 * @property height 0 asks the compositor to choose, like [width], and requires both [Edge.Top] and
 *   [Edge.Bottom].
 * @property exclusiveZone what the surface reserves of the space the compositor tiles other windows
 *   into; what is sensible depends on [anchor].
 * @property keyboard whether the surface can take keyboard focus.
 * @property exclusiveEdge which anchored edge [exclusiveZone] is measured from; only needed when
 *   [anchor] pins a corner, since the protocol cannot deduce one edge from two perpendicular ones.
 */
internal data class SurfaceConfig(
    val namespace: String = "kortex",
    val layer: Layer = Layer.Top,
    val anchor: Set<Edge>,
    val width: Dp,
    val height: Dp,
    val margins: Margins = Margins.None,
    val exclusiveZone: ExclusiveZone,
    val keyboard: KeyboardInteractivity = KeyboardInteractivity.None,
    val exclusiveEdge: Edge? = null,
) {
    companion object {
        /**
         * A surface that hugs [edge] and spans it, anchored to [edge] plus the two edges perpendicular
         * to it: a top bar anchors Top, Left and Right, for example.
         *
         * @param thickness how far the surface extends from [edge], and the screen space it reserves.
         *   It must round to at least one logical pixel, as [ExclusiveZone.Reserve] does.
         * @param length how far the surface runs along [edge]; 0 (the default) spans the whole edge.
         */
        fun panel(edge: Edge, thickness: Dp, length: Dp = 0.dp): SurfaceConfig {
            val (width, height, perpendicular) = when (edge) {
                Edge.Top, Edge.Bottom -> Triple(length, thickness, Axis.Horizontal.edges)
                Edge.Left, Edge.Right -> Triple(thickness, length, Axis.Vertical.edges)
            }
            return SurfaceConfig(
                anchor = setOf(edge) + perpendicular,
                width = width,
                height = height,
                exclusiveZone = ExclusiveZone.Reserve(thickness),
            )
        }

        /** A [panel] with [KeyboardInteractivity.OnDemand], for one a user types or clicks into. */
        fun dock(edge: Edge, thickness: Dp, length: Dp = 0.dp): SurfaceConfig =
            panel(edge, thickness, length).copy(keyboard = KeyboardInteractivity.OnDemand)

        /**
         * Fills the whole output beneath every other surface, reserving nothing and never moved out of
         * another surface's way.
         */
        fun desktopBackground(): SurfaceConfig = SurfaceConfig(
            layer = Layer.Background,
            anchor = setOf(Edge.Top, Edge.Bottom, Edge.Left, Edge.Right),
            width = 0.dp,
            height = 0.dp,
            exclusiveZone = ExclusiveZone.Overlap,
        )

        /**
         * Fills the whole output above every other surface, taking keyboard focus exclusively and
         * never moved out of another surface's way.
         *
         * This is not a real lock screen: kortex binds no `ext-session-lock-v1`, so nothing stops
         * another surface from drawing over or beside it, or the compositor from switching away.
         * Mistaking this for an actual session lock is a security problem, not a layout one.
         */
        fun lockScreen(): SurfaceConfig = SurfaceConfig(
            layer = Layer.Overlay,
            anchor = setOf(Edge.Top, Edge.Bottom, Edge.Left, Edge.Right),
            width = 0.dp,
            height = 0.dp,
            exclusiveZone = ExclusiveZone.Overlap,
            keyboard = KeyboardInteractivity.Exclusive,
        )

        /**
         * A surface of exactly [width] by [height], centred on the output by anchoring nothing.
         *
         * Centring an unanchored surface only works by yielding, not overlapping. [ExclusiveZone.Overlap]
         * means "extend to the anchored edges", and an unanchored surface has none, so the request says
         * nothing and a compositor may show no surface at all. [ExclusiveZone.Yield] centres it in the *usable*
         * area instead, so another surface's own exclusive zone can shift it off the output's true centre.
         */
        fun osd(width: Dp, height: Dp): SurfaceConfig = SurfaceConfig(
            layer = Layer.Overlay,
            anchor = emptySet(),
            width = width,
            height = height,
            exclusiveZone = ExclusiveZone.Yield,
        )

        /**
         * An [osd] that also takes keyboard focus on demand, for a floating panel whose content dismisses it
         * with `close()`. It inherits [osd]'s placement rules.
         */
        fun appMenu(width: Dp, height: Dp): SurfaceConfig =
            osd(width, height).copy(keyboard = KeyboardInteractivity.OnDemand)
    }
}
