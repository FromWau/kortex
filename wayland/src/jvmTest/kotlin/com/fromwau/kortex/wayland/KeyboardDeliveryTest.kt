package com.fromwau.kortex.wayland

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.getOrElse
import com.fromwau.kortex.compose.ContentFailure
import com.fromwau.kortex.compose.KortexPlatform
import com.fromwau.kortex.compose.KortexScene
import com.fromwau.kortex.compose.KortexTextInput
import kotlinx.coroutines.asCoroutineDispatcher
import org.jetbrains.skia.Surface
import java.lang.foreign.MemorySegment
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Types into a real composition using the compositor's own keymap, so the keycode is translated the
 * way it would be for a user with this layout rather than through a table invented for the test.
 */
class KeyboardDeliveryTest {
    @Test
    fun `typing on a wayland keyboard reaches a text field`() {
        val typed = AtomicReference("")
        withKeyboard(content = { focus -> RecordingTextField(focus, typed) }) { typist ->
            typist.tap(KEY_H)
            typist.tap(KEY_I)

            assertEquals("hi", typed.get(), "keys did not reach the text field")
        }
    }

    @Test
    fun `a letter pressed with Ctrl held reaches the composition as that letter's key`() {
        val press = firstKeyDown { typist -> typist.holding(CTRL_MASK) { typist.tap(KEY_C) } }

        assertTrue(press.isCtrlPressed, "Ctrl+C reached the composition without Ctrl")
        assertEquals(Key.C, press.key, "Ctrl+C reached the composition as the wrong key")
    }

    @Test
    fun `a punctuation key pressed with Ctrl held reaches the composition as that key`() {
        val press = firstKeyDown { typist -> typist.holding(CTRL_MASK) { typist.tap(KEY_SLASH) } }

        assertEquals(Key.Slash, press.key, "Ctrl+/ reached the composition as the wrong key")
    }

    @Test
    fun `a digit pressed with Shift held reaches the composition as the digit's key`() {
        val press = firstKeyDown { typist -> typist.holding(SHIFT_MASK) { typist.tap(KEY_1) } }

        assertTrue(press.isShiftPressed, "Shift+1 reached the composition without Shift")
        assertEquals(Key.One, press.key, "Shift+1 reached the composition as the wrong key")
    }

    @Test
    fun `Ctrl+A selects a text field's text, so the next letter replaces it`() {
        val typed = AtomicReference("")
        withKeyboard(content = { focus -> RecordingTextField(focus, typed) }) { typist ->
            typist.tap(KEY_H)
            typist.tap(KEY_I)
            typist.holding(CTRL_MASK) { typist.tap(KEY_A) }
            typist.tap(KEY_X)

            assertEquals("x", typed.get(), "Ctrl+A did not select the text typed before it")
        }
    }

    @Test
    fun `Ctrl+C in a text field copies its selection into the provided clipboard`() {
        val clipboard = FakeTextClipboard()
        val typed = AtomicReference("")
        withKeyboard(
            content = { focus -> ProvideClipboard(clipboard) { RecordingTextField(focus, typed) } },
        ) { typist ->
            typist.tap(KEY_H)
            typist.tap(KEY_I)
            typist.holding(CTRL_MASK) { typist.tap(KEY_A) }
            typist.holding(CTRL_MASK) { typist.tap(KEY_C) }

            assertTrue(awaitUntil { clipboard.setTexts.isNotEmpty() }, "Ctrl+C never reached the provided clipboard")
            assertEquals(listOf("hi"), clipboard.setTexts.toList(), "Ctrl+C copied something other than the selection")
        }
    }

