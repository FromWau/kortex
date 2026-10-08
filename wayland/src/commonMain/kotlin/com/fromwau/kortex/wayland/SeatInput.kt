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
 * so every position is multiplied by scale here. A scroll is not: it is delivered in scroll steps, which
 * are a count rather than a distance.
 */
internal class PointerInput(
    private val scene: KortexScene,
    // Mutable because a surface can move to an output with a different scale; see KortexSurface.maybeRescale.
    var scale: Float,
    // The compositor sends every wl_pointer of this client each event, whichever of its surfaces the pointer is on.
    private val ownSurface: MemorySegment,
    // Absent when a caller only needs event delivery, e.g. a test with no surface behind it.
    private val cursorTheme: WlCursorTheme? = null,
    private val cursorSurface: WlCursorSurface? = null,
    // Handed the serial of every button, which the clipboard quotes to set the selection.
    private val onInputSerial: (Int) -> Unit = {},
    // Handed the serial of every press, which is the implicit grab a drag out of this client has to name.
    private val onPointerGrab: (Int) -> Unit = {},
) {
    private val arena: Arena = Arena.ofShared()

    private var position = Offset.Zero
    private var buttons = PointerButtons()

    // A scroll arrives as an axis value, a source and a value120 in separate events, and only the frame
    // that closes the group says which of them belong together.
    private val verticalScroll = PendingScroll()
    private val horizontalScroll = PendingScroll()
    private var scrollsByDistance = false

    // False until install() sees a pointer new enough: below wl_pointer v5 no frame event ever arrives,
    // and a scroll held back for one would never be delivered at all.
    private var framesEvents = false

    /** The bound `wl_pointer`; not private because a test asserts the version it negotiated. */
    var pointerProxy: MemorySegment = MemorySegment.NULL
        private set

    private var enterSerial = 0

    // Whether the pointer is on ownSurface, from an enter on it until the leave that follows.
    private var entered = false

    private var shownCursor: KortexCursor? = null

    // The newest stamp this pointer has been handed: enter and leave carry none of their own, and a
    // constant there walks the clock backwards between the motions around them, which reads as a jump.
    private var latestTimeMillis = 0L

    fun onEnter(data: MemorySegment, proxy: MemorySegment, serial: Int, surface: MemorySegment, x: Int, y: Int) {
        entered = surface.address() == ownSurface.address()
        if (!entered) return
        // wl_pointer.set_cursor is only valid against the serial of the most recent enter.
        enterSerial = serial
        position = scenePixels(x, y, scale)
        scene.sendPointerEvent(PointerEventType.Enter, position, timeMillis = latestTimeMillis, buttons = buttons)
    }

    fun onLeave(data: MemorySegment, proxy: MemorySegment, serial: Int, surface: MemorySegment) {
        if (!entered) return
        entered = false
        scene.sendPointerEvent(PointerEventType.Exit, position, timeMillis = latestTimeMillis, buttons = buttons)
        // Without this the composition keeps a phantom hover after the pointer is gone.
        scene.cancelPointerInput()
    }

    fun onMotion(data: MemorySegment, proxy: MemorySegment, time: Int, x: Int, y: Int) {
        if (!entered) return
        position = scenePixels(x, y, scale)
        scene.sendPointerEvent(PointerEventType.Move, position, timeMillis = sceneTime(time), buttons = buttons)
    }

    fun onButton(data: MemorySegment, proxy: MemorySegment, serial: Int, time: Int, button: Int, state: Int) {
        if (!entered) return
        onInputSerial(serial)
        val pressed = state == BUTTON_PRESSED
        val which = button.toPointerButton() ?: return
        // After the filter: a button the scene never sees cannot be the one a drag gesture began with.
        if (pressed) onPointerGrab(serial)
        buttons = buttonsWith(which, pressed)
        scene.sendPointerEvent(
            eventType = if (pressed) PointerEventType.Press else PointerEventType.Release,
            position = position,
            timeMillis = sceneTime(time),
            buttons = buttons,
            button = which,
        )
    }

    fun onAxis(data: MemorySegment, proxy: MemorySegment, time: Int, axis: Int, value: Int) {
        if (!entered) return
        val pending = scrollOn(axis)
        // Summed rather than replaced: the XML makes a frame's several axis events on one axis one motion.
        pending.value = (pending.value ?: 0) + value
        pending.timeMillis = sceneTime(time)
        if (!framesEvents) deliverScroll()
    }

    fun onFrame(data: MemorySegment, proxy: MemorySegment) {
        if (entered) deliverScroll()
    }

    fun onAxisSource(data: MemorySegment, proxy: MemorySegment, axisSource: Int) {
        if (!entered) return
        scrollsByDistance = axisSource == AXIS_SOURCE_FINGER || axisSource == AXIS_SOURCE_CONTINUOUS
    }

    /** The wire's `uint` stamp on the scene's timeline, and the newest this pointer has seen from here on. */
    private fun sceneTime(time: Int): Long = time.toUInt().toLong().also { latestTimeMillis = it }

    fun onAxisStop(data: MemorySegment, proxy: MemorySegment, time: Int, axis: Int) = Unit

    // wl_pointer v8 replaced this with axis_value120 and stops sending it, so it fires only below v8.
    fun onAxisDiscrete(data: MemorySegment, proxy: MemorySegment, axis: Int, discrete: Int) = Unit

    fun onAxisValue120(data: MemorySegment, proxy: MemorySegment, axis: Int, value120: Int) {
        if (!entered) return
        val pending = scrollOn(axis)
        pending.value120 = (pending.value120 ?: 0) + value120
    }

    // The physical direction, which content must not follow: obeying it would undo natural scrolling.
    fun onAxisRelativeDirection(data: MemorySegment, proxy: MemorySegment, axis: Int, direction: Int) = Unit

    fun onWarp(data: MemorySegment, proxy: MemorySegment, x: Int, y: Int) = Unit

    private fun scrollOn(axis: Int): PendingScroll = if (axis == AXIS_VERTICAL) verticalScroll else horizontalScroll

    // The two axes go out as two events and are never combined into one diagonal: Compose reduces a scroll to
    // the single axis its angle favours, so two single-axis events move both axes where one diagonal moves one.
    private fun deliverScroll() {
        sendScroll(verticalScroll, vertical = true)
        sendScroll(horizontalScroll, vertical = false)
        scrollsByDistance = false
    }

    /** Delivers what one axis collected over a frame group, if anything, and empties it for the next. */
    private fun sendScroll(pending: PendingScroll, vertical: Boolean) {
        val value = pending.value
        val value120 = pending.value120
        // Emptied before the early return too: a value120 kept back would count towards a later frame's scroll.
        pending.value = null
        pending.value120 = null
        if (value == null) return
        // value120 counts detents, 120 to the step, which is the unit Compose's own host hands content; a
        // device that scrolls by distance has none, and Hyprland converts it at fifteen pixels to the step.
        val steps = if (value120 != null && !scrollsByDistance) {
            value120 / VALUE120_PER_STEP
        } else {
            fixedToFloat(value) / PIXELS_PER_STEP
        }
        scene.sendPointerEvent(
            eventType = PointerEventType.Scroll,
            position = position,
            timeMillis = pending.timeMillis,
            scrollDelta = if (vertical) Offset(0f, steps) else Offset(steps, 0f),
            buttons = buttons,
            preciseScroll = scrollsByDistance,
        )
    }

    /** One axis of a scroll while its frame group builds up; [value] is null where the group carried none. */
    private class PendingScroll {
        var value: Int? = null
        var value120: Int? = null
        var timeMillis = 0L
    }

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
        framesEvents = LibWayland.proxyGetVersion(pointer) >= WL_POINTER_FRAME_SINCE
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
        LibWayland.marshal(pointerProxy, WL_POINTER_RELEASE)
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

        /** A surface-local `wl_fixed_t` position as the buffer pixels a scene drawn at [scale] is laid out in. */
        fun scenePixels(x: Int, y: Int, scale: Float): Offset =
            Offset(fixedToFloat(x) * scale, fixedToFloat(y) * scale)

        private const val FIXED_ONE = 256f
        private const val BUTTON_PRESSED = 1
        private const val AXIS_VERTICAL = 0
        private const val AXIS_SOURCE_FINGER = 1
        private const val AXIS_SOURCE_CONTINUOUS = 2

        /** `axis_value120`'s own unit: "each multiple of 120 representing one logical scroll step". */
        private const val VALUE120_PER_STEP = 120f

        /** What a device that scrolls by distance moves for one step, as Hyprland converts it both ways. */
        private const val PIXELS_PER_STEP = 15f

        private const val WL_POINTER_SET_CURSOR = 0
        private const val WL_POINTER_RELEASE = 1
        private const val WL_POINTER_FRAME_SINCE = 5

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
        surface: MemorySegment,
        cursorTheme: WlCursorTheme? = null,
        cursorSurface: WlCursorSurface? = null,
        onInputSerial: (Int) -> Unit = {},
        onPointerGrab: (Int) -> Unit = {},
    ): PointerInput? {
        if (!hasPointer) return null
        val pointer = LibWayland.marshal(
            proxy, WL_SEAT_GET_POINTER, LibWayland.pointerInterface,
            LibWayland.proxyGetVersion(proxy), listOf(WlArg.Ptr(MemorySegment.NULL)),
        )
        return PointerInput(scene, scale, surface, cursorTheme, cursorSurface, onInputSerial, onPointerGrab)
            .also { it.install(pointer) }
    }

    fun attachKeyboard(
        scene: KortexScene,
        surface: MemorySegment,
        textInput: () -> KortexTextInput? = { null },
        onInputSerial: (Int) -> Unit = {},
        onKeyboardFocus: (keyboard: KeyboardInput, focused: Boolean) -> Unit = { _, _ -> },
    ): KeyboardInput? {
        if (!hasKeyboard) return null
        val keyboard = LibWayland.marshal(
            proxy, WL_SEAT_GET_KEYBOARD, LibWayland.keyboardInterface,
            LibWayland.proxyGetVersion(proxy), listOf(WlArg.Ptr(MemorySegment.NULL)),
        )
        return KeyboardInput(scene, surface, textInput, onInputSerial, onKeyboardFocus).also { it.install(keyboard) }
    }

    /** Gives the seat back; every device taken from it must already have been released. */
    fun release() {
        if (released) return
        released = true
        LibWayland.marshal(proxy, WL_SEAT_RELEASE)
        LibWayland.proxyDestroy(proxy)
        // After the destroy, so no capabilities event can still reach a stub this frees.
        capabilities.close()
    }

    companion object {
        fun bind(display: WaylandDisplay): Result<Seat, KortexError> =
            display.require(WaylandInterface.Seat, LibWayland.seatInterface, WlVersion.SEAT).map { seat ->
                val capabilities = SeatCapabilities().also { it.install(seat) }
                display.roundtrip()
                Seat(seat, capabilities)
            }

        private const val WL_SEAT_GET_POINTER = 0
        private const val CAPABILITY_POINTER = 1
        private const val CAPABILITY_KEYBOARD = 2
        private const val WL_SEAT_GET_KEYBOARD = 1
        private const val WL_SEAT_RELEASE = 3
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
