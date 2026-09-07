package com.fromwau.kortex.wayland

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.getOrElse
import com.fromwau.kortex.compose.KortexCursor
import com.fromwau.kortex.compose.KortexPlatform
import com.fromwau.kortex.compose.KortexScene
import com.fromwau.kortex.compose.KortexTextInput
import java.lang.foreign.MemorySegment
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Surface

// The scene's density is set to the output scale, so 1.dp is exactly 1 logical pixel at every scale.
private fun Dp.toLogicalPx(): Int = value.roundToInt()

/**
 * A Compose composition rendered onto a `zwlr_layer_shell_v1` surface.
 *
 * Frames are paced off `wl_surface.frame` and drawn only when the composition asks for one, so an idle
 * bar costs nothing.
 */
public class KortexBar private constructor(
    private val display: WaylandDisplay,
    private val layer: LayerSurface,
    private val shm: Shm,
    private var bufferScale: Int,
    private var frames: List<Frame>,
    private val scene: KortexScene,
    private val clock: FrameClock,
    private val dispatcher: ExecutorCoroutineDispatcher,
    private val outputs: WlOutput.Handle,
    private val cursorTheme: WlCursorTheme,
    private val cursorSurface: WlCursorSurface,
) : AutoCloseable {

    // Posted by onInvalidate (the frame thread) and drained only on the loop thread, which is the
    // one thread ever allowed to call into libwayland.
    private val queue = ConcurrentLinkedQueue<() -> Unit>()

    // Set once by create() after the seat is bound; null when the seat announced no pointer.
    @Volatile
    private var pointerInput: PointerInput? = null

    private var logicalWidth: Int = layer.logicalWidth
    private var logicalHeight: Int = layer.logicalHeight

    // Frames a resize replaced while the compositor still held them; reaped once release() clears busy.
    private val retiring = mutableListOf<Frame>()

    // A test cannot make a real compositor send wl_output.scale; this stands in for it.
    internal var scaleOverride: Int? = null

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

    /** True once the compositor has sent `zwlr_layer_surface_v1.closed`; this bar must be torn down. */
    internal val closed: Boolean get() = layer.closed

    public fun setContent(content: @Composable () -> Unit) {
        scene.setContent(content)
        renderNow(frameTimeNanos = 0L)
    }

    /** Requests a new size from the compositor; must be called on the loop thread, like every request here. */
    public fun requestSize(width: Dp, height: Dp) {
        layer.setSize(width.toLogicalPx(), height.toLogicalPx())
        layer.commit()
    }

    /** Runs this bar until the connection dies. Blocks, and owns the connection for as long as it does. */
    public fun runEventLoop() {
        while (true) {
            drainQueue()
            display.flush()
            if (display.dispatch(EVENT_LOOP_TIMEOUT_MILLIS) < 0) break
            reconcile()
        }
    }

    /**
     * Pumps the connection until [predicate] holds or [timeoutMillis] elapses.
     *
     * @return whether [predicate] held.
     */
    public fun pump(timeoutMillis: Long, predicate: () -> Boolean = { false }): Boolean {
        val deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLI
        while (System.nanoTime() < deadline) {
            drainQueue()
            if (predicate()) return true
            // roundtrip, not dispatch: dispatch blocks for an event and would sail past the deadline.
            display.roundtrip()
            reconcile()
            Thread.sleep(PUMP_INTERVAL_MILLIS)
        }
        serviceTick()
        return predicate()
    }

    private fun drainQueue() {
        generateSequence(queue::poll).forEach { it() }
    }

    /**
     * Services this bar for one tick, for a driver running several bars on one connection.
     *
     * Unlike [runEventLoop] and [pump], this does not dispatch; the driver owns the connection.
     */
    internal fun serviceTick() {
        drainQueue()
        reconcile()
    }

    private fun reconcile() {
        reapRetiredFrames()
        maybeResize()
        maybeRescale()
    }

    /** Acts on a later configure, coalesced to whatever size is current by the time this runs. */
    private fun maybeResize() {
        if (!layer.consumeResize()) return
        val newWidth = layer.logicalWidth
        val newHeight = layer.logicalHeight
        // Zero means "you choose", per the layer-shell protocol; it is never a real dimension.
        if (newWidth == 0 || newHeight == 0) return
        if (newWidth == logicalWidth && newHeight == logicalHeight) return
        resizeTo(newWidth, newHeight)
    }

    /** Acts on a later `wl_output.scale`, coalesced to whatever scale is current by the time this runs. */
    private fun maybeRescale() {
        val newScale = scaleOverride ?: outputs.scale
        if (newScale == bufferScale) return
        bufferScale = newScale
        cursorTheme.rescale(bufferScale)
        cursorSurface.setBufferScale(bufferScale)
        // The pointer skips a shape it believes is already showing, stranding it at the old scale.
        pointerInput?.invalidateCursor()
        // set_buffer_scale is double-buffered; without a commit it waits for a shape change that may never come.
        cursorSurface.commit()
        resizeTo(logicalWidth, logicalHeight)
    }

    private fun resizeTo(newLogicalWidth: Int, newLogicalHeight: Int) {
        val bufferWidth = newLogicalWidth * bufferScale
        val bufferHeight = newLogicalHeight * bufferScale
        // A resize runs long after create() returned, with no Result channel left to report through.
        val newFrames = createFrames(shm, bufferWidth, bufferHeight)
            .getOrElse { failure -> error("shm buffer allocation failed: $failure") }

        frames.forEach { frame ->
            // Freeing a buffer the compositor is still scanning out is a use-after-free on its side.
            if (frame.buffer.busy) retiring += frame else {
                frame.surface.close()
                frame.buffer.close()
            }
        }

        frames = newFrames
        logicalWidth = newLogicalWidth
        logicalHeight = newLogicalHeight
        scene.size = IntSize(bufferWidth, bufferHeight)
        scene.density = Density(bufferScale.toFloat())
        layer.setBufferScale(bufferScale)
        renderNow(frameTimeNanos = 0L)
    }

    private fun reapRetiredFrames() {
        val iterator = retiring.iterator()
        while (iterator.hasNext()) {
            val frame = iterator.next()
            if (frame.buffer.busy) continue
            frame.surface.close()
            frame.buffer.close()
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
        scene.render(frame.surface.canvas.asComposeCanvas(), frameTimeNanos)
        frame.surface.flushAndSubmit()
        layer.attach(frame.buffer)
        frame.buffer.markAttached()
        layer.commit()
        renders++
    }

    private fun onInvalidate() {
        // Runs on the frame thread, which must never touch libwayland itself, so post the work instead.
        queue += {
            // Answer an invalidation by asking for a frame, never by rendering immediately: the
            // compositor decides when a frame happens.
            clock.request(::renderNow)
            // wl_surface.frame only takes effect on the next commit; without one no callback arrives.
            layer.commit()
        }
    }

    override fun close() {
        scene.close()
        frames.forEach {
            it.surface.close()
            it.buffer.close()
        }
        // No further loop tick will reap these; tearing the surface down makes any lingering scanout moot.
        retiring.forEach {
            it.surface.close()
            it.buffer.close()
        }
        layer.close()
        dispatcher.close()
    }

    private class Frame(val buffer: ShmBuffer, val surface: Surface)

    public companion object {
        public fun create(
            display: WaylandDisplay,
            namespace: String = "kortex",
            height: Dp = 32.dp,
            platform: KortexPlatform = KortexPlatform.None,
            // Set once and never changed: Hyprland does not return the keyboard to the focused window
            // when a layer surface drops its interactivity (hyprwm/Hyprland#8293).
            keyboard: KeyboardInteractivity = KeyboardInteractivity.None,
            // NULL leaves output selection to the compositor; a bound wl_output targets one directly.
            output: MemorySegment = MemorySegment.NULL,
        ): Result<KortexBar, KortexError> {
            // Kept for the bar's lifetime so a later wl_output.scale can be re-read, not just the first.
            val outputs = WlOutput.bind(display)
            val bufferScale = outputs.scale
            val shm = Shm.bind(display).getOrElse { return Err(it) }
            val heightPx = height.toLogicalPx()
            val layer = LayerSurface.create(
                display, namespace = namespace, height = heightPx, keyboard = keyboard, output = output,
            ).getOrElse { return Err(it) }
            if (!layer.waitForConfigure()) {
                // A dead connection surfaces first as an unconfigured surface; prefer the real cause.
                return Err(display.protocolError() ?: KortexError.SurfaceNotConfigured)
            }
            // Pending state only; it is committed together with the first attach() below.
            layer.setBufferScale(bufferScale)

            // layer.logicalWidth/logicalHeight are surface-local (logical) per configure; the shm buffer
            // and Skia surface must hold the buffer (physical) pixels the compositor expects.
            val bufferWidth = layer.logicalWidth * bufferScale
            val bufferHeight = layer.logicalHeight * bufferScale
            val frames = createFrames(shm, bufferWidth, bufferHeight).getOrElse { return Err(it) }

            val dispatcher = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "kortex-frame").apply { isDaemon = true }
            }.asCoroutineDispatcher()

            // The bar tracks the open text-input session itself so a host does not have to; keys the
            // composition does not consume are turned into edits on it.
            val open = AtomicReference<KortexTextInput?>(null)
            lateinit var bar: KortexBar
            val hostPlatform = object : KortexPlatform {
                override fun setCursor(cursor: KortexCursor) {
                    // Compose can call this from the frame thread; every libwayland call must run on the loop thread.
                    bar.queue += { bar.pointerInput?.setCursor(cursor) }
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

            val cursorTheme = WlCursorTheme.load(display, bufferScale).getOrElse { return Err(it) }
            val cursorSurface = WlCursorSurface.create(display).getOrElse { return Err(it) }
            // Pending state only, like the layer surface above; committed together with the first show().
            cursorSurface.setBufferScale(bufferScale)

            val scene = KortexScene(
                size = IntSize(bufferWidth, bufferHeight),
                density = Density(bufferScale.toFloat()),
                frameContext = dispatcher,
                onInvalidate = { bar.onInvalidate() },
                platform = hostPlatform,
            )
            bar = KortexBar(
                display, layer, shm, bufferScale, frames, scene, FrameClock(layer.surface), dispatcher, outputs,
                cursorTheme, cursorSurface,
            )
            val seat = Seat.bind(display).getOrElse { return Err(it) }
            bar.pointerInput = seat.attachPointer(scene, bufferScale.toFloat(), cursorTheme, cursorSurface)
            if (keyboard != KeyboardInteractivity.None) seat.attachKeyboard(scene, { open.get() })
            if (!seat.hasPointer) {
                // A dead connection surfaces first as a seat with no devices; prefer the real cause.
                val missingPointer = KortexError.MissingSeatDevice(SeatDevice.Pointer)
                return Err(display.protocolError() ?: missingPointer)
            }
            display.roundtrip()
            return Ok(bar)
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
                    frames.forEach {
                        it.surface.close()
                        it.buffer.close()
                    }
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
        private const val EVENT_LOOP_TIMEOUT_MILLIS = 16L
    }
}