    @Test
    fun `Ctrl+X in a text field moves its selection into the provided clipboard`() {
        val clipboard = FakeTextClipboard()
        val typed = AtomicReference("")
        withKeyboard(
            content = { focus -> ProvideClipboard(clipboard) { RecordingTextField(focus, typed) } },
        ) { typist ->
            typist.tap(KEY_H)
            typist.tap(KEY_I)
            typist.holding(CTRL_MASK) { typist.tap(KEY_A) }
            typist.holding(CTRL_MASK) { typist.tap(KEY_X) }

            assertTrue(awaitUntil { clipboard.setTexts.isNotEmpty() }, "Ctrl+X never reached the provided clipboard")
            assertEquals(listOf("hi"), clipboard.setTexts.toList(), "Ctrl+X cut something other than the selection")
            assertEquals("", typed.get(), "Ctrl+X left the cut text in the field")
        }
    }

    @Test
    fun `Ctrl+V in a text field pastes what the provided clipboard reads`() {
        val clipboard = FakeTextClipboard(read = { Ok(PASTED) })
        val typed = AtomicReference("")
        withKeyboard(
            content = { focus -> ProvideClipboard(clipboard) { RecordingTextField(focus, typed) } },
        ) { typist ->
            typist.tap(KEY_H)
            typist.tap(KEY_I)
            typist.holding(CTRL_MASK) { typist.tap(KEY_V) }

            assertTrue(awaitUntil { typed.get() == "hi$PASTED" }, "Ctrl+V left the field holding '${typed.get()}'")
        }
    }

    @Test
    fun `a paste whose read fails inserts nothing and fails nothing`() {
        val clipboard = FakeTextClipboard(read = { Err(ClipboardError.ReadTimedOut) })
        val typed = AtomicReference("")
        withKeyboard(
            content = { focus -> ProvideClipboard(clipboard) { RecordingTextField(focus, typed) } },
        ) { typist ->
            typist.tap(KEY_H)
            typist.tap(KEY_I)
            typist.holding(CTRL_MASK) { typist.tap(KEY_V) }

            assertTrue(awaitUntil { clipboard.reads.get() > 0 }, "Ctrl+V never asked the provided clipboard for a text")
            // A paste lands after its read returns, so the field is watched for a while rather than checked once.
            assertTrue(holdsFor { typed.get() == "hi" }, "a failed read changed the field to '${typed.get()}'")
            assertNull(typist.failure, "a failed read failed the scene")
        }
    }

    @Test
    fun `an apostrophe types into a text field instead of moving its caret`() {
        val typed = AtomicReference("")
        withKeyboard(content = { focus -> RecordingTextField(focus, typed) }) { typist ->
            typist.tap(KEY_APOSTROPHE)

            assertEquals("'", typed.get(), "the apostrophe did not reach the text field")
        }
    }

    @Test
    fun `Page Up, Page Down, Insert and the F-keys reach the composition as their own Key`() {
        val downs = keyDownsFrom { typist -> NAMED_KEYS.forEach { (code, _) -> typist.tap(code) } }

        NAMED_KEYS.forEachIndexed { index, (code, key) ->
            assertEquals(key, downs.getOrNull(index)?.key, "evdev code $code reached the composition as the wrong key")
        }
    }

    @Test
    fun `Page Down moves a multi-line text field's caret`() {
        val caret = AtomicReference(0)
        withKeyboard(content = { focus -> MultiLineTextField(focus, caret) }) { typist ->
            typist.tap(KEY_PAGEDOWN)

            assertTrue(caret.get() > 0, "Page Down did not move the caret")
        }
    }

    @Test
    fun `a key handler that throws becomes the scene's failure instead of leaving the keyboard`() {
        withKeyboard(
            content = { focus ->
                Box(
                    focus
                        .onKeyEvent { error(KEY_FAILURE) }
                        .focusable()
                        .fillMaxSize(),
                )
            },
        ) { typist ->
            typist.tap(KEY_C)

            val failure = assertIs<ContentFailure.KeyInput>(
                typist.failure,
                "a throwing key handler must fail the scene",
            )
            assertEquals(KEY_FAILURE, failure.cause.message)
        }
    }

