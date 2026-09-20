package com.fromwau.kortex.wayland

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.IError
import com.fromwau.kern.result.Result

/**
 * A bar along one edge of its monitor, spanning that edge and reserving its own thickness there, so windows tile clear
 * of it. Left at its defaults, it runs along the top edge, 32 dp thick, and takes no keyboard focus.
 *
 * ```kotlin
 * kortexApplication {
 *     val monitors by rememberMonitors()
 *     for (monitor in monitors) key(monitor) {
 *         Bar<Nothing>(monitor = monitor, namespace = "clock-${monitor.name}") {
 *             Text("12:00")
 *         }
 *     }
 * }
 * ```
 *
 * It is a [LayerSurface] with a bar's placement fixed; every other setting is this call's own.
 *
 * @param E the error your content can end it with through `close(error)`, or `Nothing` for none.
 * @param monitor the monitor to put the bar on, one [rememberMonitors] lists; null lets the compositor choose.
 * @param edge the edge the bar runs along. It is pinned to that edge and the two beside it.
 * @param thickness how far the bar reaches in from [edge], which is also the space it reserves there. It must round to
 *   at least one logical pixel, or the bar is not placed and `onClose` receives `Err(SurfaceError.Failed(...))`.
 * @param length how far the bar runs along [edge]; 0 spans the whole edge. It must not round below 0, or the bar is
 *   not placed and `onClose` receives `Err(SurfaceError.Failed(...))`.
 * @param margins insets from the edges the bar is pinned to; a margin on the edge opposite [edge] has no effect.
 * @param keyboard whether the bar can take keyboard focus, as a text field in it needs.
 * @param namespace what the compositor calls the bar, e.g. in `hyprctl layers`, exactly as written.
 * @param visible whether the bar is on screen. `false` takes it off screen and hands back the space it
 *   reserves, while its content keeps running and keeps its state; `true` puts it back.
 * @param onClose called once when the bar ends, as [LayerSurface]'s `onClose` describes.
 * @param content what is drawn on the bar, with the bar itself as `this`.
 */
@Composable
public fun <E : IError> Bar(
    monitor: Monitor? = null,
    edge: Edge = Edge.Top,
    thickness: Dp = 32.dp,
    length: Dp = 0.dp,
    margins: Margins = Margins.None,
    keyboard: KeyboardInteractivity = KeyboardInteractivity.None,
    namespace: String = "kortex",
    visible: Boolean = true,
    onClose: (Result<SurfaceEnd, SurfaceError<E>>) -> Unit = {},
    content: @Composable SurfaceScope<E>.() -> Unit,
) {
    LayerSurface(
        monitor = monitor,
        config = SurfaceConfig
            .panel(edge, thickness, length)
            .copy(namespace = namespace, margins = margins, keyboard = keyboard),
        visible = visible,
        onClose = onClose,
        content = content,
    )
}

/**
 * A surface along one edge of its monitor, spanning that edge and reserving its own thickness there, so windows tile
 * clear of it. It takes no keyboard focus; a [Dock] is placed the same way and can take it.
 *
 * It is a [LayerSurface] with a panel's placement fixed; every other setting is this call's own.
 *
 * @param E the error your content can end it with through `close(error)`, or `Nothing` for none.
 * @param monitor the monitor to put the panel on, one [rememberMonitors] lists; null lets the compositor choose.
 * @param edge the edge the panel runs along. It is pinned to that edge and the two beside it.
 * @param thickness how far the panel reaches in from [edge], which is also the space it reserves there. It must round
 *   to at least one logical pixel, or the panel is not placed and `onClose` receives `Err(SurfaceError.Failed(...))`.
 * @param length how far the panel runs along [edge]; 0 spans the whole edge. It must not round below 0, or the panel
 *   is not placed and `onClose` receives `Err(SurfaceError.Failed(...))`.
 * @param margins insets from the edges the panel is pinned to; a margin on the edge opposite [edge] has no effect.
 * @param namespace what the compositor calls the panel, e.g. in `hyprctl layers`, exactly as written.
 * @param visible whether the panel is on screen. `false` takes it off screen and hands back the space it
 *   reserves, while its content keeps running and keeps its state; `true` puts it back.
 * @param onClose called once when the panel ends, as [LayerSurface]'s `onClose` describes.
 * @param content what is drawn on the panel, with the panel itself as `this`.
 */
