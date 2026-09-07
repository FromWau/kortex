package com.fromwau.kortex.wayland

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import com.fromwau.kortex.compose.KortexScene
import com.fromwau.kortex.compose.KortexTextInput
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT

/**
 * Forwards `wl_keyboard` events into a [KortexScene], translating keycodes through xkbcommon.
 *
 * The compositor sends the keymap once, as a file descriptor, and only raw keycodes after that.
 */
internal class KeyboardInput(
    private val scene: KortexScene,
    private val textInput: () -> KortexTextInput? = { null },
) {
    private var state: MemorySegment = MemorySegment.NULL

    /** Whether a keymap has arrived; until it does there is no way to interpret a keycode. */
    val hasKeymap: Boolean get() = !state.equals(MemorySegment.NULL)
    private var shift = false
    private var ctrl = false
    private var alt = false
    private var meta = false

    fun onKeymap(data: MemorySegment, proxy: MemorySegment, format: Int, fd: Int, size: Int) {
        try {
            if (format != XKB_V1_FORMAT) return
            // The compositor hands over a read-only fd; map it, compile it, and let the mapping go.
            val text = LibC.mmapPrivateRead(fd, size.toLong())
            state = Xkb.stateFromKeymap(text) ?: MemorySegment.NULL
            LibC.munmap(text, size.toLong())
        } finally {
            LibC.close(fd)
        }
    }

    fun onEnter(
        data: MemorySegment, proxy: MemorySegment, serial: Int, surface: MemorySegment, keys: MemorySegment,
    ) {
        scene.windowFocused = true
    }

    // Without this the composition keeps a text field focused, and its blinking caret commits a frame
    // often enough that the compositor hands the keyboard straight back to this surface.
    fun onLeave(data: MemorySegment, proxy: MemorySegment, serial: Int, surface: MemorySegment) {
        scene.windowFocused = false
    }

    fun onKey(data: MemorySegment, proxy: MemorySegment, serial: Int, time: Int, key: Int, keyState: Int) {
        if (state.equals(MemorySegment.NULL)) return
        val type = if (keyState == KEY_PRESSED) KeyEventType.KeyDown else KeyEventType.KeyUp
        val sym = Xkb.keysym(state, key)
        // Control characters come back from xkb as codepoints below space; a text field must not insert
        // them, and Compose distinguishes them by Key rather than by codepoint.
        val codePoint = Xkb.codePoint(state, key).takeIf { it >= FIRST_PRINTABLE } ?: 0
        val consumed = scene.sendKey(
            key = sym.toComposeKey(codePoint),
            type = type,
            codePoint = codePoint,
            isCtrlPressed = ctrl,
            isMetaPressed = meta,
            isAltPressed = alt,
            isShiftPressed = shift,
        )
        if (consumed || type != KeyEventType.KeyDown) return

        // A focused text field inserts through the input method, not through key events, so a key the
        // composition did not consume has to be turned into an edit on the open session.
        val session = textInput() ?: return
        when {
            sym == XK_BACKSPACE -> session.backspace()
            sym == XK_DELETE -> session.delete()
            codePoint >= FIRST_PRINTABLE -> session.commit(String(Character.toChars(codePoint)))
        }
    }

    fun onModifiers(
        data: MemorySegment, proxy: MemorySegment, serial: Int,
        depressed: Int, latched: Int, locked: Int, group: Int,
    ) {
        if (!state.equals(MemorySegment.NULL)) Xkb.updateMask(state, depressed, latched, locked, group)
        val active = depressed or latched
        shift = active and MOD_SHIFT != 0
        ctrl = active and MOD_CTRL != 0
        alt = active and MOD_ALT != 0
        meta = active and MOD_LOGO != 0
    }

    /**
     * Maps an X11 keysym onto Compose's [Key].
     *
     * Only keys a text field acts on are named; anything printable still types through its codepoint.
     */
    private fun Int.toComposeKey(codePoint: Int): Key = when (this) {
        XK_BACKSPACE -> Key.Backspace
        XK_DELETE -> Key.Delete
        XK_RETURN, XK_KP_ENTER -> Key.Enter
        XK_TAB -> Key.Tab
        XK_ESCAPE -> Key.Escape
        XK_LEFT -> Key.DirectionLeft
        XK_RIGHT -> Key.DirectionRight
        XK_UP -> Key.DirectionUp
        XK_DOWN -> Key.DirectionDown
        XK_HOME -> Key.MoveHome
        XK_END -> Key.MoveEnd
        XK_SPACE -> Key.Spacebar
        else -> if (codePoint >= FIRST_PRINTABLE) Key(codePoint.uppercaseVirtualKey()) else Key.Unknown
    }

    // Compose's desktop Key wraps AWT virtual-key codes, which for letters and digits are the ASCII
    // values of their uppercase form.
    private fun Int.uppercaseVirtualKey(): Long = Character.toUpperCase(this).toLong()

    fun install(keyboard: MemorySegment) {
        val listener = LibWayland.arena.allocate(ADDRESS.byteSize() * EVENT_COUNT)
        listener.setAtIndex(ADDRESS, KEYMAP, LibWayland.upcall(this, "onKeymap", KEYMAP_DESCRIPTOR))
        listener.setAtIndex(ADDRESS, ENTER, LibWayland.upcall(this, "onEnter", ENTER_DESCRIPTOR))
        listener.setAtIndex(ADDRESS, LEAVE, LibWayland.upcall(this, "onLeave", LEAVE_DESCRIPTOR))
        listener.setAtIndex(ADDRESS, KEY, LibWayland.upcall(this, "onKey", KEY_DESCRIPTOR))
        listener.setAtIndex(ADDRESS, MODIFIERS, LibWayland.upcall(this, "onModifiers", MODIFIERS_DESCRIPTOR))
        check(LibWayland.proxyAddListener(keyboard, listener, MemorySegment.NULL) == 0) {
            "wl_proxy_add_listener rejected the keyboard listener"
        }
    }

    private companion object {
        const val KEY_PRESSED = 1
        const val XKB_V1_FORMAT = 1
        const val FIRST_PRINTABLE = 0x20

        // Order of xkb_state's default modifier mask, as sent in wl_keyboard.modifiers.
        const val MOD_SHIFT = 1 shl 0
        const val MOD_CTRL = 1 shl 2
        const val MOD_ALT = 1 shl 3
        const val MOD_LOGO = 1 shl 6

        const val XK_BACKSPACE = 0xFF08
        const val XK_TAB = 0xFF09
        const val XK_RETURN = 0xFF0D
        const val XK_ESCAPE = 0xFF1B
        const val XK_HOME = 0xFF50
        const val XK_LEFT = 0xFF51
        const val XK_UP = 0xFF52
        const val XK_RIGHT = 0xFF53
        const val XK_DOWN = 0xFF54
        const val XK_END = 0xFF57
        const val XK_KP_ENTER = 0xFF8D
        const val XK_DELETE = 0xFFFF
        const val XK_SPACE = 0x020

        const val EVENT_COUNT = 5L
        const val KEYMAP = 0L
        const val ENTER = 1L
        const val LEAVE = 2L
        const val KEY = 3L
        const val MODIFIERS = 4L

        val KEYMAP_DESCRIPTOR: FunctionDescriptor =
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT)
        val ENTER_DESCRIPTOR: FunctionDescriptor =
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS)
        val LEAVE_DESCRIPTOR: FunctionDescriptor =
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, ADDRESS)
        val KEY_DESCRIPTOR: FunctionDescriptor =
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT)
        val MODIFIERS_DESCRIPTOR: FunctionDescriptor =
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT)

    }
}