    @Composable
    private fun RecordingTextField(focus: Modifier, typed: AtomicReference<String>) {
        // The field has to be driven by Compose state, not by the AtomicReference: an unobservable value
        // never recomposes, so every edit is applied to a stale one.
        var text by remember { mutableStateOf("") }
        BasicTextField(
            value = text,
            onValueChange = {
                text = it
                typed.set(it)
            },
            modifier = focus,
        )
    }

    @Composable
    private fun MultiLineTextField(focus: Modifier, caret: AtomicReference<Int>) {
        var value by remember { mutableStateOf(TextFieldValue(MULTILINE_TEXT)) }
        BasicTextField(
            value = value,
            onValueChange = {
                value = it
                caret.set(it.selection.start)
            },
            modifier = focus,
        )
    }

    /** The key-down events [press] delivers to a focused composition, in delivery order. */
    private fun keyDownsFrom(press: (Typist) -> Unit): List<KeyEvent> {
        val received = CopyOnWriteArrayList<KeyEvent>()
        withKeyboard(
            content = { focus ->
                Box(
                    focus
                        .onKeyEvent { event ->
                            received.add(event)
                            true
                        }
                        .focusable()
                        .fillMaxSize(),
                )
            },
            block = press,
        )
        return received.filter { it.type == KeyEventType.KeyDown }
    }

    /** The first key-down [press] delivers to a focused composition. */
    private fun firstKeyDown(press: (Typist) -> Unit): KeyEvent =
        assertNotNull(keyDownsFrom(press).firstOrNull(), "no key reached the composition")

    /** Polls [condition] until it holds or [AWAIT_MILLIS] pass: a paste finishes on the scene's own thread. */
    private fun awaitUntil(condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + AWAIT_MILLIS * NANOS_PER_MILLI
        while (!condition()) {
            if (System.nanoTime() >= deadline) return false
            Thread.sleep(FRAME_MILLIS)
        }
        return true
    }

    /** Whether [condition] holds throughout [HOLD_MILLIS], polled as [awaitUntil] polls. */
    private fun holdsFor(condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + HOLD_MILLIS * NANOS_PER_MILLI
        while (System.nanoTime() < deadline) {
            if (!condition()) return false
            Thread.sleep(FRAME_MILLIS)
        }
        return condition()
    }

    /** Runs [block] against a keyboard on the compositor's keymap, delivering into [content] once it has focus. */
    private fun withKeyboard(content: @Composable (focus: Modifier) -> Unit, block: (Typist) -> Unit) {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }
        val open = AtomicReference<KortexTextInput?>(null)
        val platform = object : KortexPlatform {
            override fun onTextInputStarted(session: KortexTextInput) = open.set(session)
            override fun onTextInputStopped() = open.set(null)
        }

