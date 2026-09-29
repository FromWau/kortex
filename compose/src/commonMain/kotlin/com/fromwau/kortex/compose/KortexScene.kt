package com.fromwau.kortex.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTransferAction
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.drawscope.DrawScope
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
import androidx.compose.ui.platform.PlatformDragAndDropManager
import androidx.compose.ui.platform.PlatformDragAndDropSource
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
import java.awt.Canvas as AwtCanvas
import java.awt.Cursor
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
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
@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
public class KortexScene(
    size: IntSize,
    density: Density,
    layoutDirection: LayoutDirection = LayoutDirection.Ltr,
    frameContext: CoroutineContext,
    private val onInvalidate: () -> Unit,
    platform: KortexPlatform = KortexPlatform.None,
    private val onFailure: (ContentFailure) -> Unit = {},
) : AutoCloseable {
    private val windowInfo = KortexWindowInfo(size)

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

    // Volatile: content can invalidate from another thread while this postpones asking for a frame.
    @Volatile
    private var postponingSceneInvalidations = false

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
            windowInfo.containerSize = value
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
            postponeSceneInvalidations { scene.setContent(recomposer.compositionContext, content) }
        }

    public fun render(canvas: Canvas, frameTimeNanos: Long): EmptyResult<ContentFailure> =
        runContent(ContentFailure::Composition) {
            postponeSceneInvalidations {
                recomposer.performFrame(frameTimeNanos)
                scene.measureAndLayout()
                scene.draw(canvas)
            }
            // Ask again for whatever invalidated while postponingSceneInvalidations was suppressing it.
            if (scene.hasInvalidations()) onInvalidate()
        }

    /**
     * Delivers a pointer event to the composition.
     *
     * @param position where the pointer is, in logical pixels relative to the content; convert physical
     *   pixels yourself, as the scene is never told the output scale.
     * @param timeMillis when the event happened. The origin does not matter, only that it advances.
     * @param scrollDelta how far a [PointerEventType.Scroll] scrolled, in scroll steps: one step is one
     *   wheel detent, and a device that scrolls by distance gives a fraction of one.
     * @param preciseScroll whether that scroll came from a device that scrolls by distance, a trackpad
     *   say, rather than in detents. Content applies a precise scroll at once instead of animating it.
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
        preciseScroll: Boolean = false,
    ): EmptyResult<ContentFailure> = runContent(ContentFailure::PointerInput) {
        scene.sendPointerEvent(
            eventType = eventType,
            position = position,
            scrollDelta = scrollDelta,
            timeMillis = timeMillis,
            type = type,
            buttons = buttons,
            keyboardModifiers = keyboardModifiers,
            nativeEvent = if (preciseScroll) preciseWheelEvent(scrollDelta, timeMillis) else null,
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

    /**
     * Offers content a drag that has arrived over it, which it may take or refuse.
     *
     * @param position where the drag is, in logical pixels relative to the content; convert physical pixels
     *   yourself, as the scene is never told the output scale.
     * @param payload what the drag carries, which content reads as the drag event's native event.
     * @param action what a drop would do to what the drag carries, which content reads off the drag event and
     *   a drop target may refuse on. Send the newest the desktop has settled on: it changes while the drag is
     *   up, usually from the modifier keys the user holds.
     * @return whether anything in this composition takes what the drag carries, wherever in it that is. Send
     *   nothing further of a drag content refused, and end it with neither [sendDragLeave] nor [sendDrop].
     *   Whether a drop at [position] itself would reach content is the separate question [dragOverTarget]
     *   answers, and the one to pass on as the drag moves.
     */
    public fun sendDragEnter(
        position: Offset,
        payload: Any?,
        action: DragAndDropTransferAction = DragAndDropTransferAction.Copy,
    ): Result<Boolean, ContentFailure> =
        runContent(ContentFailure::PointerInput) {
            val event = dragEvent(position, payload, action)
            val taken = dragTarget.acceptDragAndDropTransfer(event)
            if (taken) {
                dragTarget.onStarted(event)
                dragTarget.onEntered(event)
                // Only a move carries a drag onto the content under it, so the arrival is delivered as one too.
                dragTarget.onMoved(event)
            }
            taken
        }

    /**
     * Moves a drag content took to [position], carrying the same [payload] its arrival did and whatever
     * [action] the desktop has settled on by now. Read [dragOverTarget] once it returns for what the drag is
     * over.
     */
    public fun sendDragMove(
        position: Offset,
        payload: Any?,
        action: DragAndDropTransferAction = DragAndDropTransferAction.Copy,
    ): EmptyResult<ContentFailure> =
        runContent(ContentFailure::PointerInput) { dragTarget.onMoved(dragEvent(position, payload, action)) }

    /**
     * Whether a drop where the drag is now would reach content, as of the last [sendDragEnter] or [sendDragMove],
     * and false once content has failed, since a failed scene runs none of it.
     *
     * Content taking a drag says only that something in it wants what the drag carries; whether that something
     * lies under the drag decides whether a drop reaches it, and crossing the content changes the answer. Pass
     * it on after every move, so the desktop shows the user where a drop lands and tells the source, once it has
     * landed, whether anything took what it offered.
     */
    public val dragOverTarget: Boolean get() = failure == null && dragTarget.hasEligibleDropTarget

    /** Ends a drag content took without dropping it, leaving content nothing; [action] as [sendDragMove] takes it. */
    public fun sendDragLeave(
        position: Offset,
        payload: Any?,
        action: DragAndDropTransferAction = DragAndDropTransferAction.Copy,
    ): EmptyResult<ContentFailure> =
        runContent(ContentFailure::PointerInput) {
            val event = dragEvent(position, payload, action)
            dragTarget.onExited(event)
            dragTarget.onEnded(event)
        }

    /**
     * Drops on content a drag it took, at [position] and carrying [payload], and ends the drag.
     *
     * @param action what the desktop settled the drop on, which is the last thing it said and what content
     *   acts on: a [DragAndDropTransferAction.Move] is the source's to undo once you report the drop taken.
     * @return whether content took what was dropped.
     */
    public fun sendDrop(
        position: Offset,
        payload: Any?,
        action: DragAndDropTransferAction = DragAndDropTransferAction.Copy,
    ): Result<Boolean, ContentFailure> =
        runContent(ContentFailure::PointerInput) {
            val event = dragEvent(position, payload, action)
            val dropped = dragTarget.onDrop(event)
            dragTarget.onEnded(event)
            dropped
        }

    private var closed = false

    /**
     * Disposes the composition, even a failed one; content's own cleanup failing becomes [failure].
     * Safe to call more than once.
     */
    override fun close() {
        if (closed) return
        closed = true
        try {
            scene.close()
        } catch (cause: Throwable) {
            record(ContentFailure.Composition(cause))
        }
        recomposer.close()
    }

    private val dragTarget get() = scene.rootDragAndDropNode

    private fun dragEvent(position: Offset, payload: Any?, action: DragAndDropTransferAction) = DragAndDropEvent(
        action = action,
        nativeEvent = payload,
        positionInRootImpl = position,
    )

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
        if (!postponingSceneInvalidations) onInvalidate()
    }

    private inline fun postponeSceneInvalidations(block: () -> Unit) {
        postponingSceneInvalidations = true
        try {
            block()
        } finally {
            postponingSceneInvalidations = false
        }
    }
}

