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
import java.lang.foreign.Arena
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
    // Mutable because a surface can move to an output with a different scale; see KortexSurface.maybeRescale.
    var scale: Float,
    // Absent when a caller only needs event delivery, e.g. a test with no surface behind it.
    private val cursorTheme: WlCursorTheme? = null,
    private val cursorSurface: WlCursorSurface? = null,
    // Handed the serial of every button, which the clipboard quotes to set the selection.
    private val onInputSerial: (Int) -> Unit = {},
) {
    private val arena: Arena = Arena.ofShared()

    private var position = Offset.Zero
    private var buttons = PointerButtons()

    /** The bound `wl_pointer`; not private because a test asserts the version it negotiated. */
    var pointerProxy: MemorySegment = MemorySegment.NULL
        private set

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
        onInputSerial(serial)
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

    fun onFrame(data: MemorySegment, proxy: MemorySegment) = Unit

    fun onAxisSource(data: MemorySegment, proxy: MemorySegment, axisSource: Int) = Unit

    fun onAxisStop(data: MemorySegment, proxy: MemorySegment, time: Int, axis: Int) = Unit

    fun onAxisDiscrete(data: MemorySegment, proxy: MemorySegment, axis: Int, discrete: Int) = Unit

    fun onAxisValue120(data: MemorySegment, proxy: MemorySegment, axis: Int, value120: Int) = Unit

    fun onAxisRelativeDirection(data: MemorySegment, proxy: MemorySegment, axis: Int, direction: Int) = Unit

    fun onWarp(data: MemorySegment, proxy: MemorySegment, x: Int, y: Int) = Unit

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
        val listener = arena.allocate(ADDRESS.byteSize() * EVENT_COUNT)
        listener.setAtIndex(ADDRESS, ENTER, LibWayland.upcall(arena, this, "onEnter", ENTER_DESCRIPTOR))
        listener.setAtIndex(ADDRESS, LEAVE, LibWayland.upcall(arena, this, "onLeave", LEAVE_DESCRIPTOR))
        listener.setAtIndex(ADDRESS, MOTION, LibWayland.upcall(arena, this, "onMotion", MOTION_DESCRIPTOR))
        listener.setAtIndex(ADDRESS, BUTTON, LibWayland.upcall(arena, this, "onButton", BUTTON_DESCRIPTOR))
        listener.setAtIndex(ADDRESS, AXIS, LibWayland.upcall(arena, this, "onAxis", AXIS_DESCRIPTOR))
        listener.setAtIndex(ADDRESS, FRAME, LibWayland.upcall(arena, this, "onFrame", FRAME_DESCRIPTOR))
        listener.setAtIndex(
            ADDRESS, AXIS_SOURCE,
            LibWayland.upcall(arena, this, "onAxisSource", AXIS_SOURCE_DESCRIPTOR),
        )
        listener.setAtIndex(ADDRESS, AXIS_STOP, LibWayland.upcall(arena, this, "onAxisStop", AXIS_STOP_DESCRIPTOR))
        listener.setAtIndex(
            ADDRESS, AXIS_DISCRETE,
            LibWayland.upcall(arena, this, "onAxisDiscrete", AXIS_DISCRETE_DESCRIPTOR),
        )
        listener.setAtIndex(
            ADDRESS, AXIS_VALUE120,
            LibWayland.upcall(arena, this, "onAxisValue120", AXIS_VALUE120_DESCRIPTOR),
        )
        listener.setAtIndex(
            ADDRESS, AXIS_RELATIVE_DIRECTION,
            LibWayland.upcall(arena, this, "onAxisRelativeDirection", AXIS_RELATIVE_DIRECTION_DESCRIPTOR),
        )
        listener.setAtIndex(ADDRESS, WARP, LibWayland.upcall(arena, this, "onWarp", WARP_DESCRIPTOR))
        check(LibWayland.proxyAddListener(pointer, listener, MemorySegment.NULL) == 0) {
            "wl_proxy_add_listener rejected the pointer listener"
        }
    }

    /** Gives the pointer back and frees its stubs; nothing here may be used afterwards. */
    fun release() {
        if (pointerProxy.equals(MemorySegment.NULL)) return
        LibWayland.marshalIfSince(pointerProxy, WL_POINTER_RELEASE, WL_POINTER_RELEASE_SINCE)
        LibWayland.proxyDestroy(pointerProxy)
        pointerProxy = MemorySegment.NULL
        // After the destroy, never before: closing the arena frees the code the twelve stubs above are,
        // and libwayland drops the events queued for a destroyed proxy rather than dispatching them.
        arena.close()
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
        private const val WL_POINTER_RELEASE = 1
        private const val WL_POINTER_RELEASE_SINCE = 3

        // linux/input-event-codes.h
        private const val BTN_LEFT = 0x110
        private const val BTN_RIGHT = 0x111
        private const val BTN_MIDDLE = 0x112

        // wl_pointer v11 declares exactly these twelve events; every slot must be filled, because
        // libwayland indexes the struct and calls straight through it.
        private const val EVENT_COUNT = 12L
        private const val ENTER = 0L
        private const val LEAVE = 1L
        private const val MOTION = 2L
        private const val BUTTON = 3L
        private const val AXIS = 4L
        private const val FRAME = 5L
        private const val AXIS_SOURCE = 6L
        private const val AXIS_STOP = 7L
        private const val AXIS_DISCRETE = 8L
        private const val AXIS_VALUE120 = 9L
        private const val AXIS_RELATIVE_DIRECTION = 10L
        private const val WARP = 11L

        private val ENTER_DESCRIPTOR =
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT)
        private val LEAVE_DESCRIPTOR = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, ADDRESS)
        private val MOTION_DESCRIPTOR =
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT)
        private val BUTTON_DESCRIPTOR =
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT)
        private val AXIS_DESCRIPTOR =
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT)
        private val FRAME_DESCRIPTOR = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS)
        private val AXIS_SOURCE_DESCRIPTOR = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT)
        private val AXIS_STOP_DESCRIPTOR = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT)
        private val AXIS_DISCRETE_DESCRIPTOR = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT)
        private val AXIS_VALUE120_DESCRIPTOR = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT)
        private val AXIS_RELATIVE_DIRECTION_DESCRIPTOR =
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT)
        private val WARP_DESCRIPTOR = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT)
    }
}

