package com.fromwau.kortex.wayland

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.IError
import com.fromwau.kern.result.Ok
import com.fromwau.kortex.compose.KortexSurfaceHandle
import kotlin.reflect.KClass

/**
 * A layer-shell surface of your own: its settings are your class and the values you pass to `LayerSurface`'s
 * constructor, and its content is [invoke].
 *
 * Subclass it, pass the settings that suit its kind, and draw in [invoke]. [Show] puts it on screen inside
 * [kortexApplication]:
 *
 * ```kotlin
 * sealed interface OsdError : IError { data object Expired : OsdError }
 *
 * class VolumeOsd(
 *     private val level: Float,
 *     onClose: (EmptyResult<SurfaceError<OsdError>>) -> Unit = {},
 * ) : LayerSurface<OsdError>(
 *     namespace = "volume",
 *     layer = Layer.Overlay,
 *     width = 240.dp,
 *     height = 48.dp,
 *     onClose = onClose,
 * ) {
 *     @Composable
 *     override fun invoke() {
 *         LaunchedEffect(Unit) {
 *             delay(2_000)
 *             close(OsdError.Expired)
 *         }
 *         LinearProgressIndicator(progress = { level })
 *     }
 * }
 * ```
 *
 * The application builds a new instance each time it recomposes, so keep state inside [invoke] behind `remember`,
 * never in the class's fields. [Show] treats two instances as the same surface when they are of your same class and
 * pass `LayerSurface`'s constructor equal values, [onClose] aside: it keeps running, with the newest instance's
 * content and `onClose`. A value only your own constructor takes, such as `level` above, is not a setting: a new one
 * reaches the running surface's content.
 *
 * A surface with no error of its own extends `LayerSurface<Nothing>`, so `close(error)` cannot be called on it. A
 * class generic in `E` must reach one [Show] with a single type argument: instances with two share a surface, so a
 * `close(error)` on one reaches the other's `onClose` with an error of the wrong type, and handling it can end the
 * application.
 *
 * @property monitor the monitor to put the surface on, one [rememberMonitors] lists; null lets the compositor choose.
 *   When that monitor is unplugged, the surface ends, and [onClose] receives `Ok(Unit)`.
 * @property namespace what the compositor calls the surface, e.g. in `hyprctl layers`, exactly as written, whichever
 *   monitor it is on: name a surface you show on every monitor `"bar-${monitor.name}"`, say, to tell them apart.
 * @property layer which layer the surface sits in.
 * @property anchor the edges the surface is pinned to. Pinning both edges of an [Axis] spans that axis, and pinning
 *   none centres the surface.
 * @property width 0 asks the compositor to choose, which needs [anchor] to pin both [Edge.Left] and [Edge.Right];
 *   without them the surface is not placed, and [onClose] receives
 *   `Err(SurfaceError.Failed(KortexError.UnspannableAxis(...)))`.
 * @property height 0 asks the compositor to choose, like [width], and needs both [Edge.Top] and [Edge.Bottom].
 * @property margins insets from the anchor point; an edge [anchor] does not pin ignores its margin.
 * @property exclusiveZone what the surface reserves of the space the compositor tiles other windows into.
 * @property exclusiveEdge which anchored edge [exclusiveZone] is measured from, needed only when [anchor] pins a
 *   corner.
 * @property keyboard whether the surface can take keyboard focus.
 * @property onClose called once when the surface ends, after it has gone, on the thread that runs
 *   [kortexApplication]. It receives `Ok(Unit)` when `close()` is called, the compositor closes the surface, its
 *   [monitor] is unplugged, or its [Show] leaves composition: taken out, gone with the surface whose content showed
 *   it, or ended by `exitApplication()`. It receives [SurfaceError.Closed] when `close(error)` is called, and
 *   [SurfaceError.Failed] when the surface could not be placed, failed to follow a new size or scale from the
 *   compositor, or its content threw. Content that throws before the surface has gone, its cleanup as it goes
 *   included, makes it [SurfaceError.Failed] whatever else ended it. If `onClose` itself throws, the application
 *   ends with [KortexError.ApplicationCrashed], and no other `onClose` is called.
 */
