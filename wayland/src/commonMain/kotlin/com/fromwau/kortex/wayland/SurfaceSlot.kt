package com.fromwau.kortex.wayland

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntSize
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.IError
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import java.util.concurrent.atomic.AtomicReference

/** What [SurfaceSlot.ownEnding] finds: whether its surface has ended by itself, and if so, how. */
internal sealed interface OwnEnding {
    data object NotEnded : OwnEnding

    data class Ended(val ending: Result<SurfaceEnd, SurfaceError<IError>>) : OwnEnding
}

/**
 * What the shell holds for one surface call: the newest content and `onClose` that call composed with, what it asks
 * for, its surface, and the ending its content asked for.
 *
 * @param parent the slot of the surface whose content this call is in; null for a call in the application's own
 *   content.
 */
internal class SurfaceSlot(
    val content: State<@Composable SurfaceScope<IError>.() -> Unit>,
    val onClose: State<(Result<SurfaceEnd, SurfaceError<IError>>) -> Unit>,
    private val wake: () -> Unit,
    private val parent: SurfaceSlot?,
) {
    // The parent's surface as this call entered its content: a new surface there takes this call out with it.
    private val parentSurface: KortexSurface? = parent?.surface

    /** Whether the surface whose content this call is in has gone. */
    val parentGone: Boolean get() = parent != null && parent.surface !== parentSurface

    // The settings its call asks for while in composition, and null once it has left. Loop thread only.
    var wanted: SurfaceSettings? = null

    // Null until placed, and again once it has ended. Snapshot state, written on the loop thread outside composition,
    // so content that reads size recomposes as the surface is placed or goes, as it does on a configure.
    var surface: KortexSurface? by mutableStateOf(null)

    // What surface was placed with, which a change of settings replaces it over. Loop thread only.
    var placedWith: SurfaceSettings? = null

    // Set as its onClose is called: whatever the shell sees of this slot afterwards reports nothing. Loop thread only.
    var reported = false

    private val requested = AtomicReference<Result<SurfaceEnd, SurfaceError<IError>>?>(null)

    /** What content sees as its own `this`, and as `LocalKortexSurface`, for as long as its call composes. */
    val scope: SurfaceScope<IError> = object : SurfaceScope<IError> {
        override val size: IntSize get() = surface?.logicalSize ?: IntSize.Zero

        override fun close() = requestEnd(Ok(SurfaceEnd.Closed))

        override fun close(error: IError) = requestEnd(Err(SurfaceError.Closed(error)))
    }

    /** Asks for [ending], from any thread; the shell acts on it in its next pass. The first ask decides. */
    fun requestEnd(ending: Result<SurfaceEnd, SurfaceError<IError>>) {
        if (requested.compareAndSet(null, ending)) wake()
    }

    /**
     * Whether its surface has ended by itself, and how: the ending its content asked for, else its content's crash,
     * else the compositor's close, or a test's [KortexSurface.simulateCompositorClose].
     */
    fun ownEnding(): OwnEnding {
        val standing = requested.get()
        val placed = surface
        val crash = placed?.crash
        return when {
            standing != null -> OwnEnding.Ended(standing)
            crash != null -> OwnEnding.Ended(Err(SurfaceError.Failed(crash)))
            placed?.closed == true -> OwnEnding.Ended(Ok(SurfaceEnd.ClosedByCompositor))
            else -> OwnEnding.NotEnded
        }
    }

    /** Hands [ending] to the newest `onClose` its call composed with. */
    fun report(ending: Result<SurfaceEnd, SurfaceError<IError>>) {
        onClose.value(ending)
    }
}