/**
 * Binds `wl_seat` and attaches its devices to the scene.
 *
 * Requesting a device the seat never announced is a protocol error and costs the connection, so
 * [capabilities] is awaited before either is created.
 */
internal class Seat private constructor(
    /** The bound `wl_seat`, which the clipboard takes its data device for. */
    val proxy: MemorySegment,
    private val capabilities: SeatCapabilities,
) {

    private var released = false
    val hasPointer: Boolean get() = capabilities.value and CAPABILITY_POINTER != 0
    val hasKeyboard: Boolean get() = capabilities.value and CAPABILITY_KEYBOARD != 0

    fun attachPointer(
        scene: KortexScene,
        scale: Float,
        cursorTheme: WlCursorTheme? = null,
        cursorSurface: WlCursorSurface? = null,
        onInputSerial: (Int) -> Unit = {},
    ): PointerInput? {
        if (!hasPointer) return null
        val pointer = LibWayland.marshal(
            proxy, WL_SEAT_GET_POINTER, LibWayland.pointerInterface,
            LibWayland.proxyGetVersion(proxy), listOf(WlArg.Ptr(MemorySegment.NULL)),
        )
        return PointerInput(scene, scale, cursorTheme, cursorSurface, onInputSerial).also { it.install(pointer) }
    }

    fun attachKeyboard(
        scene: KortexScene,
        textInput: () -> KortexTextInput? = { null },
        onInputSerial: (Int) -> Unit = {},
        onKeyboardFocus: (keyboard: KeyboardInput, focused: Boolean) -> Unit = { _, _ -> },
    ): KeyboardInput? {
        if (!hasKeyboard) return null
        val keyboard = LibWayland.marshal(
            proxy, WL_SEAT_GET_KEYBOARD, LibWayland.keyboardInterface,
            LibWayland.proxyGetVersion(proxy), listOf(WlArg.Ptr(MemorySegment.NULL)),
        )
        return KeyboardInput(scene, textInput, onInputSerial, onKeyboardFocus).also { it.install(keyboard) }
    }

    /** Gives the seat back; every device taken from it must already have been released. */
    fun release() {
        if (released) return
        released = true
        LibWayland.marshalIfSince(proxy, WL_SEAT_RELEASE, WL_SEAT_RELEASE_SINCE)
        LibWayland.proxyDestroy(proxy)
        // After the destroy, so no capabilities event can still reach a stub this frees.
        capabilities.close()
    }

    companion object {
        fun bind(display: WaylandDisplay): Result<Seat, KortexError> =
            display.require("wl_seat", LibWayland.seatInterface, WlVersion.SEAT).map { seat ->
                val capabilities = SeatCapabilities().also { it.install(seat) }
                display.roundtrip()
                Seat(seat, capabilities)
            }

        private const val WL_SEAT_GET_POINTER = 0
        private const val CAPABILITY_POINTER = 1
        private const val CAPABILITY_KEYBOARD = 2
        private const val WL_SEAT_GET_KEYBOARD = 1
        private const val WL_SEAT_RELEASE = 3
        private const val WL_SEAT_RELEASE_SINCE = 5
    }
}

internal class SeatCapabilities {
    private val arena: Arena = Arena.ofShared()

    @Volatile var value: Int = 0

    fun onCapabilities(data: MemorySegment, proxy: MemorySegment, capabilities: Int) {
        value = capabilities
    }

    fun onName(data: MemorySegment, proxy: MemorySegment, name: MemorySegment) = Unit

    fun install(seat: MemorySegment) {
        val listener = arena.allocate(ADDRESS.byteSize() * EVENT_COUNT)
        listener.setAtIndex(
            ADDRESS, CAPABILITIES,
            LibWayland.upcall(arena, this, "onCapabilities", CAPABILITIES_DESCRIPTOR),
        )
        listener.setAtIndex(ADDRESS, NAME, LibWayland.upcall(arena, this, "onName", NAME_DESCRIPTOR))
        check(LibWayland.proxyAddListener(seat, listener, MemorySegment.NULL) == 0) {
            "wl_proxy_add_listener rejected the seat listener"
        }
    }

    /** Frees both stubs; only [Seat.release] may call it, and only once the `wl_seat` itself is destroyed. */
    fun close() {
        arena.close()
    }

    private companion object {
        // wl_seat v11 declares exactly these two events; every slot must be filled, because
        // libwayland indexes the struct and calls straight through it.
        const val EVENT_COUNT = 2L
        const val CAPABILITIES = 0L
        const val NAME = 1L

        val CAPABILITIES_DESCRIPTOR: FunctionDescriptor = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT)
        val NAME_DESCRIPTOR: FunctionDescriptor = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS)
    }
}
