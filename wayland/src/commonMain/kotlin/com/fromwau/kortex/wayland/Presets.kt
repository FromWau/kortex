package com.fromwau.kortex.wayland

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/**
 * A bar along one edge of its monitor, spanning that edge and reserving its own thickness there, so windows tile clear
 * of it. Left at its defaults, it runs along the top edge, 32 dp thick, and takes no keyboard focus.
 *
 * ```kotlin
 * kortexApplication {
 *     val monitors by rememberMonitors()
 *     for (monitor in monitors) key(monitor) {
 *         Bar(monitor = monitor, namespace = "clock-${monitor.name}") {
 *             Text("12:00")
 *         }
 *     }
 * }
 * ```
 *
 * It is a [LayerSurface] with a bar's placement fixed; every other setting is this call's own.
 *
 * @param monitor the monitor to put the bar on, one [rememberMonitors] lists; null lets the compositor choose.
 * @param edge the edge the bar runs along. It is pinned to that edge and the two beside it.
 * @param thickness how far the bar reaches in from [edge], which is also the space it reserves there. It must round to
 *   at least one logical pixel, or the bar is not placed and its status ends with `Err`.
 * @param length how far the bar runs along [edge]; 0 spans the whole edge. It must not round below 0, or the bar is
 *   not placed and its status ends with `Err`.
 * @param margins insets from the edges the bar is pinned to; a margin on the edge opposite [edge] has no effect.
 * @param keyboard whether the bar can take keyboard focus, as a text field in it needs.
 * @param namespace what the compositor calls the bar, e.g. in `hyprctl layers`, exactly as written.
 * @param state where to read what the bar is doing, as [LayerSurface]'s `state` describes.
 * @param content what is drawn on the bar, with the bar itself as `this`.
 */
@Composable
public fun Bar(
    monitor: Monitor? = null,
    edge: Edge = Edge.Top,
    thickness: Dp = 32.dp,
    length: Dp = 0.dp,
    margins: Margins = Margins.None,
    keyboard: KeyboardInteractivity = KeyboardInteractivity.None,
    namespace: String = "kortex",
    state: SurfaceState = rememberSurfaceState(),
    content: @Composable SurfaceScope.() -> Unit,
) {
    LayerSurface(
        monitor = monitor,
        config = SurfaceConfig
            .panel(edge, thickness, length)
            .copy(namespace = namespace, margins = margins, keyboard = keyboard),
        state = state,
        content = content,
    )
}

/**
 * A surface along one edge of its monitor, spanning that edge and reserving its own thickness there, so windows tile
 * clear of it. It takes no keyboard focus; a [Dock] is placed the same way and can take it.
 *
 * It is a [LayerSurface] with a panel's placement fixed; every other setting is this call's own.
 *
 * @param monitor the monitor to put the panel on, one [rememberMonitors] lists; null lets the compositor choose.
 * @param edge the edge the panel runs along. It is pinned to that edge and the two beside it.
 * @param thickness how far the panel reaches in from [edge], which is also the space it reserves there. It must round
 *   to at least one logical pixel, or the panel is not placed and its status ends with `Err`.
 * @param length how far the panel runs along [edge]; 0 spans the whole edge. It must not round below 0, or the panel
 *   is not placed and its status ends with `Err`.
 * @param margins insets from the edges the panel is pinned to; a margin on the edge opposite [edge] has no effect.
 * @param namespace what the compositor calls the panel, e.g. in `hyprctl layers`, exactly as written.
 * @param state where to read what the panel is doing, as [LayerSurface]'s `state` describes.
 * @param content what is drawn on the panel, with the panel itself as `this`.
 */
@Composable
public fun Panel(
    monitor: Monitor? = null,
    edge: Edge,
    thickness: Dp,
    length: Dp = 0.dp,
    margins: Margins = Margins.None,
    namespace: String = "kortex",
    state: SurfaceState = rememberSurfaceState(),
    content: @Composable SurfaceScope.() -> Unit,
) {
    LayerSurface(
        monitor = monitor,
        config = SurfaceConfig
            .panel(edge, thickness, length)
            .copy(namespace = namespace, margins = margins),
        state = state,
        content = content,
    )
}

