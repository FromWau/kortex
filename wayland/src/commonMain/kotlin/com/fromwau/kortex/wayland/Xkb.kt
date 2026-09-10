package com.fromwau.kortex.wayland

import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT

/** libxkbcommon: turns a keycode into a keysym and a character under the active layout. */
internal object Xkb {
    private val linker = Linker.nativeLinker()
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
    private val keyGetOneSym =
        downcall("xkb_state_key_get_one_sym", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT))
    private val keyGetUtf32 =
        downcall("xkb_state_key_get_utf32", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT))
    private val stateUpdateMask = downcall(
        "xkb_state_update_mask",
        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT),
    )
    private val stateGetKeymap = downcall("xkb_state_get_keymap", FunctionDescriptor.of(ADDRESS, ADDRESS))
    private val stateUnref = downcall("xkb_state_unref", FunctionDescriptor.ofVoid(ADDRESS))
    private val keymapUnref = downcall("xkb_keymap_unref", FunctionDescriptor.ofVoid(ADDRESS))
    private val keymapKeyRepeats =
        downcall("xkb_keymap_key_repeats", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT))

    private val context: MemorySegment by lazy { contextNew.invoke(0) as MemorySegment }

    /** Compiles a keymap the compositor sent as text, returning a state to query. */
    fun stateFromKeymap(keymapText: MemorySegment): MemorySegment? {
        val keymap = keymapNewFromString.invoke(
            context, keymapText, KEYMAP_FORMAT_TEXT_V1, NO_FLAGS,
        ) as MemorySegment
        if (keymap.equals(MemorySegment.NULL)) return null
        val state = stateNew.invoke(keymap) as MemorySegment
        // xkb_state_new takes its own reference on the keymap, so this one is the caller's to drop.
        keymapUnref.invoke(keymap)
        return if (state.equals(MemorySegment.NULL)) null else state
    }

    /** Drops the reference [stateFromKeymap] took, freeing the keymap with it; NULL is a no-op. */
    fun releaseState(state: MemorySegment) {
        stateUnref.invoke(state)
    }

    /** Wayland keycodes are offset by 8 from the evdev codes xkb expects. */
    fun keysym(state: MemorySegment, waylandKey: Int): Int =
        keyGetOneSym.invoke(state, waylandKey + EVDEV_OFFSET) as Int

    /** The character this key produces right now, or 0 for keys that produce none. */
    fun codePoint(state: MemorySegment, waylandKey: Int): Int =
        keyGetUtf32.invoke(state, waylandKey + EVDEV_OFFSET) as Int

    /** Whether the layout marks this key as one that repeats while held; modifiers and locks do not. */
    fun keyRepeats(state: MemorySegment, waylandKey: Int): Boolean {
        // Borrowed, not owned: xkb_state_get_keymap takes no reference and the state outlives the call.
        val keymap = stateGetKeymap.invoke(state) as MemorySegment
        return keymapKeyRepeats.invoke(keymap, waylandKey + EVDEV_OFFSET) as Int != 0
    }

    fun updateMask(state: MemorySegment, depressed: Int, latched: Int, locked: Int, group: Int) {
        stateUpdateMask.invoke(state, depressed, latched, locked, 0, 0, group)
    }

    private const val KEYMAP_FORMAT_TEXT_V1 = 1
    private const val NO_FLAGS = 0
    private const val EVDEV_OFFSET = 8
}
