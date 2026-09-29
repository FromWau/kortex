package com.fromwau.kortex.wayland

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.IntSize
import com.fromwau.kern.result.getOrElse
import java.lang.foreign.MemorySegment
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Hands a keyboard keymaps it cannot use, on memfds as a compositor sends them, and reads back what its keys
 * still name. It binds no seat and places no surface, so the compositor's own keyboard is never touched.
 */
class KeymapFailureTest {
    @Test
    fun `a keymap xkb rejects leaves the last good keymap in effect`() {
        withKeyboard { keyboard ->
            keyboard.hand(compiledKeymap(US))
            keyboard.hand(keymapOf(NOT_A_KEYMAP))

            assertTrue(keyboard.hasKeymap, "a keymap xkb rejected threw away the keymap in effect")
            assertEquals(listOf(Key.H), keyboard.tap(KEY_H), "the keymap in effect stopped naming its keys")
        }
    }

    @Test
    fun `a keymap that cannot be mapped is refused without ending the run`() {
        withKeyboard { keyboard ->
            keyboard.hand(compiledKeymap(US))
            keyboard.hand(::unmappableKeymap)

            assertTrue(keyboard.hasKeymap, "a keymap that could not be mapped threw away the keymap in effect")
            assertEquals(listOf(Key.H), keyboard.tap(KEY_H), "the keymap in effect stopped naming its keys")
        }
    }

    @Test
    fun `keys are dropped until a keymap kortex can use arrives`() {
        withKeyboard { keyboard ->
            keyboard.hand(keymapOf(NOT_A_KEYMAP))

            assertFalse(keyboard.hasKeymap, "a keymap xkb rejected counted as one to interpret keys with")
            assertTrue(keyboard.tap(KEY_H).isEmpty(), "a key was named with no keymap to name it from")

            keyboard.hand(compiledKeymap(US))
            assertEquals(listOf(Key.H), keyboard.tap(KEY_H), "the keymap that arrived did not name its keys")
        }
    }

    /** Hands [keyboard] a keymap whose announced size no mapping can honour, as a compositor sending 0 bytes does. */
    private fun unmappableKeymap(keyboard: KeyboardInput) {
        val fd = LibC.memfdCreate("kortex-test-keymap").getOrElse { error -> fail("memfd_create failed: $error") }
        keyboard.onKeymap(NULL, NULL, XKB_V1_FORMAT, fd, EMPTY_KEYMAP)
    }

    /** Runs [block] against a keyboard with no seat behind it, delivering into a focused composition. */
    private fun withKeyboard(block: (Keyboard) -> Unit) {
        val received = CopyOnWriteArrayList<KeyEvent>()

        onScene(IntSize(SIDE, SIDE)) { scene, _, tick ->
            scene.setContent {
                val requester = remember { FocusRequester() }
                Box(
                    Modifier
                        .focusRequester(requester)
                        .onKeyEvent { event ->
                            received.add(event)
                            true
                        }
                        .focusable()
                        .fillMaxSize(),
                )
                LaunchedEffect(Unit) { requester.requestFocus() }
            }
            // A few frames so the LaunchedEffect runs and focus settles.
            repeat(FOCUS_FRAMES) { frame ->
                tick(frame.toLong())
                Thread.sleep(FRAME_MILLIS)
            }

            block(Keyboard(KeyboardInput(scene), received))
        }
    }

    /** A keyboard driven as a compositor drives one, whose delivered keys the test reads back. */
    private class Keyboard(private val input: KeyboardInput, private val received: MutableList<KeyEvent>) {
        private var serial = 0

        val hasKeymap: Boolean get() = input.hasKeymap

        fun hand(keymap: (KeyboardInput) -> Unit) = keymap(input)

        /** The keys a press and release of [code] named to the composition. */
        fun tap(code: Int): List<Key> {
            received.clear()
            input.onKey(NULL, NULL, ++serial, 0, code, PRESSED)
            input.onKey(NULL, NULL, ++serial, 1, code, RELEASED)
            return received.filter { it.type == KeyEventType.KeyDown }.map { it.key }
        }
    }

    private companion object {
        val NULL: MemorySegment = MemorySegment.NULL
        const val SIDE = 64
        const val FOCUS_FRAMES = 6
        const val FRAME_MILLIS = 60L
        const val PRESSED = 1
        const val RELEASED = 0

        // wl_keyboard.keymap_format.xkb_v1
        const val XKB_V1_FORMAT = 1

        /** A size no mmap accepts, whatever the fd behind it holds. */
        const val EMPTY_KEYMAP = 0

        const val US = "us"
        const val NOT_A_KEYMAP = "this is not a keymap"

        // linux/input-event-codes.h
        const val KEY_H = 35
    }
}