/**
 * Placed as a [Panel] is, along one edge of its monitor, spanning that edge and reserving its own thickness there, and
 * able to take keyboard focus, as a text field in it needs.
 *
 * It is a [LayerSurface] with a dock's placement fixed; every other setting is this call's own.
 *
 * @param monitor the monitor to put the dock on, one [rememberMonitors] lists; null lets the compositor choose.
 * @param edge the edge the dock runs along. It is pinned to that edge and the two beside it.
 * @param thickness how far the dock reaches in from [edge], which is also the space it reserves there. It must round
 *   to at least one logical pixel, or the dock is not placed and its status ends with `Err`.
 * @param length how far the dock runs along [edge]; 0 spans the whole edge. It must not round below 0, or the dock is
 *   not placed and its status ends with `Err`.
 * @param margins insets from the edges the dock is pinned to; a margin on the edge opposite [edge] has no effect.
 * @param namespace what the compositor calls the dock, e.g. in `hyprctl layers`, exactly as written.
 * @param state where to read what the dock is doing, as [LayerSurface]'s `state` describes.
 * @param content what is drawn on the dock, with the dock itself as `this`.
 */
@Composable
public fun Dock(
    monitor: Monitor? = null,
    edge: Edge,
    thickness: Dp,
    length: Dp = 0.dp,
    margins: Margins = Margins.None,
    namespace: String = "kortex",
    state: SurfaceState = rememberSurfaceState(),
    content: @Composable SurfaceScope.() -> Unit,
) {
    LayerSurface(
        monitor = monitor,
        config = SurfaceConfig
            .dock(edge, thickness, length)
            .copy(namespace = namespace, margins = margins),
        state = state,
        content = content,
    )
}

/**
 * Fills its whole monitor on the lowest layer, beneath every window: a wallpaper, say. It reserves nothing, takes no
 * keyboard focus, and covers the space other surfaces reserve rather than moving out of their way.
 *
 * It is a [LayerSurface] with a background's placement fixed; every other setting is this call's own.
 *
 * @param monitor the monitor to fill, one [rememberMonitors] lists; null lets the compositor choose.
 * @param namespace what the compositor calls the background, e.g. in `hyprctl layers`, exactly as written.
 * @param state where to read what the background is doing, as [LayerSurface]'s `state` describes.
 * @param content what is drawn on the background, with the background itself as `this`.
 */