public abstract class LayerSurface<E : IError>(
    public val monitor: Monitor? = null,
    public val namespace: String = "kortex",
    public val layer: Layer = Layer.Top,
    public val anchor: Set<Edge> = emptySet(),
    public val width: Dp = 0.dp,
    public val height: Dp = 0.dp,
    public val margins: Margins = Margins.None,
    public val exclusiveZone: ExclusiveZone = ExclusiveZone.Yield,
    public val exclusiveEdge: Edge? = null,
    public val keyboard: KeyboardInteractivity = KeyboardInteractivity.None,
    public val onClose: (EmptyResult<SurfaceError<E>>) -> Unit = {},
) : KortexSurfaceHandle {
    // The Show this instance was last handed to; null for an instance never shown.
    @Volatile
    internal var heldBy: ShownSurface? = null

    /** Every setting but [monitor] from [config]: the presets' route to their kind's [SurfaceConfig] preset. */
    internal constructor(
        monitor: Monitor?,
        config: SurfaceConfig,
        onClose: (EmptyResult<SurfaceError<E>>) -> Unit,
    ) : this(
        monitor = monitor,
        namespace = config.namespace,
        layer = config.layer,
        anchor = config.anchor,
        width = config.width,
        height = config.height,
        margins = config.margins,
        exclusiveZone = config.exclusiveZone,
        exclusiveEdge = config.exclusiveEdge,
        keyboard = config.keyboard,
        onClose = onClose,
    )

    /**
     * The content drawn on the surface, with this instance as `this`. Composables further down reach this instance as
     * `LocalKortexSurface.current` and the clipboard as [LocalKortexClipboard]; a [Show] in here puts a surface of its
     * own on screen for as long as this one runs.
     */
    @Composable
    public abstract fun invoke()

    /**
     * The logical size of the surface this instance's [Show] holds; [IntSize.Zero] while it holds none, before
     * the surface is placed and after it has ended.
     */
    final override val size: IntSize get() = heldBy?.surface?.logicalSize ?: IntSize.Zero

    /**
     * Ends the surface this instance's [Show] holds, whichever of that `Show`'s instances of this class you call it
     * on: the `onClose` of the newest instance handed to that `Show` receives `Ok(Unit)`. Safe from any thread, more
     * than once, and after the surface has gone. It does nothing on an instance never handed to a [Show], or once that
     * `Show` has been handed an instance of another class.
     *
     * The first `close()` or `close(error)` decides what `onClose` receives, and later ones do nothing, unless the
     * surface fails. If its content throws before the surface has gone, its cleanup as the surface goes included,
     * `onClose` receives `Err(SurfaceError.Failed(...))` instead. It receives that too when the surface failed to
     * follow a new size or scale from the compositor before your first call.
     */
    final override fun close() {
        heldBy?.requestEnd(this, Ok(Unit))
    }

    /**
     * Ends the surface as `close()` does, except that the newest instance's `onClose` receives
     * `Err(SurfaceError.Closed(error))`.
     */
    public fun close(error: E) {
        heldBy?.requestEnd(this, Err(SurfaceError.Closed(error)))
    }

    internal fun report(ending: EmptyResult<SurfaceError<IError>>) {
        // Safe for a class that fixes E: a Closed ending is honoured only when asked for by an instance of this class.
        @Suppress("UNCHECKED_CAST")
        (onClose as (EmptyResult<SurfaceError<IError>>) -> Unit)(ending)
    }

    /** Its class and every constructor value but [onClose]: what makes two instances the same surface to [Show]. */
    internal val settings: SurfaceSettings
        get() = SurfaceSettings(
            kind = this::class,
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
        )
}

/** What [Show] compares its instances by: two with equal settings are the same surface. */
internal data class SurfaceSettings(
    val kind: KClass<*>,
    val monitor: Monitor?,
    val config: SurfaceConfig,
)

/** Why a surface ended, when it did not end cleanly: what its `onClose` receives inside `Err`. */
public sealed interface SurfaceError<out E : IError> : IError {
    /** Its surface was closed with your own [error], through `close(error)`. */
    public data class Closed<out E : IError>(public val error: E) : SurfaceError<E>

    /**
     * kortex ended the surface: its content threw, as [KortexError.SurfaceCrashed]; it could not be placed, as
     * [KortexError.UnspannableAxis], say; or it failed to follow a new size or scale from the compositor, as
     * [KortexError.ShmAllocationFailed].
     */
    public data class Failed(public val error: KortexError) : SurfaceError<Nothing>
}
