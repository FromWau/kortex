package com.fromwau.kortex.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.platform.FrameRecomposer
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.scene.hasInvalidations
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import java.awt.Cursor
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.awaitCancellation

/**
 * A Compose scene a host drives: the host renders it into a canvas of its own and hands it input.
 *
 * @param onInvalidate called when content needs another [render], on whichever thread noticed; hand it to your
 *   own loop rather than rendering from inside it. It is not called for the first frame after [setContent].
 * @param onFailure called with every failure content causes, the first and any after it, cleanup on close
 *   included, on whichever thread content failed.
 */
@OptIn(InternalComposeUiApi::class)
public class KortexScene(
    size: IntSize,
    density: Density,
    layoutDirection: LayoutDirection = LayoutDirection.Ltr,
    frameContext: CoroutineContext,
    private val onInvalidate: () -> Unit,
    platform: KortexPlatform = KortexPlatform.None,
    private val onFailure: (ContentFailure) -> Unit = {},
) : AutoCloseable {
    private val windowInfo = KortexWindowInfo()

    // Atomic because an effect can resume, and fail, on the host dispatcher's thread.
    private val firstFailure = AtomicReference<ContentFailure?>(null)

    // What the scene is running, so a coroutine failing meanwhile is named after it; null between calls.
    @Volatile
    private var running: ((Throwable) -> ContentFailure)? = null

    // Recomposition and effects fail inside coroutines, never out of a call, so they report through here.
    private val coroutineFailures = CoroutineExceptionHandler { _, cause ->
        record((running ?: ContentFailure::Composition)(cause))
    }

    private val recomposer = FrameRecomposer(frameContext + coroutineFailures) { onInvalidate() }

    // Scene phases end by asking for a frame; within setContent and render, that frame is the render under way or the
    // host's first render after setContent. Volatile, as content may invalidate from another thread.
    @Volatile
    private var rendering = false

    private val scene =
        CanvasLayersComposeScene(
            recomposer, density, layoutDirection, size, KortexPlatformContext(platform, windowInfo),
            invalidateLayout = ::onSceneInvalidated,
            invalidateDraw = ::onSceneInvalidated,
        )

    /** The first failure content caused. Once it is set, every call below returns it and runs no more content. */
    public val failure: ContentFailure? get() = firstFailure.get()

    // ComposeScene.size is nullable and this one is not, so it is written through rather than delegated.
    public var size: IntSize = size
        set(value) {
            field = value
            scene.size = value
        }

    /**
     * Whether the host currently holds keyboard focus.
     *
     * Clear it when focus is lost, or a focused text field blinks its caret forever and every blink
     * costs a frame.
     */
    public var windowFocused: Boolean
        get() = windowInfo.isWindowFocused
        set(value) {
            windowInfo.isWindowFocused = value
        }

    public var density: Density
        get() = scene.density
        set(value) {
            scene.density = value
        }

    public var layoutDirection: LayoutDirection
        get() = scene.layoutDirection
        set(value) {
            scene.layoutDirection = value
        }

    public fun setContent(content: @Composable () -> Unit): EmptyResult<ContentFailure> =
        runContent(ContentFailure::Composition) {
            whileRendering { scene.setContent(recomposer.compositionContext, content) }
        }

    public fun render(canvas: Canvas, frameTimeNanos: Long): EmptyResult<ContentFailure> =
        runContent(ContentFailure::Composition) {
            whileRendering {
                recomposer.performFrame(frameTimeNanos)
                scene.measureAndLayout()
                scene.draw(canvas)
            }
            // What content invalidated while this frame drew it has missed the frame.
            if (scene.hasInvalidations()) onInvalidate()
        }

    /**
     * Delivers a pointer event to the composition.
     *
     * @param position where the pointer is, in logical pixels relative to the content; convert physical
     *   pixels yourself, as the scene is never told the output scale.
     * @param timeMillis when the event happened. The origin does not matter, only that it advances.
     */
    public fun sendPointerEvent(
        eventType: PointerEventType,
        position: Offset,
        timeMillis: Long,
        scrollDelta: Offset = Offset.Zero,
        type: PointerType = PointerType.Mouse,
        buttons: PointerButtons? = null,
        keyboardModifiers: PointerKeyboardModifiers? = null,
        button: PointerButton? = null,
    ): EmptyResult<ContentFailure> = runContent(ContentFailure::PointerInput) {
        scene.sendPointerEvent(
            eventType = eventType,
            position = position,
            scrollDelta = scrollDelta,
            timeMillis = timeMillis,
            type = type,
            buttons = buttons,
            keyboardModifiers = keyboardModifiers,
            button = button,
        )
    }

    /** @return whether the composition consumed the key. */
    public fun sendKeyEvent(keyEvent: KeyEvent): Result<Boolean, ContentFailure> =
        runContent(ContentFailure::KeyInput) { scene.sendKeyEvent(keyEvent) }

    /**
     * Sends a key from its parts, for a host that has no Compose [KeyEvent] of its own to hand over.
     *
     * @param codePoint the character the key produces under the current layout and modifiers, and what a
     *   text field inserts; 0 for a key that produces none.
     * @return whether the composition consumed the key.
     */
    public fun sendKey(
        key: Key,
        type: KeyEventType,
        codePoint: Int = 0,
        isCtrlPressed: Boolean = false,
        isMetaPressed: Boolean = false,
        isAltPressed: Boolean = false,
        isShiftPressed: Boolean = false,
    ): Result<Boolean, ContentFailure> = sendKeyEvent(
        KeyEvent(
            key = key,
            type = type,
            codePoint = codePoint,
            isCtrlPressed = isCtrlPressed,
            isMetaPressed = isMetaPressed,
            isAltPressed = isAltPressed,
            isShiftPressed = isShiftPressed,
        ),
    )

    /** Ends the current pointer interaction. Call it when the pointer leaves, or a hover sticks. */
    public fun cancelPointerInput(): EmptyResult<ContentFailure> =
        runContent(ContentFailure::PointerInput) { scene.cancelPointerInput() }

    /** Disposes the composition, even a failed one; content's own cleanup failing becomes [failure]. */
    override fun close() {
        // Scene first: it holds the recomposer, whose coroutine scope would otherwise outlive it.
        try {
            scene.close()
        } catch (cause: Throwable) {
            record(ContentFailure.Composition(cause))
        }
        recomposer.close()
    }

    // A throw from content, out of [call] or out of a coroutine it runs, becomes the scene's failure.
    private inline fun <T> runContent(
        noinline kind: (Throwable) -> ContentFailure,
        call: () -> T,
    ): Result<T, ContentFailure> {
        firstFailure.get()?.let { return Err(it) }
        running = kind
        val value = try {
            call()
        } catch (cause: Throwable) {
            val failure = kind(cause)
            record(failure)
            return Err(failure)
        } finally {
            running = null
        }
        return firstFailure.get()?.let { Err(it) } ?: Ok(value)
    }

    private fun record(failure: ContentFailure) {
        firstFailure.compareAndSet(null, failure)
        onFailure(failure)
    }

    private fun onSceneInvalidated() {
        if (!rendering) onInvalidate()
    }

    private inline fun <T> whileRendering(block: () -> T): T {
        rendering = true
        try {
            return block()
        } finally {
            rendering = false
        }
    }
}

