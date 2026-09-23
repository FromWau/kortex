package com.fromwau.kortex.wayland

import androidx.compose.ui.input.key.Key
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.IError
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT

/** A keymap [Xkb.stateFromKeymap] compiled, with the keyboard state on it; only [Xkb] can read inside it. */
internal sealed interface XkbState

private class CompiledState(val pointer: MemorySegment, val keyNamesFrom: List<Int>) : XkbState

private val XkbState.compiled: CompiledState get() = when (this) { is CompiledState -> this }

/** xkb rejected a keymap it was given as text; no public entry point carries this out of `:wayland`. */
internal data object UnusableKeymap : IError

/** libxkbcommon: turns a keycode into Compose's key for it and the character it types under the active layout. */
internal object Xkb {
    private val linker = Linker.nativeLinker()
    // Global: closing the arena would unload the library out from under every handle bound below.
    private val lookup = SymbolLookup.libraryLookup("libxkbcommon.so.0", LibWayland.arena)

    private fun downcall(name: String, descriptor: FunctionDescriptor) =
        linker.downcallHandle(
            lookup.find(name).orElseThrow { UnsatisfiedLinkError("libxkbcommon exports no $name") },
            descriptor,
        )

    private val contextNew = downcall("xkb_context_new", FunctionDescriptor.of(ADDRESS, JAVA_INT))
    private val keymapNewFromString = downcall(
        "xkb_keymap_new_from_string",
        FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT),
    )
    private val stateNew = downcall("xkb_state_new", FunctionDescriptor.of(ADDRESS, ADDRESS))
    private val keyGetUtf32 =
        downcall("xkb_state_key_get_utf32", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT))
    private val keyGetLayout =
        downcall("xkb_state_key_get_layout", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT))
    private val keymapKeyGetSymsByLevel = downcall(
        "xkb_keymap_key_get_syms_by_level",
        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS),
    )
    private val stateUpdateMask = downcall(
        "xkb_state_update_mask",
        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT),
    )
    private val stateGetKeymap = downcall("xkb_state_get_keymap", FunctionDescriptor.of(ADDRESS, ADDRESS))
    private val stateUnref = downcall("xkb_state_unref", FunctionDescriptor.ofVoid(ADDRESS))
    private val keymapUnref = downcall("xkb_keymap_unref", FunctionDescriptor.ofVoid(ADDRESS))
    private val keymapKeyRepeats =
        downcall("xkb_keymap_key_repeats", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT))
    private val keymapNumLayouts = downcall("xkb_keymap_num_layouts", FunctionDescriptor.of(JAVA_INT, ADDRESS))
    private val keymapNumLayoutsForKey =
        downcall("xkb_keymap_num_layouts_for_key", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT))
    private val keymapMinKeycode = downcall("xkb_keymap_min_keycode", FunctionDescriptor.of(JAVA_INT, ADDRESS))
    private val keymapMaxKeycode = downcall("xkb_keymap_max_keycode", FunctionDescriptor.of(JAVA_INT, ADDRESS))

    private val context: MemorySegment by lazy { contextNew.invoke(0) as MemorySegment }

    /**
     * Compiles a keymap the compositor sent as text.
     *
     * @return a state to query, or why this keymap could not be used.
     */
    fun stateFromKeymap(keymapText: MemorySegment): Result<XkbState, UnusableKeymap> {
        val keymap = keymapNewFromString.invoke(
            context, keymapText, KEYMAP_FORMAT_TEXT_V1, NO_FLAGS,
        ) as MemorySegment
        if (keymap.equals(MemorySegment.NULL)) return Err(UnusableKeymap)
        val state = stateNew.invoke(keymap) as MemorySegment
        val compiled = if (state.equals(MemorySegment.NULL)) null else CompiledState(state, keyNamesFrom(keymap))
        // xkb_state_new takes its own reference on the keymap, so this one is the caller's to drop.
        keymapUnref.invoke(keymap)
        // xkb makes a state from any keymap it compiled, so none means kortex handed it something impossible.
        check(compiled != null) { "xkb_state_new found no state in a compiled keymap" }
        return Ok(compiled)
    }

    /** Drops the reference `xkb_state_new` took on the keymap, freeing it too. */
    fun releaseState(state: XkbState) {
        stateUnref.invoke(state.compiled.pointer)
    }

    /**
     * Compose's key for [waylandKey]: the keysym it has with no modifiers, as AWT names a key rather than the
     * character it types, so Shift+1 is [Key.One]. That keysym is the active layout's, unless the active layout
     * has no Latin letters: then it is the keymap's first Latin layout's, where the key has one there, so Ctrl+C
     * under a Cyrillic layout is [Key.C]. [Key.Unknown] where Compose has no name for that keysym.
     */
    fun key(state: XkbState, waylandKey: Int): Key {
        val compiled = state.compiled
        val keycode = waylandKey + EVDEV_OFFSET
        val layout = keyGetLayout.invoke(compiled.pointer, keycode) as Int
        if (layout == LAYOUT_INVALID) return Key.Unknown
        // Borrowed, not owned: xkb_state_get_keymap takes no reference and the state outlives the call.
        val keymap = stateGetKeymap.invoke(compiled.pointer) as MemorySegment
        val keysym = baseKeysymOrNull(keymap, keycode, compiled.keyNamesFrom[layout])
            ?: baseKeysymOrNull(keymap, keycode, layout)
            ?: return Key.Unknown
        return composeKey(keysym)
    }

    /** The character this key produces right now, or 0 for keys that produce none. */
    fun codePoint(state: XkbState, waylandKey: Int): Int =
        keyGetUtf32.invoke(state.compiled.pointer, waylandKey + EVDEV_OFFSET) as Int

    /** Whether the layout marks this key as one that repeats while held; modifiers and locks do not. */
    fun keyRepeats(state: XkbState, waylandKey: Int): Boolean {
        // Borrowed, not owned: xkb_state_get_keymap takes no reference and the state outlives the call.
        val keymap = stateGetKeymap.invoke(state.compiled.pointer) as MemorySegment
        return keymapKeyRepeats.invoke(keymap, waylandKey + EVDEV_OFFSET) as Int != 0
    }

    fun updateMask(state: XkbState, depressed: Int, latched: Int, locked: Int, group: Int) {
        stateUpdateMask.invoke(state.compiled.pointer, depressed, latched, locked, 0, 0, group)
    }

    /** Indexed by layout: the layout [key] names that layout's keys from. */
    private fun keyNamesFrom(keymap: MemorySegment): List<Int> {
        val keycodes = (keymapMinKeycode.invoke(keymap) as Int)..(keymapMaxKeycode.invoke(keymap) as Int)
        val layouts = 0 until (keymapNumLayouts.invoke(keymap) as Int)
        val latinLayouts = layouts.filter { layout ->
            keycodes.any { keycode -> baseKeysymOrNull(keymap, keycode, layout) in XK_SMALL_A..XK_SMALL_Z }
        }
        val firstLatin = latinLayouts.firstOrNull() ?: return layouts.toList()
        return layouts.map { layout -> if (layout in latinLayouts) layout else firstLatin }
    }

    /** The one keysym [keycode] has at [layout]'s base level; null unless it has [layout] and exactly one there. */
    private fun baseKeysymOrNull(keymap: MemorySegment, keycode: Int, layout: Int): Int? {
        // xkb would bring a layout the key lacks back into range, onto a layout the key was not asked about.
        if (layout >= keymapNumLayoutsForKey.invoke(keymap, keycode) as Int) return null
        return Arena.ofConfined().use { call ->
            val symsOut = call.allocate(ADDRESS)
            val count = keymapKeyGetSymsByLevel.invoke(keymap, keycode, layout, BASE_LEVEL, symsOut) as Int
            // As xkb_state_key_get_one_sym has it: a key that produces several keysyms has no one name.
            if (count != 1) return@use null
            symsOut
                .get(ADDRESS, 0)
                .reinterpret(JAVA_INT.byteSize())
                .get(JAVA_INT, 0)
        }
    }

    // Keypad navigation keysyms stay unnamed: at the base level they are what a keypad digit is, NumLock
    // or not, and a text field would move its caret instead of typing the digit.
    private fun composeKey(keysym: Int): Key = when (keysym) {
        XK_BACKSPACE -> Key.Backspace
        XK_TAB -> Key.Tab
        XK_RETURN, XK_KP_ENTER -> Key.Enter
        XK_ESCAPE -> Key.Escape
        XK_DELETE -> Key.Delete
        XK_HOME -> Key.MoveHome
        XK_END -> Key.MoveEnd
        XK_LEFT -> Key.DirectionLeft
        XK_UP -> Key.DirectionUp
        XK_RIGHT -> Key.DirectionRight
        XK_DOWN -> Key.DirectionDown
        XK_PAGE_UP -> Key.PageUp
        XK_PAGE_DOWN -> Key.PageDown
        XK_INSERT -> Key.Insert
        XK_SPACE -> Key.Spacebar
        XK_APOSTROPHE -> Key.Apostrophe
        XK_PLUS -> Key.Plus
        XK_COMMA -> Key.Comma
        XK_MINUS -> Key.Minus
        XK_PERIOD -> Key.Period
        XK_SLASH -> Key.Slash
        XK_SEMICOLON -> Key.Semicolon
        XK_EQUAL -> Key.Equals
        XK_BRACKETLEFT -> Key.LeftBracket
        XK_BACKSLASH -> Key.Backslash
        XK_BRACKETRIGHT -> Key.RightBracket
        XK_GRAVE -> Key.Grave
        in XK_0..XK_9 -> DIGIT_KEYS[keysym - XK_0]
        in XK_SMALL_A..XK_SMALL_Z -> LETTER_KEYS[keysym - XK_SMALL_A]
        in XK_F1..XK_F12 -> F_KEYS[keysym - XK_F1]
        else -> Key.Unknown
    }

    // In keysym order, which is the order the three ranges above index them in.
    private val DIGIT_KEYS = listOf(
        Key.Zero, Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine,
    )
    private val LETTER_KEYS = listOf(
        Key.A, Key.B, Key.C, Key.D, Key.E, Key.F, Key.G, Key.H, Key.I, Key.J, Key.K, Key.L, Key.M,
        Key.N, Key.O, Key.P, Key.Q, Key.R, Key.S, Key.T, Key.U, Key.V, Key.W, Key.X, Key.Y, Key.Z,
    )
    private val F_KEYS = listOf(
        Key.F1, Key.F2, Key.F3, Key.F4, Key.F5, Key.F6, Key.F7, Key.F8, Key.F9, Key.F10, Key.F11, Key.F12,
    )

    private const val KEYMAP_FORMAT_TEXT_V1 = 1
    private const val NO_FLAGS = 0
    private const val BASE_LEVEL = 0

    // Wayland keycodes are offset by 8 from the evdev codes xkb expects.
    private const val EVDEV_OFFSET = 8

    // XKB_LAYOUT_INVALID, 0xffffffff, read back as a signed Int.
    private const val LAYOUT_INVALID = -1

    // xkbcommon-keysyms.h; nothing outside this object sees a keysym.
    private const val XK_SPACE = 0x20
    private const val XK_APOSTROPHE = 0x27
    private const val XK_PLUS = 0x2B
    private const val XK_COMMA = 0x2C
    private const val XK_MINUS = 0x2D
    private const val XK_PERIOD = 0x2E
    private const val XK_SLASH = 0x2F
    private const val XK_0 = 0x30
    private const val XK_9 = 0x39
    private const val XK_SEMICOLON = 0x3B
    private const val XK_EQUAL = 0x3D
    private const val XK_BRACKETLEFT = 0x5B
    private const val XK_BACKSLASH = 0x5C
    private const val XK_BRACKETRIGHT = 0x5D
    private const val XK_GRAVE = 0x60
    private const val XK_SMALL_A = 0x61
    private const val XK_SMALL_Z = 0x7A
    private const val XK_BACKSPACE = 0xFF08
    private const val XK_TAB = 0xFF09
    private const val XK_RETURN = 0xFF0D
    private const val XK_ESCAPE = 0xFF1B
    private const val XK_HOME = 0xFF50
    private const val XK_LEFT = 0xFF51
    private const val XK_UP = 0xFF52
    private const val XK_RIGHT = 0xFF53
    private const val XK_DOWN = 0xFF54
    private const val XK_PAGE_UP = 0xFF55
    private const val XK_PAGE_DOWN = 0xFF56
    private const val XK_END = 0xFF57
    private const val XK_INSERT = 0xFF63
    private const val XK_KP_ENTER = 0xFF8D
    private const val XK_F1 = 0xFFBE
    private const val XK_F12 = 0xFFC9
    private const val XK_DELETE = 0xFFFF
}
