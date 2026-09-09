package com.fromwau.kortex.wayland

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Which layer a surface sits in. Other windows tile around anything below `Overlay`. */
public enum class Layer { Background, Bottom, Top, Overlay }

/** One of a surface's two axes, each spanned by pinning both of its [Anchor] edges. */
public enum class Axis { Horizontal, Vertical }

public enum class KeyboardInteractivity { None, Exclusive, OnDemand }

/**
 * Insets from the anchor point, in `set_margin`'s wire order (top, right, bottom, left) — not the CSS
 * order a reader may assume. A margin on an edge that [Anchor] does not pin has no effect.
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
