package com.fromwau.kortex.wayland

import androidx.compose.runtime.Applier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.FrameRecomposer
import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.flatMap
import com.fromwau.kortex.compose.KortexPlatform
import kotlinx.coroutines.CoroutineExceptionHandler

/** What the content of [kortexApplication] can do besides compose: end the application. */
public interface KortexApplicationScope {
    /** Ends the application. */
    public fun exitApplication()
}

/** Runs [content] as an application whose surfaces are the [LayerSurface]s it shows. */
public fun kortexApplication(
    platform: KortexPlatform = KortexPlatform.None,
    content: @Composable KortexApplicationScope.() -> Unit,
): EmptyResult<KortexError> =
    WaylandDisplay.connect().flatMap { display ->
        display.use {
            KortexShell.createApplication(display, platform, content).flatMap { shell ->
                val run = shell.runEventLoop()
                // Whatever the run returned: this is where exitApplication's Shows leave and their surfaces report.
                val closed = shell.close()
                run.flatMap { closed }
            }
        }
    }

/** Keeps [surface] on screen while this call is in composition. */
@Composable
public fun Show(surface: LayerSurface<*>) {
    val shell = LocalKortexShell.current
    val newest = rememberUpdatedState(surface)
    val shown = remember { ShownSurface(newest, shell::wake) }
    SideEffect { surface.heldBy = shown }
    val settings = surface.settings
    DisposableEffect(settings) {
        shell.queuePlace(shown, settings)
        onDispose { shell.queueRemove(shown) }
    }
}

/** The shell a [Show] queues its surface with, provided around the application's content. */
internal val LocalKortexShell: ProvidableCompositionLocal<KortexShell> =
    staticCompositionLocalOf { error("Show is only called inside kortexApplication's content") }

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
        runHostCode { composition.setContent(content) }
    }

    /** Recomposes, if the content has asked to since the last frame. */
    fun frame() {
        if (!frameRequested) return
        frameRequested = false
        runHostCode { recomposer.performFrame(System.nanoTime()) }
    }

    fun close() {
        // Content whose changes failed to apply leaves a composition whose disposal can throw as well.
        runHostCode { composition.dispose() }
        recomposer.close()
    }

    private inline fun runHostCode(call: () -> Unit) {
        try {
            call()
        } catch (cause: Throwable) {
            onFailure(cause)
        }
    }
}

/** Takes no node: UI placed directly in the application fails as it is added. */
private class ApplicationApplier : Applier<Any> {
    override val current: Any = Unit

    override fun down(node: Any) = Unit

    override fun up() = Unit

    override fun insertTopDown(index: Int, instance: Any) = rejectUi()

    override fun insertBottomUp(index: Int, instance: Any) = rejectUi()

    override fun remove(index: Int, count: Int) = Unit

    override fun move(from: Int, to: Int, count: Int) = Unit

    override fun clear() = Unit

    override fun onEndChanges() = Unit

    private fun rejectUi(): Nothing =
        error("UI content belongs in a LayerSurface's invoke(), not directly in kortexApplication's content")
}
