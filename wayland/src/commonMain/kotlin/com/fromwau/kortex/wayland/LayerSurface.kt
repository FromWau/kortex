package com.fromwau.kortex.wayland

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Keeps a layer-shell surface of your own on screen while this call is in composition, drawing [content] on it. Call
 * it in [kortexApplication]'s content, or in another surface's content to put a second surface on screen for as long
 * as the first one runs.
 *
 * ```kotlin
 * kortexApplication {
 *     val volume = rememberSurfaceState()
 *     val status = volume.status
 *
 *     LayerSurface(
 *         namespace = "volume",
 *         layer = Layer.Overlay,
 *         width = 240.dp,
 *         height = 48.dp,
 *         state = volume,
 *     ) {
 *         LaunchedEffect(Unit) {
 *             delay(2_000)
 *             close()
 *         }
 *         LinearProgressIndicator(progress = { 0.4f })
 *     }
 *     LaunchedEffect(status) { if (status is SurfaceStatus.Ended) exitApplication() }
 * }
 * ```
 *
 * The surface appears shortly after the call enters composition, and goes when the call leaves it. It keeps running
 * and draws the newest [content] throughout, and a changed setting changes the surface on screen shortly after,
 * ending nothing: [layer], [anchor], [width], [height], [margins], [exclusiveZone], [exclusiveEdge] and [keyboard]
 * reach it in one step, and a changed [monitor] or [namespace] moves the content to a layer surface the compositor
 * sees as a new one, on whichever monitor and under whichever name the call asks for by then. State [content] keeps
 * behind `remember` stands through every one of those changes, and lasts until the surface ends.
 *
 * A [Popup] open in that content does not come along: a popup belongs to the surface it opened over for as long as
 * it lives, so one open across a changed [monitor] or [namespace] ends with `Ok(SurfaceEnd.LeftComposition)`. Show
 * it again by taking its call out of composition and putting it back.
 *
 * Once the surface has ended, in any of the ways [SurfaceStatus.Ended] lists, the call shows nothing until you take
 * it out of composition and put it back, which places a surface again and takes [state] with it.
 *
 * @param monitor the monitor to put the surface on, one [rememberMonitors] lists; null lets the compositor choose.
 *   When that monitor is unplugged, the surface ends with `Ok(SurfaceEnd.MonitorUnplugged)`.
 * @param namespace what the compositor calls the surface, e.g. in `hyprctl layers`, exactly as written, whichever
 *   monitor it is on: name a surface you show on every monitor `"bar-${monitor.name}"`, say, to tell them apart.
 * @param layer which layer the surface sits in.
 * @param anchor the edges the surface is pinned to. Pinning both edges of an [Axis] spans that axis, and pinning
 *   none centres the surface.
 * @param width 0 asks the compositor to choose, which needs [anchor] to pin both [Edge.Left] and [Edge.Right];
 *   without them the surface is not placed, and ends with `Err(KortexError.UnspannableAxis(...))`. One that rounds
 *   below 0 leaves the surface unplaced too, ending it with `Err(KortexError.NegativeSize(...))`.
 * @param height 0 asks the compositor to choose, like [width], and needs both [Edge.Top] and [Edge.Bottom]. One that
 *   rounds below 0 leaves the surface unplaced, as for [width].
 * @param margins insets from the anchor point; an edge [anchor] does not pin ignores its margin.
 * @param exclusiveZone what the surface reserves of the space the compositor tiles other windows into.
 * @param exclusiveEdge which anchored edge [exclusiveZone] is measured from, needed only when [anchor] pins a
 *   corner. It needs `zwlr_layer_shell_v1` 5; against an older compositor the surface is not placed and ends
 *   with `Err(KortexError.ExclusiveEdgeUnsupported(...))` rather than reserving against an edge nobody chose.
 * @param keyboard whether the surface can take keyboard focus.
 * @param state where to read what the surface is doing: [SurfaceStatus.Placing] until it reaches the screen,
 *   [SurfaceStatus.OnScreen] with the size it is drawn at, and [SurfaceStatus.Ended] with how it ended, once and for
 *   good. Pass a state of your own to keep reading it after the call has left composition; one call at a time
 *   publishes to a state.
 * @param content what is drawn on the surface. It reaches the surface itself as `this`, the clipboard as
 *   [LocalKortexClipboard], and the same surface through `LocalKortexSurface.current` in a composable further down.
 * @throws IllegalStateException when called outside [kortexApplication].
 */
@Composable
public fun LayerSurface(
    monitor: Monitor? = null,
    namespace: String = "kortex",
    layer: Layer = Layer.Top,
    anchor: Set<Edge> = emptySet(),
    width: Dp = 0.dp,
    height: Dp = 0.dp,
    margins: Margins = Margins.None,
    exclusiveZone: ExclusiveZone = ExclusiveZone.Yield,
    exclusiveEdge: Edge? = null,
    keyboard: KeyboardInteractivity = KeyboardInteractivity.None,
    state: SurfaceState = rememberSurfaceState(),
    content: @Composable SurfaceScope.() -> Unit,
) {
    LayerSurface(
        monitor = monitor,
        config = SurfaceConfig(
            namespace = namespace,
            layer = layer,
            anchor = anchor,
            width = width,
            height = height,
            margins = margins,
            exclusiveZone = exclusiveZone,
            keyboard = keyboard,
            exclusiveEdge = exclusiveEdge,
        ),
        state = state,
        content = content,
    )
}

/** Every setting but [monitor] from [config]: what a layer-shell surface call asks for. */
@Composable
internal fun LayerSurface(
    monitor: Monitor?,
    config: SurfaceConfig,
    state: SurfaceState,
    content: @Composable SurfaceScope.() -> Unit,
) {
    SurfaceCall(
        settings = LayerSettings(monitor = monitor, config = config),
        published = state.published,
        content = content,
    )
}

/** What a [LayerSurface] call asks for. */
internal data class LayerSettings(
    val monitor: Monitor?,
    val config: SurfaceConfig,
) : SurfaceSettings() {
    /** `get_layer_surface` fixes the monitor and the namespace for the life of a layer surface. */
    override fun rebuildsOverSameKind(placed: SurfaceSettings): Boolean {
        placed as LayerSettings
        return monitor != placed.monitor || config.namespace != placed.config.namespace
    }
}

/** How a surface ended cleanly: what [SurfaceStatus.Ended] carries inside `Ok`. */
public sealed interface SurfaceEnd {
    /** `close()` was called on it. */
    public data object Closed : SurfaceEnd

    /** The compositor closed it. */
    public data object ClosedByCompositor : SurfaceEnd

    /** Its monitor was unplugged. */
    public data object MonitorUnplugged : SurfaceEnd

    /**
     * Its call left composition: your content took it out, the surface whose content showed it ended or, for a
     * popup, was moved to another monitor or namespace, or `exitApplication()` ended the application.
     */
    public data object LeftComposition : SurfaceEnd
}