@Composable
public fun <E : IError> Panel(
    monitor: Monitor? = null,
    edge: Edge,
    thickness: Dp,
    length: Dp = 0.dp,
    margins: Margins = Margins.None,
    namespace: String = "kortex",
    visible: Boolean = true,
    onClose: (Result<SurfaceEnd, SurfaceError<E>>) -> Unit = {},
    content: @Composable SurfaceScope<E>.() -> Unit,
) {
    LayerSurface(
        monitor = monitor,
        config = SurfaceConfig
            .panel(edge, thickness, length)
            .copy(namespace = namespace, margins = margins),
        visible = visible,
        onClose = onClose,
        content = content,
    )
}

/**
 * Placed as a [Panel] is, along one edge of its monitor, spanning that edge and reserving its own thickness there, and
 * able to take keyboard focus, as a text field in it needs.
 *
 * It is a [LayerSurface] with a dock's placement fixed; every other setting is this call's own.
 *
 * @param E the error your content can end it with through `close(error)`, or `Nothing` for none.
 * @param monitor the monitor to put the dock on, one [rememberMonitors] lists; null lets the compositor choose.
 * @param edge the edge the dock runs along. It is pinned to that edge and the two beside it.
 * @param thickness how far the dock reaches in from [edge], which is also the space it reserves there. It must round
 *   to at least one logical pixel, or the dock is not placed and `onClose` receives `Err(SurfaceError.Failed(...))`.
 * @param length how far the dock runs along [edge]; 0 spans the whole edge. It must not round below 0, or the dock is
 *   not placed and `onClose` receives `Err(SurfaceError.Failed(...))`.
 * @param margins insets from the edges the dock is pinned to; a margin on the edge opposite [edge] has no effect.
 * @param namespace what the compositor calls the dock, e.g. in `hyprctl layers`, exactly as written.
 * @param visible whether the dock is on screen. `false` takes it off screen and hands back the space it
 *   reserves, while its content keeps running and keeps its state; `true` puts it back.
 * @param onClose called once when the dock ends, as [LayerSurface]'s `onClose` describes.
 * @param content what is drawn on the dock, with the dock itself as `this`.
 */
@Composable
public fun <E : IError> Dock(
    monitor: Monitor? = null,
    edge: Edge,
    thickness: Dp,
    length: Dp = 0.dp,
    margins: Margins = Margins.None,
    namespace: String = "kortex",
    visible: Boolean = true,
    onClose: (Result<SurfaceEnd, SurfaceError<E>>) -> Unit = {},
    content: @Composable SurfaceScope<E>.() -> Unit,
) {
    LayerSurface(
        monitor = monitor,
        config = SurfaceConfig
            .dock(edge, thickness, length)
            .copy(namespace = namespace, margins = margins),
        visible = visible,
        onClose = onClose,
        content = content,
    )
}

/**
 * Fills its whole monitor on the lowest layer, beneath every window: a wallpaper, say. It reserves nothing, takes no
 * keyboard focus, and covers the space other surfaces reserve rather than moving out of their way.
 *
 * It is a [LayerSurface] with a background's placement fixed; every other setting is this call's own.
 *
 * @param E the error your content can end it with through `close(error)`, or `Nothing` for none.
 * @param monitor the monitor to fill, one [rememberMonitors] lists; null lets the compositor choose.
 * @param namespace what the compositor calls the background, e.g. in `hyprctl layers`, exactly as written.
 * @param visible whether the background is on screen. `false` takes it off screen, while its content keeps running
 *   and keeps its state; `true` puts it back.
 * @param onClose called once when the background ends, as [LayerSurface]'s `onClose` describes.
 * @param content what is drawn on the background, with the background itself as `this`.
 */
@Composable
public fun <E : IError> DesktopBackground(
    monitor: Monitor? = null,
    namespace: String = "kortex",
    visible: Boolean = true,
    onClose: (Result<SurfaceEnd, SurfaceError<E>>) -> Unit = {},
    content: @Composable SurfaceScope<E>.() -> Unit,
) {
    LayerSurface(
        monitor = monitor,
        config = SurfaceConfig.desktopBackground().copy(namespace = namespace),
        visible = visible,
        onClose = onClose,
        content = content,
    )
}

