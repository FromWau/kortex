package com.fromwau.kortex.wayland

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
import kotlin.test.assertNull
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

    @Test
    fun `the loop sleeps until a held key's next repeat is due, and indefinitely while none is`() {
        withKeyboardSession { keyboard, _, _, _ ->
            keyboard.onRepeatInfo(NULL, NULL, RATE, DELAY_MILLIS)
            assertEquals(
                LibC.POLL_INDEFINITELY, loopTimeout(keyboard, System.nanoTime()),
                "the loop would wake for a repeat with no key held",
            )

            val beforePress = System.nanoTime()
            keyboard.onKey(NULL, NULL, 1, 0, KEY_A, PRESSED)
            val afterPress = System.nanoTime()
            val due = assertNotNull(keyboard.nextRepeatDueNanos, "a held key gave the loop no deadline to wake at")
            val pressMillis = Math.ceilDiv(afterPress - beforePress, NANOS_PER_MILLI).toInt()
            val atPress = loopTimeout(keyboard, afterPress)
            assertTrue(
                atPress in DELAY_MILLIS - pressMillis..DELAY_MILLIS,
                "right after the press the loop would sleep ${atPress}ms, not the ${DELAY_MILLIS}ms delay",
            )
            assertEquals(1, loopTimeout(keyboard, due - 1), "the loop would wake before the repeat is due")
            assertEquals(
                0, loopTimeout(keyboard, due + NANOS_PER_MILLI),
                "an overdue repeat would not wake the loop at once",
            )

            keyboard.checkRepeat(nowNanos = due)
            assertEquals(
                MILLIS_PER_SECOND / RATE, loopTimeout(keyboard, due),
                "after a repeat the loop would not wake again one repeat interval later",
            )

            keyboard.onKey(NULL, NULL, 2, 0, KEY_A, RELEASED)
            assertEquals(
                LibC.POLL_INDEFINITELY, loopTimeout(keyboard, System.nanoTime()),
                "the loop would still wake for a released key",
            )

            keyboard.onRepeatInfo(NULL, NULL, 0, DELAY_MILLIS)
            keyboard.onKey(NULL, NULL, 3, 0, KEY_A, PRESSED)
            assertEquals(
                LibC.POLL_INDEFINITELY, loopTimeout(keyboard, System.nanoTime()),
                "at rate 0 the loop would wake for a repeat that never comes",
            )
        }
    }

    /** The timeout the loop's wait hands `poll` while [keyboard]'s repeat is the only deadline it has. */
    private fun loopTimeout(keyboard: KeyboardInput, nowNanos: Long): Int =
        LibC.pollTimeoutMillis(keyboard.nextRepeatDueNanos, nowNanos)

    @Test
    fun `a shell's loop deadline is the earliest key repeat due on any of its surfaces`() {
        withShellKeyboards { shell, first, second ->
            assertNull(shell.nextDeadlineNanos(), "the shell's loop has a deadline with no key held anywhere")

            first.onRepeatInfo(NULL, NULL, RATE, LATE_DELAY_MILLIS)
            first.onKey(NULL, NULL, 1, 0, KEY_A, PRESSED)
            assertEquals(
                first.nextRepeatDueNanos, shell.nextDeadlineNanos(),
                "the shell's loop would not wake for a key held on one of its surfaces",
            )

            second.onRepeatInfo(NULL, NULL, RATE, DELAY_MILLIS)
            second.onKey(NULL, NULL, 2, 0, KEY_S, PRESSED)
            assertEquals(
                second.nextRepeatDueNanos, shell.nextDeadlineNanos(),
                "the shell's loop would sleep past the earliest repeat among its surfaces",
            )

            second.onKey(NULL, NULL, 3, 0, KEY_S, RELEASED)
            first.onKey(NULL, NULL, 4, 0, KEY_A, RELEASED)
            assertNull(shell.nextDeadlineNanos(), "the shell's loop still has a deadline once every key is up")
        }
    }

    /**
     * Runs [block] on an application of two specks, each handed a keyboard bound here rather than one of its own:
     * a keyboard-interactive surface would take the user's focus as it maps.
     */
    private fun withShellKeyboards(block: (shell: KortexShell, first: KeyboardInput, second: KeyboardInput) -> Unit) {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }
        val dispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "kortex-repeat-test").apply { isDaemon = true }
        }.asCoroutineDispatcher()
        val scene = KortexScene(IntSize(SIDE, SIDE), Density(1f), frameContext = dispatcher, onInvalidate = {})
        try {
            val seat = Seat.bind(display).getOrElse { error -> fail("seat bind failed: $error") }
            try {
                val content: @Composable KortexApplicationScope.() -> Unit = {
                    TestSurface(FIRST_NAMESPACE)
                    TestSurface(SECOND_NAMESPACE, anchor = BOTTOM_LEFT)
                }
                val shell = KortexShell.createApplicationOrFail(display, content)
                shell.useOrFail {
                    awaitPlaced(shell, count = 2)
                    val (first, second) = shell.shownSurfaces.map { surface ->
                        assertNotNull(seat.attachKeyboard(scene), "the seat announced no keyboard")
                            .also { surface.keyboardInput = it }
                    }
                    // The compositor sends each new keyboard its keymap, without which no key is understood.
                    display.roundtrip()
                    assertTrue(first.hasKeymap && second.hasKeymap, "the compositor never delivered a keymap")
                    block(shell, first, second)
                }
            } finally {
                // After the shell, whose close released the keyboards taken from this seat.
                seat.release()
            }
        } finally {
            scene.close()
            dispatcher.close()
            display.close()
        }
    }

    private fun render(scene: KortexScene, surface: Surface) {
        scene.render(surface.canvas.asComposeCanvas(), System.nanoTime())
    }

    /** Mirrors [KortexSurface.pump]: check for a due repeat, then render, once per [TICK_MILLIS]. */
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
        const val MILLIS_PER_SECOND = 1_000

        // Matches KortexSurface.pump's own cadence, its PUMP_INTERVAL_MILLIS.
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

        // Held first but due after DELAY_MILLIS's key pressed later, so only the earliest wins.
        const val LATE_DELAY_MILLIS = 1000
        const val FIRST_NAMESPACE = "kortex-repeat-first"
        const val SECOND_NAMESPACE = "kortex-repeat-second"

        val BOTTOM_LEFT = setOf(Edge.Bottom, Edge.Left)
    }
}
