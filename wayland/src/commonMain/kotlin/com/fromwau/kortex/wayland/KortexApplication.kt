package com.fromwau.kortex.wayland

import androidx.compose.runtime.Applier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.FrameRecomposer
import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.flatMap
import com.fromwau.kortex.compose.KortexPlatform
import kotlinx.coroutines.CoroutineExceptionHandler

/** What your application's content can do besides composing: end the application. */
public interface KortexApplicationScope {
    /**
     * Ends the application: every [Show] leaves composition, each surface's `onClose` receives
     * `Ok(SurfaceEnd.LeftComposition)` unless its content throws as it goes, and [kortexApplication] returns. Safe to
     * call from any thread, and more than once.
     */
    public fun exitApplication()
}

/**
 * Runs your application: the [LayerSurface]s on screen are the ones [content] shows with [Show].
 *
 * ```kotlin
 * fun main() {
 *     kortexApplication {
 *         var level by remember { mutableStateOf<Float?>(0.4f) }
 *         level?.let { Show(VolumeOsd(it, onClose = { level = null })) }
 *     }.onError { exitProcess(1) }
 * }
 * ```
 *
 * Blocks the calling thread until the application ends. [content], every surface's content and every `onClose` run
 * on that thread, which also draws, so blocking inside an effect stalls every surface; move blocking work off it,
 * e.g. with `withContext(Dispatchers.IO)`.
 *
 * An application with no surface on screen keeps running until [KortexApplicationScope.exitApplication] is called.
 *
 * @param platform hooks the surfaces' content drives, e.g. the cursor shape a hover asks for.
 * @param content your application's state and [Show] calls. It draws nothing itself: UI belongs in a surface's
 *   `invoke()`.
 * @return `Ok(Unit)` once `exitApplication()` has ended the application, with every surface's `onClose` called.
 *   [KortexError.NoCompositorResponse], [KortexError.ConnectionError], [KortexError.ProtocolViolation] or
 *   [KortexError.MissingGlobal] when the compositor cannot be reached, goes away, or lacks what kortex needs. When
 *   the connection fails while the application runs, the `onClose` of every surface you still show receives that
 *   same error, as `Err(SurfaceError.Failed(error))`.
 *   [KortexError.ApplicationCrashed] when your own code threw, UI placed directly in [content] included.
 */
public fun kortexApplication(
    platform: KortexPlatform = KortexPlatform.None,
    content: @Composable KortexApplicationScope.() -> Unit,
): EmptyResult<KortexError> =
    WaylandDisplay.connect().flatMap { display ->
        display.use {
            KortexShell.createApplication(display, platform, content = content).flatMap { shell ->
                val run = shell.runEventLoop()
                // Whatever the run returned: this is where exitApplication's Shows leave and their surfaces report.
                val closed = shell.close()
                run.flatMap { closed }
            }
        }
    }

/**
 * Keeps [surface] on screen while this call is in composition, in [kortexApplication]'s content or in a surface's
 * own content.
 *
 * The surface appears shortly after `Show` enters composition, and goes when `Show` leaves it, reporting
 * `Ok(SurfaceEnd.LeftComposition)` to its `onClose`. A `Show` in a surface's `invoke()` leaves composition when that
 * surface ends, however it ends, so the surface it shows goes then too.
 *
 * Each recomposition hands `Show` a new instance. While the instances keep one class and equal settings, the
 * surface keeps running with the newest instance's content and `onClose`. When the class or the settings change, a
 * new surface replaces it, and no `onClose` is called, unless the old surface's content throws as it goes, which
 * ends it instead.
 *
 * Once the surface has ended by itself, in any of the ways [LayerSurface.onClose] lists, `Show` shows nothing until
 * you take it out of composition and put it back. Taking it out reports nothing more.
 *
 * @throws IllegalStateException when called anywhere else.
 */
@Composable
public fun Show(surface: LayerSurface<*>) {
    val shell = LocalKortexShell.current
    val parent = LocalShownSurface.current
    val newest = rememberUpdatedState(surface)
    val shown = remember { ShownSurface(newest, shell::wake, parent) }
    SideEffect { surface.heldBy = shown }
    val settings = surface.settings
    DisposableEffect(settings) {
        shell.queuePlace(shown, settings)
        onDispose { shell.queueRemove(shown) }
    }
}

