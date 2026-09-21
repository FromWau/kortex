package com.fromwau.kortex.wayland

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntSize
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import java.util.concurrent.atomic.AtomicReference

/** What [SurfaceSlot.ownEnding] finds: whether its surface has ended by itself, and if so, how. */
internal sealed interface OwnEnding {
    data object NotEnded : OwnEnding

    data class Ended(val ending: Result<SurfaceEnd, KortexError>) : OwnEnding
}

/**
 * What the shell holds for one surface call: the newest content and state that call composed with, what it asks
 * for, the scene it runs and the surface drawn on it, and the ending its content asked for.
 *
 * @param state where its call reads what its surface is doing; a later composition can hand it another.
 * @param parent the slot of the surface whose content this call is in; null for a call in the application's own
 *   content.
 */
internal class SurfaceSlot(
    val content: State<@Composable SurfaceScope.() -> Unit>,
    var state: SurfaceState,
    private val wake: () -> Unit,
    private val parent: SurfaceSlot?,
) {
    // The parent's scene as this call entered its content: a new scene there takes this call out with it. A rebuild
    // puts a new surface under the same scene, and this call is still part of the content on it.
    private val parentScene: SurfaceScene? = parent?.scene

    /** Whether the surface whose content this call is in has gone. */
    val parentGone: Boolean get() = parent != null && parent.scene !== parentScene

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

    private val requested = AtomicReference<Result<SurfaceEnd, KortexError>?>(null)

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

    /** Publishes that its surface is on screen, drawing [scene] at whatever size the compositor gives it. */
    fun onScreen(scene: SurfaceScene) {
        state.progress = SurfaceProgress.OnScreen(scene)
    }

    /** Publishes [ending] as the last status the newest state its call composed with will show. */
    fun report(ending: Result<SurfaceEnd, KortexError>) {
        state.progress = SurfaceProgress.Ended(ending)
    }
}
