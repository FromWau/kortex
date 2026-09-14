package com.fromwau.kortex.wayland

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
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
import com.fromwau.kern.result.onSuccess
import com.fromwau.kortex.compose.KortexCursor
import com.fromwau.kortex.compose.KortexPlatform
import com.fromwau.kortex.compose.KortexScene
import com.fromwau.kortex.compose.KortexSurfaceHandle
import com.fromwau.kortex.compose.KortexTextInput
import com.fromwau.kortex.compose.LocalKortexSurface
import com.fromwau.kortex.compose.SurfaceState
import java.lang.foreign.MemorySegment
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Surface

/**
 * A Compose composition rendered onto a `zwlr_layer_shell_v1` surface.
 *
 * Frames are paced off `wl_surface.frame` and drawn only when the composition asks for one, so an idle
 * surface costs nothing.
 */
public class KortexSurface private constructor(
    private val namespace: String,
    private val display: WaylandDisplay,
    private val layer: LayerSurface,
    private val shm: Shm,
    private var bufferScale: Int,
    private var frames: List<Frame>,
    private val scene: KortexScene,
    private val clock: FrameClock,
    private val loop: LoopQueue,
    private val surfaceWork: SurfaceWork,
    private val cursorTheme: WlCursorTheme,
    private val cursorSurface: WlCursorSurface,
    private val seat: Seat,
) : AutoCloseable {

    // Filled through post() from any thread and drained only on the loop thread, which is the one thread
    // ever allowed to call into libwayland.
    private val queue = ConcurrentLinkedQueue<() -> Unit>()

    // Set once by create() after the seat is bound; null only once close() has released it.
    @Volatile
    private var pointerInput: PointerInput? = null

    // Null when config.keyboard is None, when the seat announced no keyboard, and once close() has released it.
    // Settable here so a test can hand one to a surface without keyboard interactivity, since Hyprland gives an
    // interactive surface the user's focus as it maps.
    @Volatile
    internal var keyboardInput: KeyboardInput? = null

    private var logicalWidth: Int = layer.logicalWidth
    private var logicalHeight: Int = layer.logicalHeight

    // Snapshot state, not a plain var: a configure must recompose whatever content reads handle.size.
    private val sizeState = mutableStateOf(IntSize(logicalWidth, logicalHeight))

    // Snapshot state, not a plain var: content and the host follow it through composition and snapshotFlow.
    private val lifecycle = mutableStateOf<SurfaceState>(SurfaceState.Running)

    // Held across the teardown's check and its write, so a crash another thread records cannot land between them.
    private val lifecycleLock = Any()

    private val surfaceHandle: KortexSurfaceHandle = object : KortexSurfaceHandle {
        override val size: IntSize get() = sizeState.value

        override val state: SurfaceState get() = lifecycle.value

        override fun close() {
            // markClosed() itself needs no thread confinement, but the hop keeps this on setCursor's
            // pattern and stays correct if closing ever grows a real libwayland call.
            post { layer.markClosed() }
        }
    }

    // Frames a resize replaced while the compositor still held them; reaped once release() clears busy.
    private val retiring = mutableListOf<Frame>()

    // A test cannot make a real compositor send wl_surface.preferred_buffer_scale; this stands in for it.
    internal var scaleOverride: Int? = null

    // A second close() would re-marshal every request below on proxies the first call already freed.
    private var disposed = false

    /** The buffer (physical-pixel) size of the current frames, i.e. the scene and shm buffer size. */
    public val bufferSize: IntSize
        get() = IntSize(frames.first().buffer.width, frames.first().buffer.height)

    /** How many times the compositor has handed a buffer back. */
    internal val releases: Int get() = frames.sumOf { it.buffer.releases }

    /** How many frames [renderNow] has actually drawn and committed; exposed so a test can assert idle. */
    @Volatile
    internal var renders: Int = 0
        private set

    /** The buffer scale currently committed; exposed so a test can assert a rescale took effect. */
    internal val currentBufferScale: Int get() = bufferScale

    /** The composition's current density; exposed so a test can assert a rescale updated it too. */
    internal val density: Density get() = scene.density

    /** True once the compositor has closed this surface, or its content has; either way it must be torn down. */
    internal val closed: Boolean get() = layer.closed

    /** Which side closed this surface, once [closed] is true; null beforehand. */
    internal val closeReason: CloseReason? get() = layer.closeReason

    /** What this surface's content threw, once it has; the scene then runs none of it. */
    internal val crash: KortexError.SurfaceCrashed?
        get() = scene.failure?.let { KortexError.SurfaceCrashed(namespace, it) }

    /** Where this surface is in its life: the one value its handle and its [ActiveSurface] read. */
    internal val state: SurfaceState get() = lifecycle.value

    /** When this surface next needs a loop pass that no Wayland event will announce; null while nothing does. */
    internal val nextDeadlineNanos: Long? get() = keyboardInput?.nextRepeatDueNanos

    /** A test cannot make the compositor close this surface: that needs removing whatever output it chose. */
    internal fun simulateCompositorClose() {
        layer.simulateCompositorClose()
    }

    /** Composes [content] and draws its first frame, failing as [KortexError.SurfaceCrashed] if content throws. */
    public fun setContent(content: @Composable () -> Unit): EmptyResult<KortexError> {
        scene.setContent { CompositionLocalProvider(LocalKortexSurface provides surfaceHandle) { content() } }
            .onSuccess { renderNow(frameTimeNanos = 0L) }
        return crash?.let { Err(it) } ?: Ok(Unit)
    }

    /**
     * Requests a new size from the compositor; must be called on the loop thread, like every request here.
     *
     * @return what [LayerSurface.setSize] rejected, leaving the surface at the size it already had.
     */
    public fun requestSize(width: Dp, height: Dp): EmptyResult<KortexError> =
        layer.setSize(width.toLogicalPx(), height.toLogicalPx()).onSuccess { layer.commit() }

    /**
     * Pumps the connection until [predicate] holds or [timeoutMillis] elapses.
     *
     * @return whether [predicate] held, or why the surface failed first.
     */
    internal fun pump(timeoutMillis: Long, predicate: () -> Boolean = { false }): Result<Boolean, KortexError> {
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

    internal fun drainQueue() {
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
        val newWidth = layer.logicalWidth
        val newHeight = layer.logicalHeight
        // Zero means "you choose", per the layer-shell protocol; it is never a real dimension.
        if (newWidth == 0 || newHeight == 0) return Ok(Unit)
        if (newWidth == logicalWidth && newHeight == logicalHeight) return Ok(Unit)
        return resizeTo(newWidth, newHeight)
    }

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
        sizeState.value = IntSize(newLogicalWidth, newLogicalHeight)
        scene.size = IntSize(bufferWidth, bufferHeight)
        scene.density = Density(bufferScale.toFloat())
        layer.setBufferScale(bufferScale)
        renderNow(frameTimeNanos = 0L)
        return Ok(Unit)
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
        val frame = frames.firstOrNull { !it.buffer.busy }
        if (frame == null) {
            // Every buffer is still owned by the compositor. Ask for another frame rather than draw
            // into one it is reading.
            clock.request(::renderNow)
            layer.commit()
            return
        }
        // A frame content failed to draw is never shown; the scene keeps the failure for the loop to report.
        scene.render(frame.surface.canvas.asComposeCanvas(), frameTimeNanos).getOrElse { return }
        frame.surface.flushAndSubmit()
        layer.attach(frame.buffer)
        frame.buffer.markAttached()
        layer.commit()
        renders++
    }

    private fun onInvalidate() {
        // Posted, never run here: Compose also invalidates from inside renderNow, ahead of that frame's own commit.
        post {
            // Answer an invalidation by asking for a frame, never by rendering immediately: the
            // compositor decides when a frame happens.
            clock.request(::renderNow)
            // wl_surface.frame only takes effect on the next commit; without one no callback arrives.
            layer.commit()
        }
    }

    override fun close() {
        if (disposed) return
        disposed = true
        // Before scene.close(): the seat this surface owns keeps delivering, and a leave still in flight would
        // otherwise reach a closed scene, which throws, and the scene would report its own close as a crash.
        pointerInput?.release()
        keyboardInput?.release()
        pointerInput = null
        keyboardInput = null
        seat.release()
        // Destroyed before the theme, and round-tripped, so the compositor has processed both the released
        // pointer and this surface's destroy, and holds no cursor buffer the theme is about to free.
        cursorSurface.close()
        display.roundtrip()
        cursorTheme.close()
        scene.close()
        // The scene's recomposer leaves Compose's global snapshot observers only as its cancelled run loop resumes.
        // Only this scene's work: a whole-queue drain would run its siblings' work too, and spend the bound on it.
        loop.drain(surfaceWork)
        frames.forEach(Frame::close)
        // No further loop tick will reap these; tearing the surface down makes any lingering scanout moot.
        retiring.forEach(Frame::close)
        // Before layer.close() destroys the wl_surface this callback was requested on.
        clock.close()
        layer.close()
        shm.close()
        // Last, once content's cleanup above has had its chance to fail: a failure recorded by now keeps it Crashed.
        onTornDown()
    }

    // Called with each failure the scene records, from any thread; scene.failure is already the first of them.
    private fun onContentFailure() {
        val first = checkNotNull(scene.failure) { "the scene reported a failure it had not recorded" }
        moveLifecycle { lifecycle.value = SurfaceState.Crashed(first) }
    }

    private fun onTornDown() {
        moveLifecycle { if (scene.failure == null) lifecycle.value = SurfaceState.Closed }
    }

    private inline fun moveLifecycle(write: () -> Unit) {
        synchronized(lifecycleLock, write)
        // The run ends with a crash and a torn-down scene pumps nothing, so no one else would announce this write.
        Snapshot.sendApplyNotifications()
    }

    private class Frame(val buffer: ShmBuffer, val surface: Surface) {
        fun close() {
            // The Skia surface draws straight into the buffer's pixels, so it closes before they are unmapped.
            surface.close()
            buffer.close()
        }
    }

    public companion object {
        internal fun create(
            display: WaylandDisplay,
            config: SurfaceConfig,
            platform: KortexPlatform = KortexPlatform.None,
            // NULL leaves output selection to the compositor; a bound wl_output targets one directly.
            output: MemorySegment = MemorySegment.NULL,
            // A shell passes the queue its own loop drains; absent, the surface builds one and drains it itself.
            loopQueue: LoopQueue? = null,
            // Where every crash of this surface's content goes, the first and any after it, cleanup included.
            onCrash: (KortexError.SurfaceCrashed) -> Unit = {},
            // Handed the serial of every key, keyboard enter and button; the clipboard quotes one to set the selection.
            onInputSerial: (Int) -> Unit = {},
            // Told as the surface's keyboard gains and loses focus, which gates reading another client's text.
            onKeyboardFocus: (keyboard: KeyboardInput, focused: Boolean) -> Unit = { _, _ -> },
        ): Result<KortexSurface, KortexError> {
            // The surface tracks the open text-input session itself so a host does not have to; keys the
            // composition does not consume are turned into edits on it.
            val open = AtomicReference<KortexTextInput?>(null)
            lateinit var surface: KortexSurface
            val hostPlatform = object : KortexPlatform {
                override fun setCursor(cursor: KortexCursor) {
                    surface.post { surface.pointerInput?.setCursor(cursor) }
                    platform.setCursor(cursor)
                }

                override fun onTextInputStarted(session: KortexTextInput) {
                    open.set(session)
                    platform.onTextInputStarted(session)
                }

                override fun onTextInputStopped() {
                    open.set(null)
                    platform.onTextInputStopped()
                }
            }

            // Run newest first by any exit taken before the surface exists, so nothing outlives what it leans on.
            val unwind = mutableListOf<() -> Unit>()
            var handedOver = false
            try {
                val shm = Shm.bind(display).getOrElse { return Err(it) }
                unwind += shm::close
                val layer = LayerSurface.create(
                    display,
                    namespace = config.namespace,
                    height = config.height.toLogicalPx(),
                    width = config.width.toLogicalPx(),
                    layer = config.layer,
                    anchor = config.anchor,
                    exclusiveZone = config.exclusiveZone,
                    margins = config.margins,
                    keyboard = config.keyboard,
                    output = output,
                    exclusiveEdge = config.exclusiveEdge,
                ).getOrElse { return Err(it) }
                unwind += layer::close
                if (!layer.waitForConfigure()) {
                    // A dead connection surfaces first as an unconfigured surface; prefer the real cause.
                    return Err(display.protocolError() ?: KortexError.SurfaceNotConfigured)
                }
                // waitForConfigure has just round-tripped, so the surface's own preferred_buffer_scale is in.
                val bufferScale = layer.preferredBufferScale
                // Pending state only; it is committed together with the first attach() below.
                layer.setBufferScale(bufferScale)

                // layer.logicalWidth/logicalHeight are surface-local (logical) per configure; the shm buffer
                // and Skia surface must hold the buffer (physical) pixels the compositor expects.
                val bufferWidth = layer.logicalWidth * bufferScale
                val bufferHeight = layer.logicalHeight * bufferScale
                val frames = createFrames(shm, bufferWidth, bufferHeight).getOrElse { return Err(it) }
                frames.forEach { frame -> unwind += frame::close }

                val loop = loopQueue ?: LoopQueue(display::wake)
                val surfaceWork = SurfaceWork()
                // Added before the scene so it unwinds after it, running that scene's cancellation and no other work.
                unwind += { loop.drain(surfaceWork) }

                val cursorTheme = WlCursorTheme.load(display, bufferScale).getOrElse { return Err(it) }
                unwind += cursorTheme::close
                val cursorSurface = WlCursorSurface.create(display).getOrElse { return Err(it) }
                unwind += cursorSurface::close
                // Pending state only, like the layer surface above; committed together with the first show().
                cursorSurface.setBufferScale(bufferScale)

                val scene = KortexScene(
                    size = IntSize(bufferWidth, bufferHeight),
                    density = Density(bufferScale.toFloat()),
                    frameContext = loop + surfaceWork,
                    onInvalidate = { surface.onInvalidate() },
                    platform = hostPlatform,
                    onFailure = { failure ->
                        // Before onCrash, so the host reads Crashed by the time the crash reaches it.
                        surface.onContentFailure()
                        onCrash(KortexError.SurfaceCrashed(config.namespace, failure))
                    },
                )
                unwind += scene::close
                // Bound per surface, and never cached: each surface releases the seat it owns when it closes.
                val seat = Seat.bind(display).getOrElse { return Err(it) }
                unwind += seat::release
                // Tested before the surface exists so this exit unwinds too; Seat.bind has already round-tripped.
                if (!seat.hasPointer) {
                    // A dead connection surfaces first as a seat with no devices; prefer the real cause.
                    val missingPointer = KortexError.MissingSeatDevice(SeatDevice.Pointer)
                    return Err(display.protocolError() ?: missingPointer)
                }
                surface = KortexSurface(
                    config.namespace, display, layer, shm, bufferScale, frames, scene, FrameClock(layer.surface),
                    loop, surfaceWork, cursorTheme, cursorSurface, seat,
                )
                // From here the surface's own close() is the one owner of every piece above.
                handedOver = true
                surface.pointerInput =
                    seat.attachPointer(scene, bufferScale.toFloat(), cursorTheme, cursorSurface, onInputSerial)
                if (config.keyboard != KeyboardInteractivity.None) {
                    surface.keyboardInput = seat.attachKeyboard(scene, { open.get() }, onInputSerial, onKeyboardFocus)
                }
                display.roundtrip()
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