@Composable
public fun DesktopBackground(
    monitor: Monitor? = null,
    namespace: String = "kortex",
    state: SurfaceState = rememberSurfaceState(),
    content: @Composable SurfaceScope.() -> Unit,
) {
    LayerSurface(
        monitor = monitor,
        config = SurfaceConfig.desktopBackground().copy(namespace = namespace),
        state = state,
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
 * @param monitor the monitor to cover, one [rememberMonitors] lists; null lets the compositor choose.
 * @param namespace what the compositor calls the lock screen, e.g. in `hyprctl layers`, exactly as written.
 * @param state where to read what the lock screen is doing, as [LayerSurface]'s `state` describes.
 * @param content what is drawn on the lock screen, with the lock screen itself as `this`.
 */
@Composable
public fun LockScreen(
    monitor: Monitor? = null,
    namespace: String = "kortex",
    state: SurfaceState = rememberSurfaceState(),
    content: @Composable SurfaceScope.() -> Unit,
) {
    LayerSurface(
        monitor = monitor,
        config = SurfaceConfig.lockScreen().copy(namespace = namespace),
        state = state,
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
 * @param monitor the monitor to put it on, one [rememberMonitors] lists; null lets the compositor choose.
 * @param width its width, which must round to at least one logical pixel. One that rounds to 0 or below leaves it
 *   unplaced, and its status ends with `Err`.
 * @param height its height, which must round to at least one logical pixel, checked as [width] is.
 * @param namespace what the compositor calls it, e.g. in `hyprctl layers`, exactly as written.
 * @param state where to read what it is doing, as [LayerSurface]'s `state` describes.
 * @param content what is drawn on it, with the surface itself as `this`.
 */
@Composable
public fun Osd(
    monitor: Monitor? = null,
    width: Dp,
    height: Dp,
    namespace: String = "kortex",
    state: SurfaceState = rememberSurfaceState(),
    content: @Composable SurfaceScope.() -> Unit,
) {
    LayerSurface(
        monitor = monitor,
        config = SurfaceConfig.osd(width, height).copy(namespace = namespace),
        state = state,
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
 * @param monitor the monitor to put it on, one [rememberMonitors] lists; null lets the compositor choose.
 * @param width its width, which must round to at least one logical pixel. One that rounds to 0 or below leaves it
 *   unplaced, and its status ends with `Err`.
 * @param height its height, which must round to at least one logical pixel, checked as [width] is.
 * @param namespace what the compositor calls it, e.g. in `hyprctl layers`, exactly as written.
 * @param state where to read what it is doing, as [LayerSurface]'s `state` describes.
 * @param content what is drawn on it, with the surface itself as `this`.
 */
@Composable
public fun AppMenu(
    monitor: Monitor? = null,
    width: Dp,
    height: Dp,
    namespace: String = "kortex",
    state: SurfaceState = rememberSurfaceState(),
    content: @Composable SurfaceScope.() -> Unit,
) {
    LayerSurface(
        monitor = monitor,
        config = SurfaceConfig.appMenu(width, height).copy(namespace = namespace),
        state = state,
        content = content,
    )
}

/**
 * A menu of size [menuSize], opened at [at] over the surface this call's content is on, and stacked above it. It
 * opens down and to the right of [at], unless it would then run past the screen's right or bottom edge: it opens
 * to the left of [at] instead, or upwards from it, each direction decided on its own.
 *
 * A menu takes the desktop's grab while it is up, as a menu does everywhere: it holds the keyboard, so typing
 * reaches its content rather than whatever had focus, and the desktop dismisses it when the user clicks away
 * from it, presses a key that leaves it, or the screen locks. A dismissal reaches you as
 * `Ok(SurfaceEnd.ClosedByCompositor)` in [state], and the menu is gone by then, so take the call out of
 * composition on it. That is the whole difference from [Popup], which holds no grab and stays up until its call
 * leaves composition.
 *
 * Open it from a click. The grab names the pointer press that opened the menu, so a menu opened with no press
 * behind it, from an effect as the surface appears say, is refused the grab and dismissed as it arrives.
 *
 * Call it in a surface's content, never in [kortexApplication]'s own: the menu belongs to the surface it opens
 * over, and both a bar and a window can hold one.
 *
 * ```kotlin
 * ContextMenu(
 *     at = at,
 *     menuSize = IntSize(160, 120),
 * ) {
 *     Text(
 *         text = "Close",
 *         modifier = Modifier.clickable { close() },
 *     )
 * }
 * ```
 *
 * It is a [Popup] that takes a grab, with a menu's size given as one value; every other setting is this call's own.
 *
 * @param at where the menu opens, in logical pixels from the top-left corner of the surface this call's content
 *   is on.
 * @param menuSize the menu's size in logical pixels, each at least 1.
 * @param state where to read what the menu is doing, as [Popup]'s `state` describes.
 * @param content what is drawn on the menu, with the menu itself as `this`.
 * @throws IllegalStateException when called outside a surface's content. Uncatchable here: it ends the
 *   application as [KortexError.ApplicationCrashed], the same as any other throw from your own content.
 * @throws IllegalArgumentException when either side of [menuSize] is less than one pixel, which ends the surface
 *   this call's content is on, not just this menu, the same as any other throw from your content there.
 */
@Composable
public fun ContextMenu(
    at: IntOffset,
    menuSize: IntSize,
    state: SurfaceState = rememberSurfaceState(),
    content: @Composable SurfaceScope.() -> Unit,
) {
    PopupCall(
        at = at,
        width = menuSize.width.dp,
        height = menuSize.height.dp,
        grab = true,
        state = state,
        content = content,
    )
}
