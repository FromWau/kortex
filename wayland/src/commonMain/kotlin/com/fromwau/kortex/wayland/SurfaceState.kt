package com.fromwau.kortex.wayland

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntSize
import com.fromwau.kern.result.Result

/** What a surface is doing, as [SurfaceState.status] reports it. */
public sealed interface SurfaceStatus {
    /** The surface has not reached the screen yet: it has no size, and nothing of its content is drawn. */
    public data object Placing : SurfaceStatus

    /**
     * The surface is on screen, drawing its content.
     *
     * @property size the logical (surface-local) size the compositor gave it, which is smaller than its size in
     *   physical pixels by the scale of the monitor it is on.
     */
    public data class OnScreen(public val size: IntSize) : SurfaceStatus

    /**
     * The surface has gone for good: nothing takes its place while the call that showed it stands, and this stays
     * the status until that call leaves composition.
     *
     * @property result how it ended. `Ok` carries a [SurfaceEnd]: `close()`, the compositor, an unplugged monitor
     *   or the call leaving composition. `Err` carries what ended it instead: [KortexError.SurfaceCrashed] when
     *   your content threw; [KortexError.UnspannableAxis], [KortexError.NegativeSize],
     *   [KortexError.InvalidExclusiveEdge] or [KortexError.InvalidExclusiveZone] when the settings asked for could
     *   not be placed; [KortexError.MissingSeatDevice], [KortexError.SurfaceNotConfigured] or
     *   [KortexError.ShmAllocationFailed] when the compositor would not give the surface what it needs to draw; and
     *   the same error [kortexApplication] returns when the connection to the compositor failed.
     */
    public data class Ended(public val result: Result<SurfaceEnd, KortexError>) : SurfaceStatus
}

/**
 * What one surface call's surface is doing, as state: whatever reads [status] while it composes is recomposed each
 * time the surface's status changes.
 *
 * ```kotlin
 * val bar = rememberSurfaceState()
 * var showing by remember { mutableStateOf(true) }
 * val status = bar.status
 * LaunchedEffect(status) { if (status is SurfaceStatus.Ended) showing = false }
 *
 * when {
 *     showing -> Bar(state = bar) { Text("12:00") }
 *     else -> Osd(width = 320.dp, height = 96.dp) { Text("the bar stopped: $status") }
 * }
 * ```
 *
 * [rememberSurfaceState] keeps one where the call is, which is enough to read it beside that call. Remembering your
 * own further up keeps it readable after you have taken the call out of composition, where it holds the ending it
 * last saw. Putting the call back reuses the same state: it goes to [SurfaceStatus.Placing] and on as the new
 * surface is placed, so you read a surface that ended and came back.
 *
 * Decide what to show from state of your own, not from [status]: a call you compose only while its status is not
 * [SurfaceStatus.Ended] can never come back, because that call is what would clear the ending. Read [status] for
 * what happened, and keep what to show beside it.
 *
 * One state belongs to one surface call at a time. Two calls each holding a surface cannot share one, and handing a
 * second call a state the first is still publishing to ends what that call is part of: a call in
 * [kortexApplication]'s own content ends the application with [KortexError.ApplicationCrashed], while a call in
 * another surface's content ends that surface with [KortexError.SurfaceCrashed] and leaves the application running.
 */
public class SurfaceState {
    internal val published: PublishedProgress = PublishedProgress()

    /** What the surface is doing now. */
    public val status: SurfaceStatus
        get() = published.statusOf(SurfaceStatus.Placing, SurfaceStatus::OnScreen, SurfaceStatus::Ended)
}

/**
 * A [SurfaceState] remembered where it is called, for the surface call beside it.
 *
 * [SurfaceState] says what its status is for, and why what to show belongs in state of your own beside it.
 */
@Composable
public fun rememberSurfaceState(): SurfaceState = remember { SurfaceState() }

/**
 * What one surface call publishes about its surface, and which call is publishing it: where a [SurfaceState] and a
 * [WindowState] each read their own status from.
 */
internal class PublishedProgress {
    // Written on the one thread the application runs on, by its loop and by the effects of a composition on it;
    // the status each state makes of it is read wherever a caller composes.
    var progress: SurfaceProgress by mutableStateOf(SurfaceProgress.Placing)

    // Written and read as progress is; a call holding anything but a window never writes it.
    var windowStates: WindowStates by mutableStateOf(WindowStates())

    // The call publishing here, which is how a second call taking this state while the first holds it is caught,
    // and how an ask made on a state reaches its call from whichever thread made it.
    @Volatile
    var boundTo: SurfaceSlot? = null
}

/** What a window reports about itself beside its size, which only a [WindowState] reads. */
internal data class WindowStates(
    val closeRequested: Boolean = false,
    val maximized: Boolean = false,
    val fullscreen: Boolean = false,
    val tiled: Boolean = false,
    val activated: Boolean = false,
    val capabilities: Set<XdgToplevelCapability> = XdgToplevelCapability.ALL,
)

/** What a state reports, made of what its call published: [placing], [onScreen] with its size, or [ended]. */
internal fun <T> PublishedProgress.statusOf(
    placing: T,
    onScreen: (IntSize) -> T,
    ended: (Result<SurfaceEnd, KortexError>) -> T,
): T = when (val current = progress) {
    SurfaceProgress.Placing -> placing
    // Read from the scene rather than copied out of it, so the size here is the size content is drawn at.
    is SurfaceProgress.OnScreen -> onScreen(current.scene.logicalSize)
    is SurfaceProgress.Ended -> ended(current.result)
}

/** Where a [PublishedProgress] holds what a status is made of. */
internal sealed interface SurfaceProgress {
    data object Placing : SurfaceProgress

    data class OnScreen(val scene: SurfaceScene) : SurfaceProgress

    data class Ended(val result: Result<SurfaceEnd, KortexError>) : SurfaceProgress
}
