package com.fromwau.kortex.wayland

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
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
 * clear of it. It takes no keyboard focus; a [Dock] is placed the same way and can take it.
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

/**
 * Placed as a [Panel] is, along one edge of its monitor, spanning that edge and reserving its own thickness there, and
 * able to take keyboard focus, as a text field in it needs.
 *
 * Extend it as you would [LayerSurface] and show it with [Show]. [E] is the error your content can end it with through
 * `close(error)`, or `Nothing` for none.
 *
 * @param monitor the monitor to put the dock on, one [rememberMonitors] lists; null lets the compositor choose.
 * @param edge the edge the dock runs along. It is pinned to that edge and the two beside it.
 * @param thickness how far the dock reaches in from [edge], which is also the space it reserves there. It must round
 *   to at least one logical pixel, or the dock is not placed and `onClose` receives `Err(SurfaceError.Failed(...))`.
 * @param length how far the dock runs along [edge]; 0 spans the whole edge.
 * @param margins insets from the edges the dock is pinned to; a margin on the edge opposite [edge] has no effect.
 * @param namespace what the compositor calls the dock, e.g. in `hyprctl layers`, exactly as written.
 * @param onClose called once when the dock ends, as [LayerSurface.onClose] describes.
 */
public abstract class Dock<E : IError>(
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
        .dock(edge, thickness, length)
        .copy(namespace = namespace, margins = margins),
    onClose = onClose,
)

/**
 * Fills its whole monitor on the lowest layer, beneath every window: a wallpaper, say. It reserves nothing, takes no
 * keyboard focus, and covers the space other surfaces reserve rather than moving out of their way.
 *
 * Extend it as you would [LayerSurface] and show it with [Show]. [E] is the error your content can end it with through
 * `close(error)`, or `Nothing` for none.
 *
 * @param monitor the monitor to fill, one [rememberMonitors] lists; null lets the compositor choose.
 * @param namespace what the compositor calls the background, e.g. in `hyprctl layers`, exactly as written.
 * @param onClose called once when the background ends, as [LayerSurface.onClose] describes.
 */
public abstract class DesktopBackground<E : IError>(
    monitor: Monitor? = null,
    namespace: String = "kortex",
    onClose: (EmptyResult<SurfaceError<E>>) -> Unit = {},
) : LayerSurface<E>(
    monitor = monitor,
    config = SurfaceConfig.desktopBackground().copy(namespace = namespace),
    onClose = onClose,
)

/**
 * Fills its whole monitor on the topmost layer, above every window, and takes the keyboard for as long as it is shown.
 * It reserves nothing, and covers the space other surfaces reserve rather than moving out of their way.
 *
 * It does not lock the session. kortex does not use `ext-session-lock-v1`, the protocol that locks one, so nothing
 * stops another surface from drawing over or beside it, or the compositor from switching away from it. Mistaking it
 * for a session lock is a security problem, not a layout one.
 *
 * Extend it as you would [LayerSurface] and show it with [Show]. [E] is the error your content can end it with through
 * `close(error)`, or `Nothing` for none.
 *
 * @param monitor the monitor to cover, one [rememberMonitors] lists; null lets the compositor choose.
 * @param namespace what the compositor calls the lock screen, e.g. in `hyprctl layers`, exactly as written.
 * @param onClose called once when the lock screen ends, as [LayerSurface.onClose] describes.
 */
public abstract class LockScreen<E : IError>(
    monitor: Monitor? = null,
    namespace: String = "kortex",
    onClose: (EmptyResult<SurfaceError<E>>) -> Unit = {},
) : LayerSurface<E>(
    monitor = monitor,
    config = SurfaceConfig.lockScreen().copy(namespace = namespace),
    onClose = onClose,
)

/**
 * A floating surface of exactly [width] by [height] on the topmost layer, above every window: a volume indicator, say.
 * It is centred in the space other surfaces leave free on its monitor, so a bar's reserved space shifts it off the
 * monitor's true centre. It takes no keyboard focus; an [AppMenu] is placed the same way and can take it.
 *
 * Extend it as you would [LayerSurface] and show it with [Show]. [E] is the error your content can end it with through
 * `close(error)`, or `Nothing` for none.
 *
 * @param monitor the monitor to put it on, one [rememberMonitors] lists; null lets the compositor choose.
 * @param width its width. It must round to at least one logical pixel, or it is not placed and `onClose` receives
 *   `Err(SurfaceError.Failed(...))`.
 * @param height its height, which must round to at least one logical pixel as [width] must.
 * @param namespace what the compositor calls it, e.g. in `hyprctl layers`, exactly as written.
 * @param onClose called once when it ends, as [LayerSurface.onClose] describes.
 */
