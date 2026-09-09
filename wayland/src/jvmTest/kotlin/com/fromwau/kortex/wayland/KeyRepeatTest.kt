package com.fromwau.kortex.wayland

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.fromwau.kern.result.getOrElse
import com.fromwau.kortex.compose.KortexPlatform
import com.fromwau.kortex.compose.KortexScene
import com.fromwau.kortex.compose.KortexTextInput
import java.lang.foreign.MemorySegment
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.asCoroutineDispatcher
import org.jetbrains.skia.Surface

/**
 * A client cannot make a real compositor hold a key down, so this drives [KeyboardInput]'s listener
 * entry points, and its [KeyboardInput.checkRepeat] tick seam, directly — the pattern [OutputRescaleTest]
 * uses for `scaleOverride`. A real compositor connection is still needed for the keymap, since xkbcommon
 * has to translate every keycode used here.
 */
class KeyRepeatTest {
    @Test
    fun `a held key repeats only after the delay, at roughly the requested rate, and stops on release`() {
        withKeyboardSession { keyboard, scene, surface, typed ->
            keyboard.onRepeatInfo(NULL, NULL, RATE, DELAY_MILLIS)
            val pressedAt = System.nanoTime()
            keyboard.onKey(NULL, NULL, 1, 0, KEY_A, PRESSED)
            render(scene, surface)
            assertEquals("a", typed.get(), "the initial press did not reach the text field")

            // Comfortably inside the delay: several ticks pass, but the delay itself never does.
            pollFor(DELAY_MILLIS - MARGIN_MILLIS, keyboard, scene, surface)
            assertEquals("a", typed.get(), "a repeat arrived before repeat_info's delay had elapsed")

            // Well past the delay: several intervals' worth of repeats should have landed by now.
            pollFor(EXTRA_MILLIS, keyboard, scene, surface)
            val elapsedNanos = System.nanoTime() - pressedAt
            val expectedRepeats = ((elapsedNanos - DELAY_MILLIS * NANOS_PER_MILLI) / (NANOS_PER_SECOND / RATE))
                .coerceAtLeast(0L)
                .toInt()
            val actualRepeats = typed.get().length - 1
            assertTrue(actualRepeats > 0, "no repeat arrived well past the delay")
            assertTrue(
                abs(actualRepeats - expectedRepeats) <= REPEAT_COUNT_TOLERANCE,
                "expected roughly $expectedRepeats repeats at $RATE/s over ${elapsedNanos / NANOS_PER_MILLI}ms, " +
                    "got $actualRepeats",
            )

            val atRelease = typed.get()
            keyboard.onKey(NULL, NULL, 2, 0, KEY_A, RELEASED)
            render(scene, surface)
            pollFor(EXTRA_MILLIS, keyboard, scene, surface)
            assertEquals(atRelease, typed.get(), "repeats continued after the held key was released")
        }
    }

    @Test
    fun `rate 0 disables repeat, and only the most recently pressed key repeats`() {
        withKeyboardSession { keyboard, scene, surface, typed ->
            keyboard.onRepeatInfo(NULL, NULL, 0, DELAY_MILLIS)
            keyboard.onKey(NULL, NULL, 1, 0, KEY_A, PRESSED)
            render(scene, surface)
            val afterPress = typed.get()

            pollFor(NO_REPEAT_WINDOW_MILLIS, keyboard, scene, surface)
            assertEquals(afterPress, typed.get(), "rate 0 still produced a repeat")

            keyboard.onKey(NULL, NULL, 2, 0, KEY_A, RELEASED)

            // A second key down replaces the first as the repeating key, and releasing a key that is
            // no longer the repeating one must not cancel whichever key replaced it.
            keyboard.onRepeatInfo(NULL, NULL, REPLACE_RATE, REPLACE_DELAY_MILLIS)
            keyboard.onKey(NULL, NULL, 3, 0, KEY_S, PRESSED)
            keyboard.onKey(NULL, NULL, 4, 0, KEY_A, PRESSED)
            render(scene, surface)
            val beforeStaleRelease = typed.get()

            keyboard.onKey(NULL, NULL, 5, 0, KEY_S, RELEASED)
            keyboard.checkRepeat(nowNanos = System.nanoTime() + FAR_FUTURE_NANOS)
            render(scene, surface)

            assertEquals(
                beforeStaleRelease + "a", typed.get(),
                "either A never replaced S as the repeating key, or releasing S cancelled A's repeat anyway",
            )
        }
    }

