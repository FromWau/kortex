package com.fromwau.kortex.wayland

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.map
import com.fromwau.kortex.compose.KortexCursor
import com.fromwau.kortex.compose.KortexScene
import com.fromwau.kortex.compose.KortexTextInput
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT

/**
 * Forwards `wl_pointer` events into a [KortexScene].
 *
 * Wayland reports surface-local (logical) coordinates and the scene draws in buffer (physical) pixels,
 * so everything is multiplied by scale here.
 */
internal class PointerInput(
    private val scene: KortexScene,
    private val scale: Float,
    // Absent when a caller only needs event delivery, e.g. a test with no surface behind it.
    private val cursorTheme: WlCursorTheme? = null,
    private val cursorSurface: WlCursorSurface? = null,
) {
    private var position = Offset.Zero
    private var buttons = PointerButtons()
    private var pointerProxy = MemorySegment.NULL
    private var enterSerial = 0
    private var shownCursor: KortexCursor? = null

    fun onEnter(data: MemorySegment, proxy: MemorySegment, serial: Int, surface: MemorySegment, x: Int, y: Int) {
        // wl_pointer.set_cursor is only valid against the serial of the most recent enter.
        enterSerial = serial
        position = toScenePixels(x, y)
        scene.sendPointerEvent(PointerEventType.Enter, position, timeMillis = 0L, buttons = buttons)
    }

    fun onLeave(data: MemorySegment, proxy: MemorySegment, serial: Int, surface: MemorySegment) {
        scene.sendPointerEvent(PointerEventType.Exit, position, timeMillis = 0L, buttons = buttons)
        // Without this the composition keeps a phantom hover after the pointer is gone.
        scene.cancelPointerInput()
    }

    fun onMotion(data: MemorySegment, proxy: MemorySegment, time: Int, x: Int, y: Int) {
        position = toScenePixels(x, y)
        scene.sendPointerEvent(PointerEventType.Move, position, timeMillis = time.toUInt().toLong(), buttons = buttons)
    }

    fun onButton(data: MemorySegment, proxy: MemorySegment, serial: Int, time: Int, button: Int, state: Int) {
        val pressed = state == BUTTON_PRESSED
        val which = button.toPointerButton() ?: return
        buttons = buttonsWith(which, pressed)
        scene.sendPointerEvent(
            eventType = if (pressed) PointerEventType.Press else PointerEventType.Release,
            position = position,
            timeMillis = time.toUInt().toLong(),
            buttons = buttons,
            button = which,
        )
    }

    fun onAxis(data: MemorySegment, proxy: MemorySegment, time: Int, axis: Int, value: Int) {
        val delta = fixedToFloat(value) * scale
        scene.sendPointerEvent(
            eventType = PointerEventType.Scroll,
            position = position,
            timeMillis = time.toUInt().toLong(),
            scrollDelta = if (axis == AXIS_VERTICAL) Offset(0f, delta) else Offset(delta, 0f),
            buttons = buttons,
        )
    }

    private fun toScenePixels(x: Int, y: Int) = Offset(fixedToFloat(x) * scale, fixedToFloat(y) * scale)

    private fun buttonsWith(button: PointerButton, pressed: Boolean) = PointerButtons(
        isPrimaryPressed = if (button == PointerButton.Primary) pressed else buttons.isPrimaryPressed,
        isSecondaryPressed = if (button == PointerButton.Secondary) pressed else buttons.isSecondaryPressed,
        isTertiaryPressed = if (button == PointerButton.Tertiary) pressed else buttons.isTertiaryPressed,
    )

    private fun Int.toPointerButton(): PointerButton? = when (this) {
        BTN_LEFT -> PointerButton.Primary
        BTN_RIGHT -> PointerButton.Secondary
        BTN_MIDDLE -> PointerButton.Tertiary
        else -> null
    }

    fun install(pointer: MemorySegment) {
        pointerProxy = pointer
        val listener = LibWayland.arena.allocate(ADDRESS.byteSize() * EVENT_COUNT)
        listener.setAtIndex(ADDRESS, ENTER, LibWayland.upcall(this, "onEnter", ENTER_DESCRIPTOR))
        listener.setAtIndex(ADDRESS, LEAVE, LibWayland.upcall(this, "onLeave", LEAVE_DESCRIPTOR))
        listener.setAtIndex(ADDRESS, MOTION, LibWayland.upcall(this, "onMotion", MOTION_DESCRIPTOR))
        listener.setAtIndex(ADDRESS, BUTTON, LibWayland.upcall(this, "onButton", BUTTON_DESCRIPTOR))
        listener.setAtIndex(ADDRESS, AXIS, LibWayland.upcall(this, "onAxis", AXIS_DESCRIPTOR))
        check(LibWayland.proxyAddListener(pointer, listener, MemorySegment.NULL) == 0) {
            "wl_proxy_add_listener rejected the pointer listener"
        }
    }

    /** Forgets which shape is showing, so the next [setCursor] re-sends it at the current scale. */
    fun invalidateCursor() {
        shownCursor = null
    }

    /** Sends `wl_pointer.set_cursor`; must run on the loop thread, like every other request in this file. */
    fun setCursor(cursor: KortexCursor) {
        if (cursor == shownCursor) return
        val surface = cursorSurface ?: return
        val image = cursorTheme?.imageFor(cursor) ?: return
        surface.show(image)
        LibWayland.marshal(
            pointerProxy, WL_POINTER_SET_CURSOR,
            args = listOf(
                WlArg.Num(enterSerial), WlArg.Ptr(surface.proxy),
                WlArg.Num(image.hotspotX), WlArg.Num(image.hotspotY),
            ),
        )
        shownCursor = cursor
    }

    companion object {
        /** wl_fixed_t is signed 24.8 fixed point. */
        fun fixedToFloat(value: Int): Float = value / FIXED_ONE

        private const val FIXED_ONE = 256f
        private const val BUTTON_PRESSED = 1
        private const val AXIS_VERTICAL = 0
        private const val WL_POINTER_SET_CURSOR = 0

        // linux/input-event-codes.h
        private const val BTN_LEFT = 0x110
        private const val BTN_RIGHT = 0x111
        private const val BTN_MIDDLE = 0x112

        // wl_pointer v1 declares exactly these five events; every slot must be filled, because
        // libwayland indexes the struct and calls straight through it.
        private const val EVENT_COUNT = 5L
        private const val ENTER = 0L
        private const val LEAVE = 1L
        private const val MOTION = 2L
        private const val BUTTON = 3L
        private const val AXIS = 4L

        private val ENTER_DESCRIPTOR =
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT)
        private val LEAVE_DESCRIPTOR = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, ADDRESS)
        private val MOTION_DESCRIPTOR =
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT)
        private val BUTTON_DESCRIPTOR =
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT)
        private val AXIS_DESCRIPTOR =
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT)
    }
}

