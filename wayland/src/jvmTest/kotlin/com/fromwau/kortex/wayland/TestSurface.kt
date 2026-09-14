package com.fromwau.kortex.wayland

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.IError

/**
 * A [LayerSurface] whose content is a lambda, so a test writes a surface inline. Left at its defaults it is a speck
 * in the bottom-right corner, where the pointer is least likely to be, that takes no keyboard focus.
 */
internal class TestSurface<E : IError>(
    namespace: String,
    layer: Layer = Layer.Overlay,
    anchor: Set<Edge> = setOf(Edge.Bottom, Edge.Right),
    width: Dp = SPECK.dp,
    height: Dp = SPECK.dp,
    margins: Margins = Margins.None,
    exclusiveZone: ExclusiveZone = ExclusiveZone.Yield,
    exclusiveEdge: Edge? = null,
    keyboard: KeyboardInteractivity = KeyboardInteractivity.None,
    onClose: (EmptyResult<SurfaceError<E>>) -> Unit = {},
    private val content: @Composable TestSurface<E>.() -> Unit = {},
) : LayerSurface<E>(
    namespace = namespace,
    layer = layer,
    anchor = anchor,
    width = width,
    height = height,
    margins = margins,
    exclusiveZone = exclusiveZone,
    exclusiveEdge = exclusiveEdge,
    keyboard = keyboard,
    onClose = onClose,
) {
    @Composable
    override fun invoke() {
        content()
    }

    private companion object {
        const val SPECK = 8
    }
}
