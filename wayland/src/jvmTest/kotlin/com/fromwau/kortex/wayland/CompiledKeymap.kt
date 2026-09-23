package com.fromwau.kortex.wayland

import com.fromwau.kern.result.getOrElse
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.JAVA_BYTE
import kotlin.test.fail

/**
 * The keymap for [layouts], such as `us,ru`, ready to hand a [KeyboardInput] as a compositor sends one, so a
 * test types under any layout without touching the one the compositor is running.
 */
internal fun compiledKeymap(layouts: String): (KeyboardInput) -> Unit = keymapOf(Xkbcli.compileKeymap(layouts))

/** [text] ready to hand a [KeyboardInput] as a compositor sends a keymap, whether or not xkb can compile it. */
internal fun keymapOf(text: String): (KeyboardInput) -> Unit {
    val bytes = text.encodeToByteArray()
    // A compositor's size counts the NUL xkb reads the text up to, which a fresh memfd already holds.
    val size = bytes.size + 1
    return { keyboard ->
        val fd = keymapFd(bytes, size.toLong())
        // onKeymap closes the fd, as it closes the compositor's.
        keyboard.onKeymap(MemorySegment.NULL, MemorySegment.NULL, XKB_V1_FORMAT, fd, size)
    }
}

/** A memfd [size] bytes long holding [bytes], as the fd a compositor sends its keymap on is. */
private fun keymapFd(bytes: ByteArray, size: Long): Int {
    val fd = LibC.memfdCreate("kortex-test-keymap").getOrElse { error -> fail("memfd_create failed: $error") }
    LibC.ftruncate(fd, size).getOrElse { error -> fail("sizing the keymap memfd failed: $error") }
    val mapping = LibC.mmapShared(fd, size).getOrElse { error -> fail("mapping the keymap memfd failed: $error") }
    MemorySegment.copy(bytes, 0, mapping, JAVA_BYTE, 0, bytes.size)
    LibC.munmap(mapping, size)
    return fd
}

// wl_keyboard.keymap_format.xkb_v1
private const val XKB_V1_FORMAT = 1
