package com.fromwau.kortex.wayland

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.IError
import com.fromwau.kern.result.Result

/**
 * A [LayerSurface] with a test's defaults: left at them it is a speck in the bottom-right corner, where the pointer
 * is least likely to be, that takes no keyboard focus.
 */
@Composable
internal fun <E : IError> TestSurface(
    namespace: String,
    monitor: Monitor? = null,
    layer: Layer = Layer.Overlay,
    anchor: Set<Edge> = setOf(Edge.Bottom, Edge.Right),
    width: Dp = SPECK.dp,
    height: Dp = SPECK.dp,
    margins: Margins = Margins.None,
    exclusiveZone: ExclusiveZone = ExclusiveZone.Yield,
    exclusiveEdge: Edge? = null,
    keyboard: KeyboardInteractivity = KeyboardInteractivity.None,
    visible: Boolean = true,
    onClose: (Result<SurfaceEnd, SurfaceError<E>>) -> Unit = {},
    content: @Composable SurfaceScope<E>.() -> Unit = {},
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
        visible = visible,
        onClose = onClose,
        content = content,
    )
}

private const val SPECK = 8