private class KortexWindowInfo : WindowInfo {
    override var isWindowFocused: Boolean by mutableStateOf(true)
}

@OptIn(InternalComposeUiApi::class)
private class KortexPlatformContext(
    private val platform: KortexPlatform,
    override val windowInfo: WindowInfo,
) : PlatformContext by PlatformContext.Empty() {
    override fun setPointerIcon(pointerIcon: PointerIcon) = platform.setCursor(pointerIcon.toKortexCursor())

    override suspend fun startInputMethod(request: PlatformTextInputMethodRequest): Nothing {
        platform.onTextInputStarted(KortexTextInput(request))
        try {
            awaitCancellation()
        } finally {
            platform.onTextInputStopped()
        }
    }
}

// AwtCursor's equals and hashCode go by cursor type, so content's own PointerIcon(Cursor(type)) finds its entry.
private val CURSOR_ICONS: Map<PointerIcon, KortexCursor> = mapOf(
    PointerIcon.Default to KortexCursor.Default,
    PointerIcon.Crosshair to KortexCursor.Crosshair,
    PointerIcon.Text to KortexCursor.Text,
    PointerIcon.Hand to KortexCursor.Hand,
    PointerIcon(Cursor(Cursor.MOVE_CURSOR)) to KortexCursor.Move,
    PointerIcon(Cursor(Cursor.WAIT_CURSOR)) to KortexCursor.Wait,
    PointerIcon(Cursor(Cursor.N_RESIZE_CURSOR)) to KortexCursor.ResizeNorth,
    PointerIcon(Cursor(Cursor.NE_RESIZE_CURSOR)) to KortexCursor.ResizeNorthEast,
    PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)) to KortexCursor.ResizeEast,
    PointerIcon(Cursor(Cursor.SE_RESIZE_CURSOR)) to KortexCursor.ResizeSouthEast,
    PointerIcon(Cursor(Cursor.S_RESIZE_CURSOR)) to KortexCursor.ResizeSouth,
    PointerIcon(Cursor(Cursor.SW_RESIZE_CURSOR)) to KortexCursor.ResizeSouthWest,
    PointerIcon(Cursor(Cursor.W_RESIZE_CURSOR)) to KortexCursor.ResizeWest,
    PointerIcon(Cursor(Cursor.NW_RESIZE_CURSOR)) to KortexCursor.ResizeNorthWest,
)

private fun PointerIcon.toKortexCursor(): KortexCursor = CURSOR_ICONS[this] ?: KortexCursor.Default