/**
 * Binds `wl_seat` and attaches its devices to the scene.
 *
 * Requesting a device the seat never announced is a protocol error and costs the connection, so
 * [capabilities] is awaited before either is created.
 */
internal class Seat private constructor(
    private val seat: MemorySegment,
    private val capabilities: SeatCapabilities,
) {
    val hasPointer: Boolean get() = capabilities.value and CAPABILITY_POINTER != 0
    val hasKeyboard: Boolean get() = capabilities.value and CAPABILITY_KEYBOARD != 0

    fun attachPointer(
        scene: KortexScene,
        scale: Float,
        cursorTheme: WlCursorTheme? = null,
        cursorSurface: WlCursorSurface? = null,
    ): PointerInput? {
        if (!hasPointer) return null
        val pointer = LibWayland.marshal(
            seat, WL_SEAT_GET_POINTER, LibWayland.pointerInterface,
            LibWayland.proxyGetVersion(seat), listOf(WlArg.Ptr(MemorySegment.NULL)),
        )
        return PointerInput(scene, scale, cursorTheme, cursorSurface).also { it.install(pointer) }
    }

    fun attachKeyboard(scene: KortexScene, textInput: () -> KortexTextInput? = { null }): KeyboardInput? {
        if (!hasKeyboard) return null
        val keyboard = LibWayland.marshal(
            seat, WL_SEAT_GET_KEYBOARD, LibWayland.keyboardInterface,
            LibWayland.proxyGetVersion(seat), listOf(WlArg.Ptr(MemorySegment.NULL)),
        )
        return KeyboardInput(scene, textInput).also { it.install(keyboard) }
    }

    companion object {
        fun bind(display: WaylandDisplay): Result<Seat, KortexError> =
            display.require("wl_seat", LibWayland.seatInterface, WlVersion.SEAT).map { seat ->
                val capabilities = SeatCapabilities()
                val listener = LibWayland.arena.allocate(ADDRESS.byteSize())
                listener.setAtIndex(
                    ADDRESS, 0L, LibWayland.upcall(capabilities, "onCapabilities", CAPABILITIES_DESCRIPTOR),
                )
                check(LibWayland.proxyAddListener(seat, listener, MemorySegment.NULL) == 0) {
                    "wl_proxy_add_listener rejected the seat listener"
                }
                display.roundtrip()
                Seat(seat, capabilities)
            }

        private const val WL_SEAT_GET_POINTER = 0
        private const val CAPABILITY_POINTER = 1
        private const val CAPABILITY_KEYBOARD = 2
        private val CAPABILITIES_DESCRIPTOR = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT)
        private const val WL_SEAT_GET_KEYBOARD = 1
    }
}

internal class SeatCapabilities {
    @Volatile var value: Int = 0

    fun onCapabilities(data: MemorySegment, proxy: MemorySegment, capabilities: Int) {
        value = capabilities
    }
}
