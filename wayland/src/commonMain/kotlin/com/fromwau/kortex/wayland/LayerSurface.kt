package com.fromwau.kortex.wayland

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.IError
import com.fromwau.kern.result.Result
import com.fromwau.kortex.compose.KortexSurfaceHandle

/**
 * Keeps a layer-shell surface of your own on screen while this call is in composition, drawing [content] on it. Call
 * it in [kortexApplication]'s content, or in another surface's content to put a second surface on screen for as long
 * as the first one runs.
 *
 * ```kotlin
 * sealed interface OsdError : IError { data object Expired : OsdError }
 *
 * kortexApplication {
 *     var level by remember { mutableStateOf<Float?>(0.4f) }
 *     level?.let { volume ->
 *         LayerSurface<OsdError>(
 *             namespace = "volume",
 *             layer = Layer.Overlay,
 *             width = 240.dp,
 *             height = 48.dp,
 *             onClose = { level = null },
 *         ) {
 *             LaunchedEffect(Unit) {
 *                 delay(2_000)
 *                 close(OsdError.Expired)
 *             }
 *             LinearProgressIndicator(progress = { volume })
 *         }
 *     }
 * }
 * ```
 *
 * The surface appears shortly after the call enters composition, and goes when the call leaves it. It keeps running
 * and draws the newest [content] throughout, and a changed setting changes the surface on screen shortly after,
 * reporting nothing: [layer], [anchor], [width], [height], [margins], [exclusiveZone], [exclusiveEdge] and [keyboard]
 * reach it in one step, [visible] takes it off screen and back, and a changed [monitor] or [namespace] moves the
 * content to a layer surface the compositor sees as a new one, on whichever monitor and under whichever name the
 * call asks for by then. State [content] keeps behind `remember` stands through every one of those changes, and
 * lasts until the surface ends.
 *
 * Once the surface has ended by itself, in any of the ways [onClose] lists, the call shows nothing until you take it
 * out of composition and put it back. Taking it out reports nothing more.
 *
 * @param E the error your content can end the surface with through `close(error)`, or `Nothing` for none. Spell it at
 *   the call site, as `LayerSurface<Nothing>(...)`.
 * @param monitor the monitor to put the surface on, one [rememberMonitors] lists; null lets the compositor choose.
 *   When that monitor is unplugged, the surface ends, and [onClose] receives `Ok(SurfaceEnd.MonitorUnplugged)`.
 * @param namespace what the compositor calls the surface, e.g. in `hyprctl layers`, exactly as written, whichever
 *   monitor it is on: name a surface you show on every monitor `"bar-${monitor.name}"`, say, to tell them apart.
 * @param layer which layer the surface sits in.
 * @param anchor the edges the surface is pinned to. Pinning both edges of an [Axis] spans that axis, and pinning
 *   none centres the surface.
 * @param width 0 asks the compositor to choose, which needs [anchor] to pin both [Edge.Left] and [Edge.Right];
 *   without them the surface is not placed, and [onClose] receives
 *   `Err(SurfaceError.Failed(KortexError.UnspannableAxis(...)))`. One that rounds below 0 leaves the surface unplaced
 *   too, and [onClose] receives `Err(SurfaceError.Failed(KortexError.NegativeSize(...)))`.
 * @param height 0 asks the compositor to choose, like [width], and needs both [Edge.Top] and [Edge.Bottom]. One that
 *   rounds below 0 leaves the surface unplaced, as for [width].
 * @param margins insets from the anchor point; an edge [anchor] does not pin ignores its margin.
 * @param exclusiveZone what the surface reserves of the space the compositor tiles other windows into.
 * @param exclusiveEdge which anchored edge [exclusiveZone] is measured from, needed only when [anchor] pins a
 *   corner.
 * @param keyboard whether the surface can take keyboard focus.
 * @param visible whether the surface is on screen. `false` takes it off screen and hands back the space it reserved,
 *   while [content] keeps running and keeps its state, and `size` keeps the value it last had; `true` puts it back on
 *   screen, at whatever the settings ask for by then. Nothing is reported either way, and nothing is released until
 *   the call leaves composition.
 * @param onClose called once when the surface ends, after it has gone, on the thread that runs [kortexApplication].
 *   It receives `Ok` with how the surface ended: [SurfaceEnd.Closed] when `close()` is called,
 *   [SurfaceEnd.ClosedByCompositor] when the compositor closes it, [SurfaceEnd.MonitorUnplugged] when its [monitor]
 *   is unplugged, and [SurfaceEnd.LeftComposition] when the call leaves composition: taken out, gone with the
 *   surface whose content showed it, or ended by `exitApplication()`. It receives [SurfaceError.Closed] when
 *   `close(error)` is called, and [SurfaceError.Failed] when the surface could not be placed or changed to the
 *   settings asked for, failed to follow a new size or scale from the compositor, its content threw, or the
 *   connection to the compositor failed. For a failed connection, [SurfaceError.Failed] carries the same error
 *   [kortexApplication] returns. Content that throws before the surface has gone, its cleanup as it goes included,
 *   makes it [SurfaceError.Failed] whatever else ended it. If `onClose` itself throws, the application ends with
 *   [KortexError.ApplicationCrashed], and no other `onClose` is called.
 * @param content what is drawn on the surface. It reaches the surface itself as `this`, the clipboard as
 *   [LocalKortexClipboard], and the same surface through `LocalKortexSurface.current` in a composable further down.
 * @throws IllegalStateException when called outside [kortexApplication].
 */