        display.use { wayland ->
            val dispatcher = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "kortex-key-test").apply { isDaemon = true }
            }.asCoroutineDispatcher()
            val surface = Surface.makeRasterN32Premul(SIDE, SIDE)

            dispatcher.use {
                KortexScene(
                    size = IntSize(SIDE, SIDE),
                    density = Density(1f),
                    layoutDirection = LayoutDirection.Ltr,
                    frameContext = dispatcher,
                    onInvalidate = {},
                    platform = platform,
                ).use { scene ->
                    scene.setContent {
                        val requester = remember { FocusRequester() }
                        content(Modifier.focusRequester(requester))
                        LaunchedEffect(Unit) { requester.requestFocus() }
                    }
                    // A few frames so the LaunchedEffect runs and focus settles.
                    repeat(FOCUS_FRAMES) { frame ->
                        scene.render(surface.canvas.asComposeCanvas(), frame.toLong())
                        Thread.sleep(FRAME_MILLIS)
                    }

                    val seat = Seat.bind(wayland).getOrElse { error -> fail("seat bind failed: $error") }
                    val keyboard = assertNotNull(
                        seat.attachKeyboard(scene, textInput = { open.get() }),
                        "the seat announced no keyboard",
                    )
                    // The compositor sends the keymap as soon as the keyboard exists.
                    wayland.roundtrip()
                    assertTrue(keyboard.hasKeymap, "the compositor never delivered a keymap")

                    block(Typist(keyboard, scene, surface))
                }
            }
        }
    }

    /** Presses keys the way a compositor reports them, rendering after each so the composition catches up. */
    private class Typist(
        private val keyboard: KeyboardInput,
        private val scene: KortexScene,
        private val surface: Surface,
    ) {
        private var serial = 0

        val failure: ContentFailure? get() = scene.failure

        fun tap(code: Int) {
            keyboard.onKey(NULL, NULL, ++serial, 0, code, PRESSED)
            keyboard.onKey(NULL, NULL, ++serial, 1, code, RELEASED)
            scene.render(surface.canvas.asComposeCanvas(), 100L)
            Thread.sleep(FRAME_MILLIS)
        }

        /** Holds [modifiers] as a compositor reports them: as modifier state, not as keys. */
        fun holding(modifiers: Int, block: () -> Unit) {
            keyboard.onModifiers(NULL, NULL, ++serial, modifiers, 0, 0, 0)
            block()
            keyboard.onModifiers(NULL, NULL, ++serial, 0, 0, 0, 0)
        }
    }

    private companion object {
        val NULL: MemorySegment = MemorySegment.NULL
        const val SIDE = 200
        const val FOCUS_FRAMES = 6
        const val FRAME_MILLIS = 60L
        const val AWAIT_MILLIS = 2000L
        const val HOLD_MILLIS = 500L
        const val NANOS_PER_MILLI = 1_000_000L
        const val PASTED = "Grüße"
        const val PRESSED = 1
        const val RELEASED = 0
        const val KEY_FAILURE = "a key handler threw"

        // Shift's and Control's bits in wl_keyboard.modifiers, the ones KeyboardInput reads.
        const val SHIFT_MASK = 1 shl 0
        const val CTRL_MASK = 1 shl 2

        // linux/input-event-codes.h
        const val KEY_1 = 2
        const val KEY_I = 23
        const val KEY_A = 30
        const val KEY_H = 35
        const val KEY_APOSTROPHE = 40
        const val KEY_X = 45
        const val KEY_C = 46
        const val KEY_V = 47
        const val KEY_SLASH = 53
        const val KEY_F1 = 59
        const val KEY_F2 = 60
        const val KEY_F3 = 61
        const val KEY_F4 = 62
        const val KEY_F5 = 63
        const val KEY_F6 = 64
        const val KEY_F7 = 65
        const val KEY_F8 = 66
        const val KEY_F9 = 67
        const val KEY_F10 = 68
        const val KEY_F11 = 87
        const val KEY_F12 = 88
        const val KEY_PAGEUP = 104
        const val KEY_PAGEDOWN = 109
        const val KEY_INSERT = 110

        const val MULTILINE_TEXT = "one\ntwo\nthree\nfour\nfive\nsix\nseven\neight\nnine\nten"

        val NAMED_KEYS = listOf(
            KEY_PAGEUP to Key.PageUp,
            KEY_PAGEDOWN to Key.PageDown,
            KEY_INSERT to Key.Insert,
            KEY_F1 to Key.F1,
            KEY_F2 to Key.F2,
            KEY_F3 to Key.F3,
            KEY_F4 to Key.F4,
            KEY_F5 to Key.F5,
            KEY_F6 to Key.F6,
            KEY_F7 to Key.F7,
            KEY_F8 to Key.F8,
            KEY_F9 to Key.F9,
            KEY_F10 to Key.F10,
            KEY_F11 to Key.F11,
            KEY_F12 to Key.F12,
        )
    }
}