/**
 * Fills its whole monitor on the topmost layer, above every window, and takes the keyboard for as long as it is shown.
 * It reserves nothing, and covers the space other surfaces reserve rather than moving out of their way.
 *
 * It does not lock the session. kortex does not use `ext-session-lock-v1`, the protocol that locks one, so nothing
 * stops another surface from drawing over or beside it, or the compositor from switching away from it. Mistaking it
 * for a session lock is a security problem, not a layout one.
 *
 * It is a [LayerSurface] with a lock screen's placement fixed; every other setting is this call's own.
 *
 * @param E the error your content can end it with through `close(error)`, or `Nothing` for none.
 * @param monitor the monitor to cover, one [rememberMonitors] lists; null lets the compositor choose.
 * @param namespace what the compositor calls the lock screen, e.g. in `hyprctl layers`, exactly as written.
 * @param visible whether the lock screen is on screen. `false` takes it off screen, while its content keeps running
 *   and keeps its state; `true` puts it back.
 * @param onClose called once when the lock screen ends, as [LayerSurface]'s `onClose` describes.
 * @param content what is drawn on the lock screen, with the lock screen itself as `this`.
 */
@Composable
public fun <E : IError> LockScreen(
    monitor: Monitor? = null,
    namespace: String = "kortex",
    visible: Boolean = true,
    onClose: (Result<SurfaceEnd, SurfaceError<E>>) -> Unit = {},
    content: @Composable SurfaceScope<E>.() -> Unit,
) {
    LayerSurface(
        monitor = monitor,
        config = SurfaceConfig.lockScreen().copy(namespace = namespace),
        visible = visible,
        onClose = onClose,
        content = content,
    )
}

/**
 * A floating surface of exactly [width] by [height] on the topmost layer, above every window: a volume indicator, say.
 * It is centred in the space other surfaces leave free on its monitor, so a bar's reserved space shifts it off the
 * monitor's true centre. It takes no keyboard focus; an [AppMenu] is placed the same way and can take it.
 *
 * It is a [LayerSurface] with an osd's placement fixed; every other setting is this call's own.
 *
 * @param E the error your content can end it with through `close(error)`, or `Nothing` for none.
 * @param monitor the monitor to put it on, one [rememberMonitors] lists; null lets the compositor choose.
 * @param width its width, which must round to at least one logical pixel. One that rounds to 0 or below leaves it
 *   unplaced, and `onClose` receives `Err(SurfaceError.Failed(...))`.
 * @param height its height, which must round to at least one logical pixel, checked as [width] is.
 * @param namespace what the compositor calls it, e.g. in `hyprctl layers`, exactly as written.
 * @param visible whether it is on screen. `false` takes it off screen, while its content keeps running
 *   and keeps its state; `true` puts it back.
 * @param onClose called once when it ends, as [LayerSurface]'s `onClose` describes.
 * @param content what is drawn on it, with the surface itself as `this`.
 */
@Composable
public fun <E : IError> Osd(
    monitor: Monitor? = null,
    width: Dp,
    height: Dp,
    namespace: String = "kortex",
    visible: Boolean = true,
    onClose: (Result<SurfaceEnd, SurfaceError<E>>) -> Unit = {},
    content: @Composable SurfaceScope<E>.() -> Unit,
) {
    LayerSurface(
        monitor = monitor,
        config = SurfaceConfig.osd(width, height).copy(namespace = namespace),
        visible = visible,
        onClose = onClose,
        content = content,
    )
}

/**
 * Placed as an [Osd] is, a floating surface of exactly [width] by [height] centred in the space other surfaces leave
 * free on its monitor, and able to take keyboard focus: a launcher you type into, say, which its content dismisses
 * with `close()`.
 *
 * It is a [LayerSurface] with an app menu's placement fixed; every other setting is this call's own.
 *
 * @param E the error your content can end it with through `close(error)`, or `Nothing` for none.
 * @param monitor the monitor to put it on, one [rememberMonitors] lists; null lets the compositor choose.
 * @param width its width, which must round to at least one logical pixel. One that rounds to 0 or below leaves it
 *   unplaced, and `onClose` receives `Err(SurfaceError.Failed(...))`.
 * @param height its height, which must round to at least one logical pixel, checked as [width] is.
 * @param namespace what the compositor calls it, e.g. in `hyprctl layers`, exactly as written.
 * @param visible whether it is on screen. `false` takes it off screen, while its content keeps running
 *   and keeps its state; `true` puts it back.
 * @param onClose called once when it ends, as [LayerSurface]'s `onClose` describes.
 * @param content what is drawn on it, with the surface itself as `this`.
 */