    @Test
    fun `a modifier held down neither repeats itself nor displaces the key that does`() {
        withKeyboardSession { keyboard, scene, surface, typed ->
            keyboard.onRepeatInfo(NULL, NULL, RATE, DELAY_MILLIS)
            keyboard.onKey(NULL, NULL, 1, 0, KEY_A, PRESSED)
            keyboard.onKey(NULL, NULL, 2, 0, KEY_LEFTSHIFT, PRESSED)
            render(scene, surface)
            val beforeRepeat = typed.get()

            keyboard.checkRepeat(nowNanos = System.nanoTime() + FAR_FUTURE_NANOS)
            render(scene, surface)

            // Shift produces no character, so a repeating Shift shows up as A's repeat going missing.
            assertEquals(
                beforeRepeat + "a", typed.get(),
                "holding Shift either repeated Shift itself or cancelled A's repeat",
            )
        }
    }

    private fun render(scene: KortexScene, surface: Surface) {
        scene.render(surface.canvas.asComposeCanvas(), System.nanoTime())
    }

    /** Mirrors [KortexBar]'s real tick: check for a due repeat, then render, once per [TICK_MILLIS]. */
    private fun pollFor(durationMillis: Long, keyboard: KeyboardInput, scene: KortexScene, surface: Surface) {
        val deadline = System.nanoTime() + durationMillis * NANOS_PER_MILLI
        while (System.nanoTime() < deadline) {
            keyboard.checkRepeat()
            render(scene, surface)
            Thread.sleep(TICK_MILLIS)
        }
    }

    private fun withKeyboardSession(
        block: (keyboard: KeyboardInput, scene: KortexScene, surface: Surface, typed: AtomicReference<String>) -> Unit,
    ) {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }
        val typed = AtomicReference("")
        val open = AtomicReference<KortexTextInput?>(null)
        val platform = object : KortexPlatform {
            override fun onTextInputStarted(session: KortexTextInput) = open.set(session)
            override fun onTextInputStopped() = open.set(null)
        }

        display.use { wayland ->
            val dispatcher = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "kortex-repeat-test").apply { isDaemon = true }
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
                        // The field has to be driven by Compose state, not by the AtomicReference: an
                        // unobservable value never recomposes, so every edit is applied to a stale one.
                        var text by remember { mutableStateOf("") }
                        BasicTextField(
                            value = text,
                            onValueChange = { text = it; typed.set(it) },
                            modifier = Modifier.focusRequester(requester),
                        )
                        LaunchedEffect(Unit) { requester.requestFocus() }
                    }
                    // A few frames so the LaunchedEffect runs and focus settles.
                    repeat(FOCUS_FRAMES) {
                        render(scene, surface)
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

                    block(keyboard, scene, surface, typed)
                }
            }
        }
    }

    private companion object {
        val NULL: MemorySegment = MemorySegment.NULL
        const val SIDE = 200
        const val FOCUS_FRAMES = 6
        const val FRAME_MILLIS = 60L
        const val PRESSED = 1
        const val RELEASED = 0

        // linux/input-event-codes.h
        const val KEY_A = 30
        const val KEY_S = 31
        const val KEY_LEFTSHIFT = 42

        const val NANOS_PER_MILLI = 1_000_000L
        const val NANOS_PER_SECOND = 1_000_000_000L

        // Matches KortexBar's own tick cadence (PUMP_INTERVAL_MILLIS / EVENT_LOOP_TIMEOUT_MILLIS).
        const val TICK_MILLIS = 16L

        // Picked so no plausible built-in default (e.g. 25/s after 250ms) could pass this by accident.
        const val RATE = 8
        const val DELAY_MILLIS = 333
        const val MARGIN_MILLIS = 100L
        const val EXTRA_MILLIS = 700L
        const val REPEAT_COUNT_TOLERANCE = 2

        const val NO_REPEAT_WINDOW_MILLIS = 600L
        const val REPLACE_RATE = 5
        const val REPLACE_DELAY_MILLIS = 20
        const val FAR_FUTURE_NANOS = 10_000_000_000L
    }
}
