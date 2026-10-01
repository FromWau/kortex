package com.fromwau.kortex.wayland

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A [LayerSurface] with a test's defaults: left at them it is a speck in the bottom-right corner, where the pointer
 * is least likely to be, that takes no keyboard focus.
 */
@Composable
internal fun TestSurface(
    namespace: String,
    monitor: Monitor? = null,
    layer: Layer = Layer.Overlay,
    anchor: Set<Edge> = setOf(Edge.Bottom, Edge.Right),
    width: Length = Length.Of(SPECK.dp),
    height: Length = Length.Of(SPECK.dp),
    margins: Margins = Margins.None,
    exclusiveZone: ExclusiveZone = ExclusiveZone.Yield,
    exclusiveEdge: Edge? = null,
    keyboard: KeyboardInteractivity = KeyboardInteractivity.None,
    state: SurfaceState = rememberSurfaceState(),
    content: @Composable SurfaceScope.() -> Unit = {},
) {
    LayerSurface(
        monitor = monitor,
        namespace = namespace,
        layer = layer,
        anchor = anchor,
        width = width,
        height = height,
        margins = margins,
        exclusiveZone = exclusiveZone,
        exclusiveEdge = exclusiveEdge,
        keyboard = keyboard,
        state = state,
        content = content,
    )
}

private const val SPECK = 8