@Composable
public fun <E : IError> AppMenu(
    monitor: Monitor? = null,
    width: Dp,
    height: Dp,
    namespace: String = "kortex",
    visible: Boolean = true,
    onClose: (Result<SurfaceEnd, SurfaceError<E>>) -> Unit = {},
    content: @Composable SurfaceScope<E>.() -> Unit,
) {
    LayerSurface(
        monitor = monitor,
        config = SurfaceConfig.appMenu(width, height).copy(namespace = namespace),
        visible = visible,
        onClose = onClose,
        content = content,
    )
}

/**
 * A menu of size [menuSize], opened at [at] on its monitor, on the topmost layer above every window. It opens
 * down and to the right of [at], unless it would then run past the monitor's right or bottom edge: it opens to the
 * left of [at] instead, or upwards from it, each direction decided on its own. It takes no keyboard focus.
 *
 * On a rotated monitor, the right and bottom edges are those of the monitor as it is turned.
 *
 * At a fractional scale, kortex measures the monitor short, since the compositor reports the scale as a whole number:
 * Hyprland reports 1.5 as 2, which makes the monitor a quarter smaller than it is. The menu then opens to the left or
 * upwards near an edge it would have cleared, and a menu that opens to the left or upwards sits away from [at], and
 * can run past the monitor's edge. One that opens down and to the right still opens at [at].
 *
 * ```kotlin
 * ContextMenu<Nothing>(
 *     monitor = monitor,
 *     at = at,
 *     menuSize = IntSize(160, 120),
 *     onClose = { menuAt = null },
 * ) {
 *     Text(
 *         text = "Close",
 *         modifier = Modifier.clickable { close() },
 *     )
 * }
 * ```
 *
 * It is a [LayerSurface] with a menu's placement fixed; every other setting is this call's own.
 *
 * @param E the error your content can end it with through `close(error)`, or `Nothing` for none.
 * @param monitor the monitor the menu opens on, one [rememberMonitors] lists.
 * @param at where the menu opens, in logical pixels from [monitor]'s top-left corner, however much of the monitor
 *   other surfaces reserve.
 * @param menuSize the menu's size in logical pixels, each at least 1. A width or height of 0 or below leaves the
 *   menu unplaced, and `onClose` receives `Err(SurfaceError.Failed(...))`.
 * @param namespace what the compositor calls the menu, e.g. in `hyprctl layers`, exactly as written.
 * @param visible whether the menu is on screen. `false` takes it off screen, while its content keeps running
 *   and keeps its state; `true` puts it back.
 * @param onClose called once when the menu ends, as [LayerSurface]'s `onClose` describes.
 * @param content what is drawn on the menu, with the menu itself as `this`.
 */
@Composable
public fun <E : IError> ContextMenu(
    monitor: Monitor,
    at: IntOffset,
    menuSize: IntSize,
    namespace: String = "kortex",
    visible: Boolean = true,
    onClose: (Result<SurfaceEnd, SurfaceError<E>>) -> Unit = {},
    content: @Composable SurfaceScope<E>.() -> Unit,
) {
    LayerSurface(
        monitor = monitor,
        config = SurfaceConfig
            .contextMenu(at = at, menuSize = menuSize, outputSize = monitor.geometry.logicalSize)
            .copy(namespace = namespace),
        visible = visible,
        onClose = onClose,
        content = content,
    )
}

// A mode is the output's unturned size, so a quarter turn swaps its width and height on screen.
private val OutputGeometry.logicalSize: IntSize
    get() = when {
        transform.isQuarterTurn -> IntSize(height / scale, width / scale)
        else -> IntSize(width / scale, height / scale)
    }