/**
 * The monitors connected to the desktop, as state: content that reads it recomposes when a monitor is plugged in or
 * unplugged. Key what you show by the monitor, so each surface stays with its own monitor as others come and go:
 *
 * ```kotlin
 * kortexApplication {
 *     val monitors by rememberMonitors()
 *     for (monitor in monitors) key(monitor) {
 *         Show(StatusBar(monitor))
 *     }
 * }
 * ```
 *
 * A monitor is listed once the compositor has described it. It leaves the list when it is unplugged, and every
 * surface you put on it ends then, as [LayerSurface.onClose] describes. Call it in [kortexApplication]'s content or
 * in a surface's content.
 *
 * @throws IllegalStateException when called anywhere else.
 */
@Composable
public fun rememberMonitors(): State<List<Monitor>> = LocalKortexShell.current.monitors

/** The Show whose surface's content this is, provided around that content; null in the application's own content. */
internal val LocalShownSurface: ProvidableCompositionLocal<ShownSurface?> = staticCompositionLocalOf { null }

/** The shell a [Show] queues its surface with, provided around the application's content. */
internal val LocalKortexShell: ProvidableCompositionLocal<KortexShell> =
    staticCompositionLocalOf { error("Show and rememberMonitors() work only inside kortexApplication") }

/**
 * The composition [kortexApplication]'s content runs in. It has no UI of its own, and recomposes on the loop's
 * thread, in the pass after it asks for a frame.
 */
@OptIn(InternalComposeUiApi::class)
internal class ApplicationComposition(
    loopQueue: LoopQueue,
    private val wake: () -> Unit,
    // Handed whatever the application's own code throws, on whichever thread it threw.
    private val onFailure: (Throwable) -> Unit,
) {
    @Volatile
    private var frameRequested = false

    // Recomposition and the content's effects fail inside coroutines, never out of a call, so they report here.
    private val coroutineFailures = CoroutineExceptionHandler { _, cause -> onFailure(cause) }

    private val recomposer = FrameRecomposer(loopQueue + coroutineFailures) {
        frameRequested = true
        wake()
    }

    private val composition = Composition(ApplicationApplier(), recomposer.compositionContext)

    fun setContent(content: @Composable () -> Unit) {
        runHostCode(onFailure) { composition.setContent(content) }
    }

    /** Recomposes, if the content has asked to since the last frame. */
    fun frame() {
        if (!frameRequested) return
        frameRequested = false
        runHostCode(onFailure) { recomposer.performFrame(System.nanoTime()) }
    }

    fun close() {
        // Content whose changes failed to apply leaves a composition whose disposal can throw as well.
        runHostCode(onFailure) { composition.dispose() }
        recomposer.close()
    }
}

/** Runs the host's own code, handing whatever it throws to [onFailure] instead of letting it reach kortex's loop. */
internal inline fun runHostCode(
    onFailure: (Throwable) -> Unit,
    call: () -> Unit,
) {
    try {
        call()
    } catch (cause: Throwable) {
        onFailure(cause)
    }
}

/** Takes no node: UI placed directly in the application fails as it is added. */
private class ApplicationApplier : Applier<Any> {
    override val current: Any = Unit

    override fun down(node: Any) = Unit

    override fun up() = Unit

    override fun insertTopDown(
        index: Int,
        instance: Any,
    ) = rejectUi()

    override fun insertBottomUp(
        index: Int,
        instance: Any,
    ) = rejectUi()

    override fun remove(
        index: Int,
        count: Int,
    ) = Unit

    override fun move(
        from: Int,
        to: Int,
        count: Int,
    ) = Unit

    override fun clear() = Unit

    override fun onEndChanges() = Unit

    private fun rejectUi(): Nothing = error(UI_OUTSIDE_A_SURFACE)
}

/** What UI placed directly in the application's content fails with; not private because a test checks for it. */
internal const val UI_OUTSIDE_A_SURFACE =
    "UI content belongs in a LayerSurface's invoke(), not directly in kortexApplication's content"