@Composable
public fun <E : IError> LayerSurface(
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
    visible: Boolean = true,
    onClose: (Result<SurfaceEnd, SurfaceError<E>>) -> Unit = {},
    content: @Composable SurfaceScope<E>.() -> Unit,
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
        visible = visible,
        onClose = onClose,
        content = content,
    )
}

/**
 * The surface a piece of content is drawn on, as its own `this`: its [size], and the two ways to end it. `close()`
 * ends it with `Ok(SurfaceEnd.Closed)`, and [size] is the logical size the compositor last gave the surface, which
 * it keeps while the surface is off screen, and `IntSize.Zero` once the surface has ended.
 *
 * @param E the error the content can end the surface with, or `Nothing` for none.
 */
public interface SurfaceScope<in E : IError> : KortexSurfaceHandle {
    /**
     * Ends the surface, whose `onClose` receives `Err(SurfaceError.Closed(error))`. Safe from any thread, more than
     * once, and after the surface has gone.
     *
     * The first `close()` or `close(error)` decides what `onClose` receives, and later ones do nothing, unless the
     * surface fails: content that throws before the surface has gone, its cleanup as the surface goes included, makes
     * it `Err(SurfaceError.Failed(...))` instead.
     */
    public fun close(error: E)
}

/** Every setting but [monitor] and [visible] from [config]: the route every surface call places through. */
@Composable
internal fun <E : IError> LayerSurface(
    monitor: Monitor?,
    config: SurfaceConfig,
    visible: Boolean,
    onClose: (Result<SurfaceEnd, SurfaceError<E>>) -> Unit,
    content: @Composable SurfaceScope<E>.() -> Unit,
) {
    val shell = LocalKortexShell.current
    val newestContent = rememberUpdatedState(content)
    // Safe: only this call's own scope produces an E, and only this call's onClose receives it.
    @Suppress("UNCHECKED_CAST")
    val newestOnClose = rememberUpdatedState(onClose) as State<(Result<SurfaceEnd, SurfaceError<IError>>) -> Unit>
    val parent = LocalSurfaceSlot.current
    val slot = remember {
        SurfaceSlot(
            content = newestContent,
            onClose = newestOnClose,
            wake = shell::wake,
            parent = parent,
        )
    }
    val settings = SurfaceSettings(monitor = monitor, config = config, visible = visible)
    SideEffect { shell.queueUpdate(slot, settings) }
    DisposableEffect(Unit) { onDispose { shell.queueRemove(slot) } }
}

/** What a surface call asks for: a surface placed with other settings is changed to these. */
internal data class SurfaceSettings(
    val monitor: Monitor?,
    val config: SurfaceConfig,
    val visible: Boolean,
) {
    /**
     * Whether a surface placed with [placed] has to be made again to reach these settings, rather than changed:
     * `get_layer_surface` fixes the monitor and the namespace for the life of a layer surface.
     */
    fun rebuildsOver(placed: SurfaceSettings): Boolean =
        monitor != placed.monitor || config.namespace != placed.config.namespace
}

/** How a surface ended cleanly: what its `onClose` receives inside `Ok`. */
public sealed interface SurfaceEnd {
    /** `close()` was called on it. */
    public data object Closed : SurfaceEnd

    /** The compositor closed it. */
    public data object ClosedByCompositor : SurfaceEnd

    /** Its monitor was unplugged. */
    public data object MonitorUnplugged : SurfaceEnd

    /**
     * Its call left composition: your content took it out, the surface whose content showed it ended, or
     * `exitApplication()` ended the application.
     */
    public data object LeftComposition : SurfaceEnd
}

/** Why a surface ended, when it did not end cleanly: what its `onClose` receives inside `Err`. */
public sealed interface SurfaceError<out E : IError> : IError {
    /** Its surface was closed with your own [error], through `close(error)`. */
    public data class Closed<out E : IError>(public val error: E) : SurfaceError<E>

    /**
     * kortex ended the surface: its content threw, as [KortexError.SurfaceCrashed]; it could not be placed, as
     * [KortexError.UnspannableAxis], say; it failed to follow a new size or scale from the compositor, as
     * [KortexError.ShmAllocationFailed]; or the connection to the compositor failed, as the same error
     * [kortexApplication] returns.
     */
    public data class Failed(public val error: KortexError) : SurfaceError<Nothing>
}
