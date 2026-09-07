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
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.awaitCancellation

@OptIn(InternalComposeUiApi::class)
public class KortexScene(
    size: IntSize,
    density: Density,
    layoutDirection: LayoutDirection = LayoutDirection.Ltr,
    frameContext: CoroutineContext,
    onInvalidate: () -> Unit,
    platform: KortexPlatform = KortexPlatform.None,
) : AutoCloseable {
    private val windowInfo = KortexWindowInfo()

    private val recomposer = FrameRecomposer(frameContext) { onInvalidate() }
    private val scene =
        CanvasLayersComposeScene(
            recomposer, density, layoutDirection, size, KortexPlatformContext(platform, windowInfo),
        )

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

    public fun setContent(content: @Composable () -> Unit): Unit =
        scene.setContent(recomposer.compositionContext, content)

    public fun render(canvas: Canvas, frameTimeNanos: Long) {
        recomposer.performFrame(frameTimeNanos)
        scene.measureAndLayout()
        scene.draw(canvas)
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
    ) {
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

    public fun sendKeyEvent(keyEvent: KeyEvent): Boolean = scene.sendKeyEvent(keyEvent)

    /**
     * Sends a key from its parts, for a host that has a keysym rather than a Compose [KeyEvent].
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
    ): Boolean = scene.sendKeyEvent(
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
    public fun cancelPointerInput(): Unit = scene.cancelPointerInput()

    // Scene first: it holds the recomposer, whose coroutine scope would otherwise outlive it.
    override fun close() {
        scene.close()
        recomposer.close()
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

private fun PointerIcon.toKortexCursor(): KortexCursor = when (this) {
    PointerIcon.Crosshair -> KortexCursor.Crosshair
    PointerIcon.Text -> KortexCursor.Text
    PointerIcon.Hand -> KortexCursor.Hand
    else -> KortexCursor.Default
}
