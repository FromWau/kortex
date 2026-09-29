package com.fromwau.kortex.wayland

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntSize
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** What a surface call asks for: a surface placed with other settings is changed to these. */
internal sealed class SurfaceSettings {
    /** Whether a surface placed with [placed] has to be made again rather than changed. */
    fun rebuildsOver(placed: SurfaceSettings): Boolean =
        placed::class != this::class || rebuildsOverSameKind(placed)

    /** Whether [placed], always of this same kind, differs in something a live surface cannot be given. */
    protected abstract fun rebuildsOverSameKind(placed: SurfaceSettings): Boolean
}

/** What [SurfaceSlot.ownEnding] finds: whether its surface has ended by itself, and if so, how. */
internal sealed interface OwnEnding {
    data object NotEnded : OwnEnding

    data class Ended(val ending: Result<SurfaceEnd, KortexError>) : OwnEnding
}

/**
 * What the shell holds for one surface call: the newest content and state that call composed with, what it asks
 * for, the scene it runs and the surface drawn on it, and the ending its content asked for.
 *
 * @param state where its call publishes what its surface is doing; a later composition can hand it another.
 * @param parent the slot of the surface whose content this call is in; null for a call in the application's own
 *   content.
 */
internal class SurfaceSlot(
    val content: State<@Composable SurfaceScope.() -> Unit>,
    private var state: PublishedProgress,
    private val wake: () -> Unit,
    private val parent: SurfaceSlot?,
) {
    // The parent's scene as this call entered its content: a new scene there takes this call out with it. A rebuild
    // puts a new surface under the same scene, and this call is still part of the content on it.
    private val parentScene: SurfaceScene? = parent?.scene

    /** Whether the surface whose content this call is in has gone. */
    val parentGone: Boolean get() = parent != null && parent.scene !== parentScene

    /** What the surface whose content this call is in is built on, which a popup here is parented to. */
    val parentRole: SurfaceRole? get() = parent?.surface?.role

    /** What names the surface whose content this call is in; null for a call in the application's own content. */
    val parentName: String? get() = parent?.scene?.namespace

    /** Whether this call is in [other]'s content, or in the content of a surface opened from it. */
    fun under(other: SurfaceSlot): Boolean = generateSequence(parent) { it.parent }.any { it === other }

    // The settings its call asks for while in composition, and null once it has left. Loop thread only.
    var wanted: SurfaceSettings? = null

    // Null until placed, and again once it has ended. Snapshot state, written on the loop thread outside composition,
    // so a test driving the loop from a thread of its own sees it change.
    var surface: KortexSurface? by mutableStateOf(null)

    // The Compose side of this call, which outlives the surfaces built around it. Loop thread only.
    var scene: SurfaceScene? = null

    // What surface was placed with, which a change of settings replaces it over. Loop thread only.
    var placedWith: SurfaceSettings? = null

    // Set as its ending is published: whatever the shell sees of this slot afterwards reports nothing. Loop thread
    // only, and not the state's own status, which the call can swap under it.
    var reported = false

    // What its surface is doing, which every state this call binds is given. Composition and loop thread, both the
    // one thread the application runs on.
    private var progress: SurfaceProgress = SurfaceProgress.Placing

    // What its window last reported about itself, given to every state this call binds, on the same threads.
    private var windowStates: WindowStates = WindowStates()

    /** Whether this call still holds its surface; one that has left composition or ended publishes nothing more. */
    val live: Boolean get() = wanted != null && !reported

    private val requested = AtomicReference<Result<SurfaceEnd, KortexError>?>(null)

    private val closeDeclined = AtomicBoolean(false)

    // Asks made from content's thread and sent on the loop's, which is the only one that may marshal.
    private val windowAsks = ConcurrentLinkedQueue<(KortexSurface, XdgToplevelSurface) -> Unit>()

    /** What content sees as its own `this`, and as `LocalKortexSurface`, for as long as its call composes. */
    val scope: SurfaceScope = object : SurfaceScope {
        // From the scene, which outlives a rebuild: the size holds until the surface that takes over is configured.
        override val size: IntSize get() = scene?.logicalSize ?: IntSize.Zero

        override fun close() = requestEnd(Ok(SurfaceEnd.Closed))
    }

    /** Asks for [ending], from any thread; the shell acts on it in its next pass. The first ask decides. */
    fun requestEnd(ending: Result<SurfaceEnd, KortexError>) {
        if (requested.compareAndSet(null, ending)) wake()
    }

    /**
     * Asks for the compositor's close request to be taken back, from any thread; the shell acts on it in its next
     * pass, and a call holding anything but a window has none to take back.
     */
    fun declineClose() {
        closeDeclined.set(true)
        wake()
    }

    /**
     * Queues an ask for this window's state, from any thread; the shell sends it in its next pass, and a call
     * holding anything but a window on screen sends none.
     */
    fun askWindow(request: (KortexSurface, XdgToplevelSurface) -> Unit) {
        windowAsks += request
        wake()
    }

    /**
     * Whether its surface has ended by itself, and how: the ending its content asked for, else its content's crash,
     * else the compositor's close, or a test's [KortexSurface.simulateCompositorClose].
     */
    fun ownEnding(): OwnEnding {
        val standing = requested.get()
        val placed = surface
        // From the scene, which outlives a rebuild, and whose content is what threw.
        val crash = scene?.crash
        return when {
            standing != null -> OwnEnding.Ended(standing)
            crash != null -> OwnEnding.Ended(Err(crash))
            placed?.closed == true -> OwnEnding.Ended(Ok(SurfaceEnd.ClosedByCompositor))
            else -> OwnEnding.NotEnded
        }
    }

    /**
     * Publishes this call's status to [state] from now on, starting with what its surface is doing already: a state
     * another call has finished with reads this call's own status from here on.
     *
     * Two calls holding a surface each cannot share one state, and that is API misuse.
     */
    fun bindTo(state: PublishedProgress) {
        val bound = state.boundTo
        check(bound == null || bound === this || !bound.live) { SURFACE_STATE_SHARED }
        // The state this call leaves is free for another: nothing of this one reaches it again.
        if (this.state !== state && this.state.boundTo === this) this.state.boundTo = null
        state.boundTo = this
        this.state = state
        publish(progress)
        publish(windowStates)
    }

    /** Publishes that its surface is on screen, drawing [scene] at whatever size the compositor gives it. */
    fun onScreen(scene: SurfaceScene) {
        publish(SurfaceProgress.OnScreen(scene))
    }

    /** Publishes [ending] as the last status this call shows. */
    fun report(ending: Result<SurfaceEnd, KortexError>) {
        publish(SurfaceProgress.Ended(ending))
    }

    /** Publishes what the window this call holds reports about itself; a call holding anything else has none. */
    fun followWindow() {
        val window = surface
        val toplevel = window?.role as? XdgToplevelSurface
        if (window == null || toplevel == null) {
            // A window still being placed, or one that has ended, takes no ask, as WindowState's own docs say.
            windowAsks.clear()
            return
        }
        // Taken back here, where the window is read: a decline acted on anywhere else is republished away below.
        if (closeDeclined.getAndSet(false)) toplevel.declineClose()
        // Before the publish below, so a state the compositor answers with is read in the pass that follows.
        generateSequence(windowAsks::poll).forEach { request -> request(window, toplevel) }
        publish(
            WindowStates(
                closeRequested = toplevel.closeRequested,
                maximized = toplevel.maximized,
                fullscreen = toplevel.fullscreen,
                tiled = toplevel.tiled,
                activated = toplevel.activated,
                capabilities = toplevel.capabilities,
            ),
        )
    }

    private fun publish(next: SurfaceProgress) {
        progress = next
        // Only while this call still holds the state: a call that takes it over owns what it shows from then on.
        if (state.boundTo === this) state.progress = next
    }

    private fun publish(next: WindowStates) {
        windowStates = next
        if (state.boundTo === this) state.windowStates = next
    }
}

/**
 * The route every surface call places through: one slot for as long as the call composes, asking the shell on each
 * composition for the surface [settings] describes, publishing what that surface does to [published], and asking for
 * it to go once the call leaves composition.
 */
@Composable
internal fun SurfaceCall(
    settings: SurfaceSettings,
    published: PublishedProgress,
    content: @Composable SurfaceScope.() -> Unit,
) {
    val shell = LocalKortexShell.current
    val newestContent = rememberUpdatedState(content)
    val parent = LocalSurfaceSlot.current
    val slot = remember {
        SurfaceSlot(
            content = newestContent,
            state = published,
            wake = shell::wake,
            parent = parent,
        )
    }
    SideEffect {
        slot.bindTo(published)
        shell.queueUpdate(slot, settings)
    }
    DisposableEffect(Unit) { onDispose { shell.queueRemove(slot) } }
}

/** What a surface call fails with when it is handed a state another call is still publishing to; a test reads it. */
internal const val SURFACE_STATE_SHARED =
    "a state belongs to one surface call at a time: give this call a state of its own"
