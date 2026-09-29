package com.fromwau.kortex.wayland

import androidx.compose.runtime.Applier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.State
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
     * Ends the application: every surface call leaves composition, each surface ending with
     * `Ok(SurfaceEnd.LeftComposition)` unless its content throws as it goes, and [kortexApplication] returns. Safe to
     * call from any thread, and more than once.
     */
    public fun exitApplication()
}

/**
 * Runs your application: the surfaces on screen are the ones [content] calls, each for as long as its call is in
 * composition.
 *
 * There are four kinds. [LayerSurface], and the presets over it such as [Bar], put a surface of your own where a
 * desktop puts a panel, a dock or a wallpaper: pinned to a monitor's edges, in a layer above or below the
 * windows, and able to reserve the space other windows are tiled around. [Window] is an ordinary application
 * window, which the compositor places, sizes and lists beside every other. [Dialog] is a window the compositor is
 * told belongs to the window whose content showed it. [Popup], and [ContextMenu] over it, opens a menu, a tooltip
 * or a popover over the surface whose content called it, stacked above that surface.
 *
 * A window, a dialog and a popup can be opened from a surface's content as well as from here, so a bar can show a
 * menu and a window can show a dialog.
 *
 * ```kotlin
 * fun main() {
 *     kortexApplication {
 *         var showing by remember { mutableStateOf(true) }
 *         if (showing) Bar { Text("12:00") }
 *     }.onError { exitProcess(1) }
 * }
 * ```
 *
 * kortex needs `zwlr_layer_shell_v1`, the protocol every [LayerSurface] is built on, and asks for it before it
 * opens anything. A compositor without it fails here with [KortexError.MissingGlobal] naming that protocol, and
 * no surface of any kind opens, [Window] included. That is the wlroots compositors, Hyprland and sway among
 * them, and KWin; Weston and GNOME implement no layer shell and cannot run a kortex application.
 *
 * Blocks the calling thread until the application ends. [content] and every surface's content run on that thread,
 * which also draws, so blocking inside an effect stalls every surface; move blocking work off it, e.g. with
 * `withContext(Dispatchers.IO)`.
 *
 * An application with no surface on screen keeps running until [KortexApplicationScope.exitApplication] is called.
 *
 * @param platform hooks the surfaces' content drives, e.g. the cursor shape a hover asks for.
 * @param content your application's state and its surface calls. It draws nothing itself: UI belongs in a surface's
 *   own content.
 * @return `Ok(Unit)` once `exitApplication()` has ended the application, with every surface ended.
 *   [KortexError.NoCompositorResponse], [KortexError.ConnectionError], [KortexError.ProtocolViolation] or
 *   [KortexError.MissingGlobal] when the compositor cannot be reached, goes away, or lacks what kortex needs. When
 *   the connection fails while the application runs, every surface you still show ends with that same error, unless
 *   its content throws as it goes. [KortexError.ApplicationCrashed] when your own code threw, UI placed directly in
 *   [content] included.
 */
public fun kortexApplication(
    platform: KortexPlatform = KortexPlatform.None,
    content: @Composable KortexApplicationScope.() -> Unit,
): EmptyResult<KortexError> =
    WaylandDisplay.connect().flatMap { display ->
        display.use {
            KortexShell.createApplication(display, platform, content = content).flatMap { shell ->
                val run = shell.runEventLoop()
                // Whatever the run returned: this is where exitApplication's calls leave and their surfaces end.
                val closed = shell.close()
                run.flatMap { closed }
            }
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
 *         Bar(monitor = monitor, namespace = "bar-${monitor.name}") { Text("12:00") }
 *     }
 * }
 * ```
 *
 * A monitor is listed once the compositor has described it. It leaves the list when it is unplugged, and every
 * surface you put on it ends then, as [LayerSurface]'s `state` describes. Call it in [kortexApplication]'s content
 * or in a surface's content.
 *
 * @throws IllegalStateException when called anywhere else.
 */
@Composable
public fun rememberMonitors(): State<List<Monitor>> = LocalKortexShell.current.monitors

/** The slot of the surface whose content this is, provided around that content; null in the application's own. */
internal val LocalSurfaceSlot: ProvidableCompositionLocal<SurfaceSlot?> = staticCompositionLocalOf { null }

/** The shell a surface call queues its slot with, provided around the application's content. */
internal val LocalKortexShell: ProvidableCompositionLocal<KortexShell> = staticCompositionLocalOf {
    error("a kortex composable must be called inside kortexApplication { }")
}

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
private inline fun runHostCode(
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
    "UI content belongs in a surface's own content, not directly in kortexApplication's content"