public abstract class Osd<E : IError>(
    monitor: Monitor? = null,
    width: Dp,
    height: Dp,
    namespace: String = "kortex",
    onClose: (EmptyResult<SurfaceError<E>>) -> Unit = {},
) : LayerSurface<E>(
    monitor = monitor,
    config = SurfaceConfig.osd(width, height).copy(namespace = namespace),
    onClose = onClose,
)

/**
 * Placed as an [Osd] is, a floating surface of exactly [width] by [height] centred in the space other surfaces leave
 * free on its monitor, and able to take keyboard focus: a launcher you type into, say, which its content dismisses
 * with `close()`.
 *
 * Extend it as you would [LayerSurface] and show it with [Show]. [E] is the error your content can end it with through
 * `close(error)`, or `Nothing` for none.
 *
 * @param monitor the monitor to put it on, one [rememberMonitors] lists; null lets the compositor choose.
 * @param width its width. It must round to at least one logical pixel, or it is not placed and `onClose` receives
 *   `Err(SurfaceError.Failed(...))`.
 * @param height its height, which must round to at least one logical pixel as [width] must.
 * @param namespace what the compositor calls it, e.g. in `hyprctl layers`, exactly as written.
 * @param onClose called once when it ends, as [LayerSurface.onClose] describes.
 */
public abstract class AppMenu<E : IError>(
    monitor: Monitor? = null,
    width: Dp,
    height: Dp,
    namespace: String = "kortex",
    onClose: (EmptyResult<SurfaceError<E>>) -> Unit = {},
) : LayerSurface<E>(
    monitor = monitor,
    config = SurfaceConfig.appMenu(width, height).copy(namespace = namespace),
    onClose = onClose,
)

/**
 * A menu of the `size` you give it, opened at [at] on its monitor, on the topmost layer above every window. It opens
 * down and to the right of [at], unless it would then run past the monitor's right or bottom edge: it opens to the
 * left of [at] instead, or upwards from it, each direction decided on its own. It takes no keyboard focus.
 *
 * It measures its monitor unrotated, so on a monitor turned a quarter it can open past the monitor's edges.
 *
 * ```kotlin
 * class Menu(monitor: Monitor, at: IntOffset, onClose: (EmptyResult<SurfaceError<Nothing>>) -> Unit) :
 *     ContextMenu<Nothing>(monitor = monitor, at = at, size = IntSize(160, 120), onClose = onClose) {
 *     @Composable
 *     override fun invoke() {
 *         Text("Close", Modifier.clickable { close() })
 *     }
 * }
 * ```
 *
 * Extend it as you would [LayerSurface] and show it with [Show]. [E] is the error your content can end it with through
 * `close(error)`, or `Nothing` for none.
 *
 * @param monitor the monitor the menu opens on, one [rememberMonitors] lists.
 * @param at where the menu opens, in logical pixels from [monitor]'s top-left corner, however much of the monitor
 *   other surfaces reserve.
 * @param size the menu's size in logical pixels.
 * @param namespace what the compositor calls the menu, e.g. in `hyprctl layers`, exactly as written.
 * @param onClose called once when the menu ends, as [LayerSurface.onClose] describes.
 */
public abstract class ContextMenu<E : IError>(
    monitor: Monitor,
    at: IntOffset,
    size: IntSize,
    namespace: String = "kortex",
    onClose: (EmptyResult<SurfaceError<E>>) -> Unit = {},
) : LayerSurface<E>(
    monitor = monitor,
    config = SurfaceConfig
        .contextMenu(at = at, menuSize = size, outputSize = monitor.geometry.logicalSize)
        .copy(namespace = namespace),
    onClose = onClose,
)

// Leaves the transform out: a monitor turned a quarter keeps its mode's unturned width and height.
private val OutputGeometry.logicalSize: IntSize get() = IntSize(width / scale, height / scale)
