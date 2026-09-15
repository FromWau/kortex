package com.fromwau.kortex.wayland

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.IError

/**
 * A bar along one edge of its monitor, spanning that edge and reserving its own thickness there, so windows tile clear
 * of it. Left at its defaults, it runs along the top edge, 32 dp thick, and takes no keyboard focus.
 *
 * ```kotlin
 * class Clock(monitor: Monitor) : Bar<Nothing>(monitor = monitor, namespace = "clock-${monitor.name}") {
 *     @Composable
 *     override fun invoke() {
 *         Text("12:00")
 *     }
 * }
 * ```
 *
 * Extend it as you would [LayerSurface] and show it with [Show]. [E] is the error your content can end it with through
 * `close(error)`, or `Nothing` for none.
 *
 * @param monitor the monitor to put the bar on, one [rememberMonitors] lists; null lets the compositor choose.
 * @param edge the edge the bar runs along. It is pinned to that edge and the two beside it.
 * @param thickness how far the bar reaches in from [edge], which is also the space it reserves there. It must round to
 *   at least one logical pixel, or the bar is not placed and `onClose` receives `Err(SurfaceError.Failed(...))`.
 * @param length how far the bar runs along [edge]; 0 spans the whole edge.
 * @param margins insets from the edges the bar is pinned to; a margin on the edge opposite [edge] has no effect.
 * @param keyboard whether the bar can take keyboard focus, as a text field in it needs.
 * @param namespace what the compositor calls the bar, e.g. in `hyprctl layers`, exactly as written.
 * @param onClose called once when the bar ends, as [LayerSurface.onClose] describes.
 */
public abstract class Bar<E : IError>(
    monitor: Monitor? = null,
    edge: Edge = Edge.Top,
    thickness: Dp = 32.dp,
    length: Dp = 0.dp,
    margins: Margins = Margins.None,
    keyboard: KeyboardInteractivity = KeyboardInteractivity.None,
    namespace: String = "kortex",
    onClose: (EmptyResult<SurfaceError<E>>) -> Unit = {},
) : LayerSurface<E>(
    monitor = monitor,
    config = SurfaceConfig
        .panel(edge, thickness, length)
        .copy(namespace = namespace, margins = margins, keyboard = keyboard),
    onClose = onClose,
)

/**
 * A surface along one edge of its monitor, spanning that edge and reserving its own thickness there, so windows tile
 * clear of it. It takes no keyboard focus; a [Dock] is placed the same way and takes it on demand.
 *
 * Extend it as you would [LayerSurface] and show it with [Show]. [E] is the error your content can end it with through
 * `close(error)`, or `Nothing` for none.
 *
 * @param monitor the monitor to put the panel on, one [rememberMonitors] lists; null lets the compositor choose.
 * @param edge the edge the panel runs along. It is pinned to that edge and the two beside it.
 * @param thickness how far the panel reaches in from [edge], which is also the space it reserves there. It must round
 *   to at least one logical pixel, or the panel is not placed and `onClose` receives `Err(SurfaceError.Failed(...))`.
 * @param length how far the panel runs along [edge]; 0 spans the whole edge.
 * @param margins insets from the edges the panel is pinned to; a margin on the edge opposite [edge] has no effect.
 * @param namespace what the compositor calls the panel, e.g. in `hyprctl layers`, exactly as written.
 * @param onClose called once when the panel ends, as [LayerSurface.onClose] describes.
 */
public abstract class Panel<E : IError>(
    monitor: Monitor? = null,
    edge: Edge,
    thickness: Dp,
    length: Dp = 0.dp,
    margins: Margins = Margins.None,
    namespace: String = "kortex",
    onClose: (EmptyResult<SurfaceError<E>>) -> Unit = {},
) : LayerSurface<E>(
    monitor = monitor,
    config = SurfaceConfig
        .panel(edge, thickness, length)
        .copy(namespace = namespace, margins = margins),
    onClose = onClose,
)
