package com.fromwau.kortex.wayland

import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.flatMap
import com.fromwau.kern.result.getOrElse
import com.fromwau.kortex.compose.KortexCursor
import java.lang.foreign.MemorySegment
import java.util.concurrent.ConcurrentLinkedQueue
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Surface

/**
 * The `zwlr_layer_shell_v1` side of a surface: the layer surface itself, the buffers drawn into it, its frame
 * pacing, its cursor and the seat its input comes off.
 *
 * A [SurfaceScene] is drawn here for as long as it is attached. Frames are paced off `wl_surface.frame` and drawn
 * only when that scene asks for one, so an idle surface costs nothing.
 */
internal class KortexSurface private constructor(
    private val display: WaylandDisplay,
    private val layer: LayerShellSurface,
    private val shm: Shm,
    private var bufferScale: Int,
    private var frames: List<Frame>,
    private val clock: FrameClock,
    private val loop: LoopQueue,
    private val cursorTheme: WlCursorTheme,
    private val cursorSurface: WlCursorSurface,
    private val seat: Seat,
    // Handed the serial of every key, keyboard enter and button; the clipboard quotes one to set the selection.
    private val onInputSerial: (Int) -> Unit,
    // Told as this surface's keyboard gains and loses focus, which gates reading another client's text.
    private val onKeyboardFocus: (keyboard: KeyboardInput, focused: Boolean) -> Unit,
) : AutoCloseable {

    // Filled through post() from any thread and drained only on the loop thread, which is the one thread
    // ever allowed to call into libwayland.
    private val queue = ConcurrentLinkedQueue<() -> Unit>()

    // The scene drawn here, which only detach() gives back. Null before the first attach, and again once the scene
    // has been handed on; a surface closed while it still holds one keeps reading that scene's crash.
    private var scene: SurfaceScene? = null

    // Set by attach() once the seat is bound; null again once the scene is given back or the surface closes.
    @Volatile
    private var pointerInput: PointerInput? = null

    // Null when the config asks for no interactivity, when the seat announced no keyboard, and once released.
    // Settable here so a test can hand one to a surface without keyboard interactivity, since Hyprland gives an
    // interactive surface the user's focus as it maps.
    @Volatile
    internal var keyboardInput: KeyboardInput? = null

    private var logicalWidth: Int = layer.logicalWidth
    private var logicalHeight: Int = layer.logicalHeight

    // Frames a resize replaced while the compositor still held them; reaped once release() clears busy.
    private val retiring = mutableListOf<Frame>()

    // A test cannot make a real compositor send wl_surface.preferred_buffer_scale; this stands in for it.
    internal var scaleOverride: Int? = null

    // A second close() would re-marshal every request below on proxies the first call already freed.
    private var disposed = false

    /** The logical (surface-local) size the compositor last configured this surface at. */
    internal val logicalSize: IntSize get() = IntSize(logicalWidth, logicalHeight)

    /** The buffer (physical-pixel) size of the current frames; exposed so a test can assert a scale reached them. */
    val bufferSize: IntSize
        get() = IntSize(frames.first().buffer.width, frames.first().buffer.height)

    /** How many times the compositor has handed a buffer back; exposed so a test can assert buffers come back. */
    internal val releases: Int get() = frames.sumOf { it.buffer.releases }

    /** How many frames [renderNow] has actually drawn and committed; exposed so a test can assert idle. */
    @Volatile
    internal var renders: Int = 0
        private set

    /**
     * Whether the surface is unmapped: it draws nothing, takes no input and reserves nothing until [show] maps it
     * again, while its composition keeps running.
     */
    @Volatile
    internal var hidden: Boolean = false
        private set

    /** The buffer scale currently committed; exposed so a test can assert a rescale took effect. */
    internal val currentBufferScale: Int get() = bufferScale

    /**
     * True once the compositor has closed this surface, or a test has in its place through [simulateCompositorClose];
     * either way it must be torn down.
     */
    internal val closed: Boolean get() = layer.closed

    /** What the content drawn here threw, once it has; null once the scene it threw in has been detached. */
    internal val crash: KortexError.SurfaceCrashed? get() = scene?.crash

    /** When this surface next needs a loop pass that no Wayland event will announce; null while nothing does. */
    internal val nextDeadlineNanos: Long? get() = keyboardInput?.nextRepeatDueNanos

    /** A test cannot make the compositor close this surface: that needs removing whatever output it chose. */
    internal fun simulateCompositorClose() {
        layer.markClosed()
    }

    /**
     * Draws [scene] here from now on: its content takes this surface's pointer and keyboard, reads the size this
     * surface was configured at, and is drawn at once if it has already been composed.
     *
     * @return what the content drawn here threw, as [KortexError.SurfaceCrashed].
     */
    fun attach(scene: SurfaceScene): EmptyResult<KortexError> {
        check(this.scene == null) { "a surface draws one scene; detach() gives back the one it has" }
        this.scene = scene
        scene.drawOn(this)
        pointerInput =
            seat.attachPointer(scene.composition, bufferScale.toFloat(), cursorTheme, cursorSurface, onInputSerial)
        if (layer.keyboard != KeyboardInteractivity.None) keyboardInput = takeKeyboard()
        display.roundtrip()
        sizeScene()
        renderNow(frameTimeNanos = 0L)
        return scene.crash?.let { Err(it) } ?: Ok(Unit)
    }

    /**
     * Gives back the scene drawn here, leaving it composed and holding everything its content has: its input devices
     * go, and with them the interaction they were in the middle of.
     */
    fun detach() {
        val scene = scene ?: return
        releaseInputs()
        // After the release, which drops the events queued for those proxies: an interaction ended here must not
        // outlive the surface it was on, since the next surface's enter would never clear it.
        scene.composition.cancelPointerInput()
        scene.stopDrawingOn(this)
        this.scene = null
    }

    /**
     * Requests a new size from the compositor, on the loop thread like every request here; exposed so a test can
     * make the compositor configure the surface again.
     *
     * @return what [LayerShellSurface.setSize] rejected, leaving the surface at the size it already had.
     */
    fun requestSize(
        width: Dp,
        height: Dp,
    ): EmptyResult<KortexError> = layer.setSize(width.toLogicalPx(), height.toLogicalPx())

    /**
     * Applies [new] to the live surface, keyboard included: everything changed reaches the compositor in one commit,
     * and the composition on it keeps running and keeps its state.
     *
     * @return what [LayerShellSurface.apply] rejected, leaving the surface with the settings it already had.
     */
    fun applyConfig(new: SurfaceConfig): EmptyResult<KortexError> {
        check(!hidden) { "a surface off screen sends nothing; show() is what sends the config it comes back with" }
        layer.apply(new).getOrElse { return Err(it) }
        followKeyboard(new)
        return Ok(Unit)
    }

    /**
     * Checks [new] against the placement rules without sending any of it, for a surface that is off screen and
     * stays there; [show] is what sends the config it comes back with.
     *
     * @return what [LayerShellSurface.requirePlaceable] rejects [new] for.
     */
    fun requirePlaceable(new: SurfaceConfig): EmptyResult<KortexError> = LayerShellSurface.requirePlaceable(new)

    /**
     * Takes the surface off screen and hands back the space it reserved. Its composition keeps running and keeps
     * its state, and [logicalSize] keeps the value it had.
     */
    fun hide(): EmptyResult<KortexError> {
        hidden = true
        // A compositor draws no unmapped surface, so a frame it owes is one it will never send.
        clock.cancel()
        layer.unmap()
        return Ok(Unit)
    }

    /**
     * Puts the surface back on screen with [config], every request of which is sent again, and draws the frame
     * that gives the compositor a buffer to map it from.
     *
     * @return what [LayerShellSurface.resend] rejected [config] for, or why the compositor never configured the
     *   surface again, leaving it off screen either way.
     */
    fun show(config: SurfaceConfig): EmptyResult<KortexError> {
        layer.resend(config).getOrElse { return Err(it) }
        layer.remap()
        layer.waitForConfigure().getOrElse { return Err(it) }
        hidden = false
        // The interactivity [config] asks for reached the compositor only now, and with it whatever focus it grants.
        followKeyboard(config)
        return drawAtConfiguredSize()
    }

    /** Draws the attached scene at once, rather than waiting for a frame the compositor has yet to send. */
    internal fun drawNow() {
        renderNow(frameTimeNanos = 0L)
    }

    /** Shows [cursor] on this surface's pointer, from whichever thread content asked for it. */
    internal fun setCursor(cursor: KortexCursor) {
        post { pointerInput?.setCursor(cursor) }
    }

    /** Marks the surface closed, as the compositor closing it would; what content's own handle asks for. */
    internal fun requestClose() {
        post { layer.markClosed() }
    }

    /** Asks the compositor for a frame, from whichever thread noticed the attached scene needs one. */
    internal fun invalidate() {
        // Posted, never run here: Compose also invalidates from inside renderNow, ahead of that frame's own commit.
        post {
            // The frame an off-screen surface asks for is one the compositor never sends; showing it draws instead.
            if (hidden) return@post
            // Answer an invalidation by asking for a frame, never by rendering immediately: the
            // compositor decides when a frame happens.
            clock.request(::renderNow)
            // wl_surface.frame only takes effect on the next commit; without one no callback arrives.
            layer.commit()
        }
    }

    private fun takeKeyboard(): KeyboardInput? {
        val scene = scene ?: return null
        return seat.attachKeyboard(scene.composition, scene::textInput, onInputSerial, onKeyboardFocus)
    }

    private fun followKeyboard(config: SurfaceConfig) {
        when {
            config.keyboard == KeyboardInteractivity.None -> {
                keyboardInput?.release()
                keyboardInput = null
            }
            // The compositor gives an interactive surface focus, which reaches nothing until a keyboard is bound.
            keyboardInput == null -> keyboardInput = takeKeyboard()
        }
    }

    private fun releaseInputs() {
        pointerInput?.release()
        keyboardInput?.release()
        pointerInput = null
        keyboardInput = null
    }

    /**
     * Pumps the connection until [predicate] holds or [timeoutMillis] elapses; exposed so a test can drive a bare
     * surface, where a shell drives its surfaces through [serviceTick].
     *
     * @return whether [predicate] held, or why the surface failed first.
     */
    internal fun pump(
        timeoutMillis: Long,
        predicate: () -> Boolean = { false },
    ): Result<Boolean, KortexError> {
        val deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLI
        while (System.nanoTime() < deadline) {
            loop.runPass()
            drainQueue()
            crash?.let { return Err(it) }
            if (predicate()) return Ok(true)
            // roundtrip, not dispatch: dispatch blocks for an event and would sail past the deadline.
            display.roundtrip()
            reconcile().getOrElse { return Err(it) }
            Thread.sleep(PUMP_INTERVAL_MILLIS)
        }
        loop.runPass()
        serviceTick().getOrElse { return Err(it) }
        return crash?.let { Err(it) } ?: Ok(predicate())
    }

    private fun drainQueue() {
        generateSequence(queue::poll).forEach { it() }
    }

    private fun post(work: () -> Unit) {
        queue += work
        display.wake()
    }

    /**
     * Services this surface for one tick, for a driver running several surfaces on one connection.
     *
     * Unlike [pump], this does not dispatch; the driver owns the connection.
     */
    internal fun serviceTick(): EmptyResult<KortexError> {
        drainQueue()
        return reconcile()
    }

    private fun reconcile(): EmptyResult<KortexError> {
        keyboardInput?.checkRepeat()
        reapRetiredFrames()
        return maybeResize().flatMap { maybeRescale() }
    }

    /** Acts on a later configure, coalesced to whatever size is current by the time this runs. */
    private fun maybeResize(): EmptyResult<KortexError> {
        if (!layer.consumeResize()) return Ok(Unit)
        if (!configuredSizeChanged) return Ok(Unit)
        return resizeTo(layer.logicalWidth, layer.logicalHeight)
    }

    /**
     * Draws at the size the compositor last configured, taking the frames to that size first if they are not.
     *
     * Nothing else will: the configure that answers a [LayerShellSurface.remap] arrives while the surface counts as
     * unconfigured, which is exactly what [LayerShellSurface.consumeResize] does not report.
     */
    private fun drawAtConfiguredSize(): EmptyResult<KortexError> {
        if (configuredSizeChanged) return resizeTo(layer.logicalWidth, layer.logicalHeight)
        renderNow(frameTimeNanos = 0L)
        return Ok(Unit)
    }

    // A configure of zero means "you choose", per the layer-shell protocol; it is never a real dimension.
    private val configuredSizeChanged: Boolean
        get() = layer.logicalWidth != 0 &&
            layer.logicalHeight != 0 &&
            (layer.logicalWidth != logicalWidth || layer.logicalHeight != logicalHeight)

    /** Acts on a later `wl_surface.preferred_buffer_scale`, coalesced to the scale current when this runs. */
    private fun maybeRescale(): EmptyResult<KortexError> {
        val newScale = scaleOverride ?: layer.preferredBufferScale
        if (newScale == bufferScale) return Ok(Unit)
        bufferScale = newScale
        cursorTheme.rescale(bufferScale)
        cursorSurface.setBufferScale(bufferScale)
        pointerInput?.let { pointer ->
            // Wayland keeps reporting logical coordinates, so a stale scale puts every event at the
            // wrong scene position rather than failing outright.
            pointer.scale = bufferScale.toFloat()
            // The pointer skips a shape it believes is already showing, stranding it at the old scale.
            pointer.invalidateCursor()
        }
        // set_buffer_scale is double-buffered; without a commit it waits for a shape change that may never come.
        cursorSurface.commit()
        return resizeTo(logicalWidth, logicalHeight)
    }

    private fun resizeTo(newLogicalWidth: Int, newLogicalHeight: Int): EmptyResult<KortexError> {
        val bufferWidth = newLogicalWidth * bufferScale
        val bufferHeight = newLogicalHeight * bufferScale
        val newFrames = createFrames(shm, bufferWidth, bufferHeight).getOrElse { return Err(it) }

        frames.forEach { frame ->
            // Freeing a buffer the compositor is still scanning out is a use-after-free on its side.
            if (frame.buffer.busy) retiring += frame else frame.close()
        }

        frames = newFrames
        logicalWidth = newLogicalWidth
        logicalHeight = newLogicalHeight
        sizeScene()
        layer.setBufferScale(bufferScale)
        renderNow(frameTimeNanos = 0L)
        return Ok(Unit)
    }

    /** Hands the attached scene the size and scale this surface draws it at, in logical and in buffer pixels. */
    private fun sizeScene() {
        val scene = scene ?: return
        scene.logicalSize = IntSize(logicalWidth, logicalHeight)
        scene.composition.size = IntSize(logicalWidth * bufferScale, logicalHeight * bufferScale)
        scene.composition.density = Density(bufferScale.toFloat())
    }

    private fun reapRetiredFrames() {
        val iterator = retiring.iterator()
        while (iterator.hasNext()) {
            val frame = iterator.next()
            if (frame.buffer.busy) continue
            frame.close()
            iterator.remove()
        }
    }

    private fun renderNow(frameTimeNanos: Long) {
        // An off-screen surface maps again at its next buffer, so nothing is drawn or committed until it shows.
        if (hidden) return
        val scene = scene ?: return
        // A blank buffer would map the surface on a scene that has no content to show yet.
        if (!scene.composed) return
        val frame = frames.firstOrNull { !it.buffer.busy }
        if (frame == null) {
            // Every buffer is still owned by the compositor. Ask for another frame rather than draw
            // into one it is reading.
            clock.request(::renderNow)
            layer.commit()
            return
        }
        // A frame content failed to draw is never shown; the scene keeps the failure for the loop to report.
        scene.composition.render(frame.surface.canvas.asComposeCanvas(), frameTimeNanos).getOrElse { return }
        frame.surface.flushAndSubmit()
        layer.attach(frame.buffer)
        frame.buffer.markAttached()
        layer.commit()
        renders++
    }

    override fun close() {
        if (disposed) return
        disposed = true
        // Before whoever owns the scene closes it: the seat this surface owns keeps delivering, and a leave still in
        // flight would otherwise reach a closed scene, which throws, and the scene would report that as a crash.
        releaseInputs()
        seat.release()
        // Destroyed before the theme, and round-tripped, so the compositor has processed both the released
        // pointer and this surface's destroy, and holds no cursor buffer the theme is about to free.
        cursorSurface.close()
        display.roundtrip()
        cursorTheme.close()
        frames.forEach(Frame::close)
        // No further loop tick will reap these; tearing the surface down makes any lingering scanout moot.
        retiring.forEach(Frame::close)
        // Before layer.close() destroys the wl_surface this callback was requested on.
        clock.close()
        layer.close()
        shm.close()
    }

    private class Frame(val buffer: ShmBuffer, val surface: Surface) {
        fun close() {
            // The Skia surface draws straight into the buffer's pixels, so it closes before they are unmapped.
            surface.close()
            buffer.close()
        }
    }

    companion object {
        /**
         * Builds the Wayland objects for a surface of [config], up to its first configure. Nothing is drawn on it
         * until a [SurfaceScene] is handed to [attach].
         */
        fun create(
            display: WaylandDisplay,
            config: SurfaceConfig,
            // False makes the surface off screen from its first commit on, rather than briefly reserving [config]'s
            // exclusive zone; [show] is what puts it on screen.
            visible: Boolean = true,
            // NULL leaves output selection to the compositor; a bound wl_output targets one directly.
            output: MemorySegment = MemorySegment.NULL,
            // A shell passes the queue its own loop drains; absent, the surface builds one and drains it itself.
            loopQueue: LoopQueue? = null,
            // Handed the serial of every key, keyboard enter and button; the clipboard quotes one to set the selection.
            onInputSerial: (Int) -> Unit = {},
            // Told as the surface's keyboard gains and loses focus, which gates reading another client's text.
            onKeyboardFocus: (keyboard: KeyboardInput, focused: Boolean) -> Unit = { _, _ -> },
        ): Result<KortexSurface, KortexError> {
            // Run newest first by any exit taken before the surface exists, so nothing outlives what it leans on.
            val unwind = mutableListOf<() -> Unit>()
            var handedOver = false
            try {
                val shm = Shm.bind(display).getOrElse { return Err(it) }
                unwind += shm::close
                val layer = LayerShellSurface.create(display, config, visible, output).getOrElse { return Err(it) }
                unwind += layer::close
                layer.waitForConfigure().getOrElse { return Err(it) }
                // waitForConfigure has just round-tripped, so the surface's own preferred_buffer_scale is in.
                val bufferScale = layer.preferredBufferScale
                // Pending state only; it is committed together with the first attach() below.
                layer.setBufferScale(bufferScale)

                // layer.logicalWidth/logicalHeight are surface-local (logical) per configure; the shm buffer
                // and Skia surface must hold the buffer (physical) pixels the compositor expects.
                val frames = createFrames(shm, layer.logicalWidth * bufferScale, layer.logicalHeight * bufferScale)
                    .getOrElse { return Err(it) }
                frames.forEach { frame -> unwind += frame::close }

                val cursorTheme = WlCursorTheme.load(display, bufferScale).getOrElse { return Err(it) }
                unwind += cursorTheme::close
                val cursorSurface = WlCursorSurface.create(display).getOrElse { return Err(it) }
                unwind += cursorSurface::close
                // Pending state only, like the layer surface above; committed together with the first show().
                cursorSurface.setBufferScale(bufferScale)

                // Bound per surface, and never cached: each surface releases the seat it owns when it closes.
                val seat = Seat.bind(display).getOrElse { return Err(it) }
                unwind += seat::release
                // Tested before the surface exists so this exit unwinds too; Seat.bind has already round-tripped.
                if (!seat.hasPointer) {
                    // A dead connection surfaces first as a seat with no devices; prefer the real cause.
                    return display.requireAlive().flatMap { Err(KortexError.MissingSeatDevice(SeatDevice.Pointer)) }
                }
                val surface = KortexSurface(
                    display, layer, shm, bufferScale, frames, FrameClock(layer.surface),
                    loopQueue ?: LoopQueue(display::wake), cursorTheme, cursorSurface, seat,
                    onInputSerial, onKeyboardFocus,
                )
                surface.hidden = !visible
                // From here the surface's own close() is the one owner of every piece above.
                handedOver = true
                return Ok(surface)
            } finally {
                if (!handedOver) unwind.asReversed().forEach { it() }
            }
        }

        // Two buffers, so a frame can be drawn while the compositor still holds the last one.
        private fun createFrames(
            shm: Shm,
            bufferWidth: Int,
            bufferHeight: Int,
        ): Result<List<Frame>, KortexError> {
            val info = ImageInfo(bufferWidth, bufferHeight, COLOR_TYPE, ColorAlphaType.PREMUL)
            val frames = mutableListOf<Frame>()
            repeat(BUFFER_COUNT) {
                val buffer = shm.createBuffer(bufferWidth, bufferHeight).getOrElse { failure ->
                    frames.forEach(Frame::close)
                    return Err(failure)
                }
                frames += Frame(buffer, Surface.makeRasterDirect(info, buffer.pixels.address(), buffer.stride))
            }
            return Ok(frames)
        }

        /** wl_shm's ARGB8888 is a native-endian uint32, which is B,G,R,A in memory on little-endian. */
        private val COLOR_TYPE = ColorType.BGRA_8888
        private const val BUFFER_COUNT = 2
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val PUMP_INTERVAL_MILLIS = 16L
    }
}
