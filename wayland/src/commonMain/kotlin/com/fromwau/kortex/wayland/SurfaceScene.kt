package com.fromwau.kortex.wayland

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.fold
import com.fromwau.kern.result.onSuccess
import com.fromwau.kortex.compose.ContentFailure
import com.fromwau.kortex.compose.KortexCursor
import com.fromwau.kortex.compose.KortexDragSource
import com.fromwau.kortex.compose.KortexPlatform
import com.fromwau.kortex.compose.KortexScene
import com.fromwau.kortex.compose.KortexSurfaceHandle
import com.fromwau.kortex.compose.KortexTextInput
import com.fromwau.kortex.compose.LocalKortexSurface
import java.util.concurrent.atomic.AtomicReference

/**
 * The Compose side of one surface call: its composition, the work that composition runs, the size its content reads
 * and the first failure that content caused.
 *
 * It outlives the Wayland objects drawn on it. `get_layer_surface` fixes a layer surface's monitor and namespace, so
 * a call that changes either gets new ones through [KortexSurface.detach] and [KortexSurface.attach], while the
 * content here keeps its state, its running effects and the size it last read.
 */
internal class SurfaceScene(
    /**
     * What the compositor calls the surface this is drawn on, which names a crash of its content. A rebuild sets it
     * to the namespace the new surface carries, so a crash names the surface the content was on when it threw.
     *
     * A property rather than a parameter kept beside one: a parameter would shadow it inside the initializers
     * below, and the composition's onFailure would then name every crash after the first surface.
     */
    // Read from whichever thread content failed on, and written by the loop thread as it rebuilds.
    @Volatile var namespace: String,
    private val loop: LoopQueue,
    platform: KortexPlatform,
    private val onCrash: (KortexError.SurfaceCrashed) -> Unit,
) : AutoCloseable {

    // Rides in the frame context below, so the loop can run this scene's work and leave its siblings' where it is.

    private val work = SurfaceWork()

    // The surface drawing this scene, which a rebuild exchanges; null before the first attach and after a detach.
    // Read from whichever thread content invalidates or asks for a cursor on, and written by the loop thread.
    @Volatile
    private var surface: KortexSurface? = null

    // The text-input session content has open, which belongs to the content rather than to any one surface.
    private val openTextInput = AtomicReference<KortexTextInput?>(null)

    // Named as content throws rather than as the shell reports, so the name is the surface it was on at the time.
    // Written from whichever thread content failed on, and read by the loop thread.
    private val firstCrash = AtomicReference<KortexError.SurfaceCrashed?>(null)

    private val hostPlatform = object : KortexPlatform {
        override fun setCursor(cursor: KortexCursor) {
            surface?.setCursor(cursor)
            platform.setCursor(cursor)
        }

        override fun onTextInputStarted(session: KortexTextInput) {
            openTextInput.set(session)
            platform.onTextInputStarted(session)
        }

        override fun onTextInputStopped() {
            openTextInput.set(null)
            platform.onTextInputStopped()
        }

        // One drag, so the surface drawing this scene carries it where there is one, and the host otherwise.
        override fun startDrag(dragged: KortexDragSource): Boolean {
            val surface = surface ?: return platform.startDrag(dragged)
            // The reason stops here: ClipboardError is this module's, and Compose's own channel for a drag that
            // did not start carries no reason either, only that the gesture did not complete.
            return surface.startDrag(dragged).fold(onSuccess = { true }, onError = { false })
        }
    }

    /** The composition a surface renders into its buffers and hands the input off its own seat to. */
    val composition: KortexScene = KortexScene(
        size = IntSize.Zero,
        density = Density(1f),
        frameContext = loop + work,
        onInvalidate = { surface?.invalidate() },
        platform = hostPlatform,
        onFailure = ::recordCrash,
    )

    // Every failure the composition records, wherever what failed was run. The composition is the only way in,
    // so a surface cannot end as crashed while the scene behind it still runs content.
    private fun recordCrash(failure: ContentFailure) {
        val crashed = KortexError.SurfaceCrashed(namespace, failure)
        // The first failure recorded here is the one the surface ends with; later ones only wake the loop.
        firstCrash.compareAndSet(null, crashed)
        onCrash(crashed)
    }

    /** What content has open for typing into, which the surface's keyboard turns unconsumed keys into edits on. */
    val textInput: KortexTextInput? get() = openTextInput.get()

    /**
     * The logical (surface-local) size the surface drawing this was last configured at, which content reads as its
     * own size. Snapshot state, so a configure recomposes whatever reads it.
     */
    var logicalSize: IntSize by mutableStateOf(IntSize.Zero)

    /** What this scene's content threw, once it has; the scene then runs none of it. */
    val crash: KortexError.SurfaceCrashed? get() = firstCrash.get()

    /** Whether content has been composed; a scene with none has nothing to draw. */
    var composed: Boolean = false
        private set

    private val handle: KortexSurfaceHandle = object : KortexSurfaceHandle {
        override val size: IntSize get() = logicalSize

        override fun close() {
            // No shown surface's content reaches this: ShownContent provides its slot's scope as LocalKortexSurface.
            surface?.requestClose()
        }
    }

    /**
     * Composes [content] and draws it on the surface this is attached to, if any.
     *
     * @return what content threw while composing, as [KortexError.SurfaceCrashed].
     */
    fun setContent(content: @Composable () -> Unit): EmptyResult<KortexError> {
        composition
            .setContent { CompositionLocalProvider(LocalKortexSurface provides handle) { content() } }
            .onSuccess {
                composed = true
                surface?.drawNow()
            }
        return crash?.let { Err(it) } ?: Ok(Unit)
    }

    /** Draws this scene on [surface] from now on. */
    fun drawOn(surface: KortexSurface) {
        check(this.surface == null) { "a scene is drawn on one surface; detach() gives back the one holding it" }
        this.surface = surface
    }

    /** Stops [surface] being asked to draw this or to act on its content's behalf. */
    fun stopDrawingOn(surface: KortexSurface) {
        if (this.surface === surface) this.surface = null
    }

    /**
     * Disposes the composition, even a failed one, and runs the work its cancellation leaves behind, so what that
     * work throws is in [crash] before its surface is reported. Nothing this content schedules on the loop after
     * that runs: a cleanup that has hopped to another dispatcher finishes there, and its return here is dropped,
     * throw and all, because running the return would run content whose surface has ended.
     */
    override fun close() {
        composition.close()
        // The scene's recomposer leaves Compose's global snapshot observers only as its cancelled run loop resumes.
        // Only this scene's work: a whole-queue drain would run its siblings' work too, and spend the bound on it.
        loop.drain(work)
        work.close()
    }
}
