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
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.fromwau.kern.result.getOrElse
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
import kotlin.test.assertNotNull
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
        ) { typist ->
            typist.holdingCtrl { typist.tap(KEY_C) }

            val press = assertNotNull(
                received.firstOrNull { it.type == KeyEventType.KeyDown },
                "Ctrl+C never reached the composition",
            )
            assertTrue(press.isCtrlPressed, "Ctrl+C reached the composition without Ctrl")
            assertEquals(Key.C, press.key, "Ctrl+C reached the composition as the wrong key")
        }
    }

    @Test
    fun `Ctrl+A selects a text field's text, so the next letter replaces it`() {
        val typed = AtomicReference("")
        withKeyboard(content = { focus -> RecordingTextField(focus, typed) }) { typist ->
            typist.tap(KEY_H)
            typist.tap(KEY_I)
            typist.holdingCtrl { typist.tap(KEY_A) }
            typist.tap(KEY_X)

            assertEquals("x", typed.get(), "Ctrl+A did not select the text typed before it")
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

        fun tap(code: Int) {
            keyboard.onKey(NULL, NULL, ++serial, 0, code, PRESSED)
            keyboard.onKey(NULL, NULL, ++serial, 1, code, RELEASED)
            scene.render(surface.canvas.asComposeCanvas(), 100L)
            Thread.sleep(FRAME_MILLIS)
        }

        /** Holds Ctrl as a compositor reports it: as modifier state, not as a key. */
        fun holdingCtrl(block: () -> Unit) {
            keyboard.onModifiers(NULL, NULL, ++serial, CTRL_MASK, 0, 0, 0)
            block()
            keyboard.onModifiers(NULL, NULL, ++serial, 0, 0, 0, 0)
        }
    }

    private companion object {
        val NULL: MemorySegment = MemorySegment.NULL
        const val SIDE = 200
        const val FOCUS_FRAMES = 6
        const val FRAME_MILLIS = 60L
        const val PRESSED = 1
        const val RELEASED = 0

        // Control's bit in wl_keyboard.modifiers, the one KeyboardInput reads.
        const val CTRL_MASK = 1 shl 2

        // linux/input-event-codes.h
        const val KEY_I = 23
        const val KEY_A = 30
        const val KEY_H = 35
        const val KEY_X = 45
        const val KEY_C = 46
    }
}