private class KortexWindowInfo(size: IntSize) : WindowInfo {
    override var isWindowFocused: Boolean by mutableStateOf(true)

    // WindowInfo's default is Int.MIN_VALUE on both axes, which drops every popup at the scene's corner.
    override var containerSize: IntSize by mutableStateOf(size)
}

@OptIn(InternalComposeUiApi::class)
private class KortexPlatformContext(
    private val platform: KortexPlatform,
    override val windowInfo: WindowInfo,
) : PlatformContext by PlatformContext.Empty() {
    override val dragAndDropManager: PlatformDragAndDropManager = KortexDragAndDropManager(platform::startDrag)

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

/** Hands content's own request to drag something out to [startDrag], and refuses a payload kortex cannot carry. */
@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
private class KortexDragAndDropManager(
    private val startDrag: (drag: KortexDrag) -> Boolean,
) : PlatformDragAndDropManager {
    // Without this Compose waits for a drag the desktop starts on its own, which no Wayland compositor does.
    override val isRequestDragAndDropTransferRequired: Boolean get() = true

    override fun requestDragAndDropTransfer(source: PlatformDragAndDropSource, offset: Offset) {
        var started = false
        val transfers = object : PlatformDragAndDropSource.StartTransferScope {
            override fun startDragAndDropTransfer(
                transferData: DragAndDropTransferData,
                decorationSize: Size,
                drawDragDecoration: DrawScope.() -> Unit,
            ): Boolean {
                val dragged = transferData.transferable as? KortexDragSource
                val actions = transferData.supportedActions.filterTo(mutableSetOf(), DRAGGABLE_ACTIONS::contains)
                started = dragged != null && actions.isNotEmpty() &&
                    startDrag(KortexDrag(dragged, actions) { action -> completed(transferData, action) })
                // Compose's own channel for a gesture that did not complete, which is what a drag the host
                // would not start is. Content's own code, and it runs inside the pointer event that dragged,
                // so a throw out of it is this scene's failure like any other content throws.
                if (!started) transferData.onTransferCompleted?.invoke(null)
                return started
            }
        }
        with(source) { transfers.startDragAndDropTransfer(offset) { started } }
    }

    // On whichever thread the drag ended, which is the host's loop rather than the one that dragged.
    private fun completed(transferData: DragAndDropTransferData, action: DragAndDropTransferAction?) {
        transferData.onTransferCompleted?.invoke(action)
    }
}

/**
 * The actions a Wayland drag can settle on, which is every one `wl_data_device_manager.dnd_action` names bar
 * `ask`. A drag content offers none of these is refused rather than carried as something it did not ask for:
 * [DragAndDropTransferAction.Link] has no counterpart at all, and a desktop given no action cancels the drag.
 */
@OptIn(ExperimentalComposeUiApi::class)
private val DRAGGABLE_ACTIONS =
    setOf(DragAndDropTransferAction.Copy, DragAndDropTransferAction.Move)

// java.awt.event.MouseEvent refuses a null source, and this one is never shown, drawn into or delivered to.
private val WHEEL_EVENT_SOURCE = AwtCanvas()

/**
 * A scroll of [delta] as the AWT event Compose's desktop scroll config reads precision off: it asks whether the
 * rotation is a whole number of clicks, which only a wheel's detents are. Nothing else on the event is read.
 */
private fun preciseWheelEvent(delta: Offset, timeMillis: Long): MouseWheelEvent = MouseWheelEvent(
    WHEEL_EVENT_SOURCE, MouseEvent.MOUSE_WHEEL, timeMillis, 0,
    0, 0, 0, 0, 0, false,
    // One unit and not a page, which is what the config falls back to when no AWT event carries the scroll.
    MouseWheelEvent.WHEEL_UNIT_SCROLL, 1,
    0, (if (delta.y != 0f) delta.y else delta.x).toDouble(),
)

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
