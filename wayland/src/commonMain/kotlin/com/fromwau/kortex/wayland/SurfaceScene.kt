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
import com.fromwau.kern.result.onSuccess
import com.fromwau.kortex.compose.KortexCursor
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
    /** What the compositor calls the surface this is drawn on, which names a crash of its content. */
    val namespace: String,
    private val loop: LoopQueue,
    platform: KortexPlatform,
    onCrash: (KortexError.SurfaceCrashed) -> Unit,
) : AutoCloseable {

    // Rides in the frame context below, so the loop can run this scene's work and leave its siblings' where it is.
    private val work = SurfaceWork()

    // The surface drawing this scene, which a rebuild exchanges; null before the first attach and after a detach.
    private var surface: KortexSurface? = null

    // The text-input session content has open, which belongs to the content rather than to any one surface.
    private val openTextInput = AtomicReference<KortexTextInput?>(null)

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
    }

    /** The composition a surface renders into its buffers and hands the input off its own seat to. */
    val composition: KortexScene = KortexScene(
        size = IntSize.Zero,
        density = Density(1f),
        frameContext = loop + work,
        onInvalidate = { surface?.invalidate() },
        platform = hostPlatform,
        onFailure = { failure -> onCrash(KortexError.SurfaceCrashed(namespace, failure)) },
    )

    /** What content has open for typing into, which the surface's keyboard turns unconsumed keys into edits on. */
    val textInput: KortexTextInput? get() = openTextInput.get()

    /**
     * The logical (surface-local) size the surface drawing this was last configured at, which content reads as its
     * own size. Snapshot state, so a configure recomposes whatever reads it.
     */
    var logicalSize: IntSize by mutableStateOf(IntSize.Zero)

    /** What this scene's content threw, once it has; the scene then runs none of it. */
    val crash: KortexError.SurfaceCrashed?
        get() = composition.failure?.let { KortexError.SurfaceCrashed(namespace, it) }

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

    /** Draws this scene on [surface] from now on, in place of whichever surface drew it before. */
    fun drawOn(surface: KortexSurface) {
        this.surface = surface
    }

    /** Stops [surface] being asked to draw this or to act on its content's behalf. */
    fun stopDrawingOn(surface: KortexSurface) {
        if (this.surface === surface) this.surface = null
    }

    /** Disposes the composition, even a failed one, and runs the work its cancellation leaves behind. */
    override fun close() {
        composition.close()
        // The scene's recomposer leaves Compose's global snapshot observers only as its cancelled run loop resumes.
        // Only this scene's work: a whole-queue drain would run its siblings' work too, and spend the bound on it.
        loop.drain(work)
    }
}
