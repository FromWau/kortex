package com.fromwau.kortex.wayland

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.withFrameNanos
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield

/** A real [KortexShell.runEventLoop] on a thread of its own: it sleeps with nothing to do, and wakes for content. */
class EventLoopWakeTest {
    @Test
    fun `an idle application's loop stays asleep`() {
        val content: @Composable KortexApplicationScope.() -> Unit = { Show(TestSurface<Nothing>(IDLE_NAMESPACE)) }

        LoopThread.runApplicationOnDisplay(content) { display, _, _ ->
            assertTrue(
                LoopThread.awaitNamespace(IDLE_NAMESPACE, present = true),
                "hyprctl layers never reported $IDLE_NAMESPACE",
            )
            Thread.sleep(QUIET_MILLIS)

            val before = display.waits
            Thread.sleep(IDLE_WINDOW_MILLIS)
            val waits = display.waits - before
            assertTrue(
                waits <= IDLE_WAIT_LIMIT,
                "a static surface's loop woke $waits times in ${IDLE_WINDOW_MILLIS}ms with nothing to do",
            )
        }
    }

    @Test
    fun `a surface content shows later is placed while the loop sleeps`() {
        val showOpened = mutableStateOf(false)
        val content: @Composable KortexApplicationScope.() -> Unit = {
            Show(
                TestSurface<Nothing>(OPENER_NAMESPACE) {
                    // Once the loop has gone quiet, so nothing but the new Show itself can wake it.
                    LaunchedEffect(Unit) {
                        delay(QUIET_MILLIS)
                        showOpened.value = true
                    }
                },
            )
            if (showOpened.value) Show(TestSurface<Nothing>(OPENED_NAMESPACE, anchor = BOTTOM_LEFT))
        }

        val printed = capturingStdout {
            LoopThread.runApplicationOnDisplay(content) { _, _, _ ->
                assertTrue(
                    LoopThread.awaitNamespace(OPENER_NAMESPACE, present = true),
                    "hyprctl layers never reported $OPENER_NAMESPACE",
                )
                assertTrue(
                    LoopThread.awaitNamespace(
                        OPENED_NAMESPACE,
                        present = true,
                        timeoutMillis = QUIET_MILLIS + LoopThread.APPEAR_MILLIS,
                    ),
                    "hyprctl layers never reported $OPENED_NAMESPACE after content showed it",
                )
            }
        }

        // The opened surface is created mid-run, so its snapshot pump and the opener's must share the loop's thread.
        assertFalse(
            printed.contains(SNAPSHOT_PUMP_WARNING),
            "GlobalSnapshotManager warned about concurrent registrations once content showed a surface",
        )
    }

    @Test
    fun `an effect that keeps yielding still lets the loop wait between yields`() {
        val yieldRequested = mutableStateOf(false)
        val composed = AtomicBoolean(false)
        val loopDisplay = AtomicReference<WaylandDisplay>()
        val waitsWhileYielding = AtomicReference<Long?>(null)
        val content: @Composable KortexApplicationScope.() -> Unit = {
            Show(
                TestSurface<Nothing>(YIELDING_NAMESPACE) {
                    // Twice: hyprctl lists this namespace once the compositor maps it, which can race this
                    // surface's own first composition; a first frame can still land inside
                    // KortexSurface.setContent's own synchronous flush, a second cannot.
                    LaunchedEffect(Unit) {
                        withFrameNanos {}
                        withFrameNanos {}
                        composed.set(true)
                    }
                    val requested = yieldRequested.value
                    LaunchedEffect(requested) {
                        if (!requested) return@LaunchedEffect
                        val display = loopDisplay.get()
                        val before = display.waits
                        repeat(YIELDS) { yield() }
                        waitsWhileYielding.set(display.waits - before)
                    }
                },
            )
        }

        LoopThread.runApplicationOnDisplay(content) { display, _, _ ->
            assertTrue(
                LoopThread.awaitNamespace(YIELDING_NAMESPACE, present = true),
                "hyprctl layers never reported $YIELDING_NAMESPACE",
            )
            assertTrue(
                LoopThread.waitUntil { composed.get() },
                "$YIELDING_NAMESPACE's content never drew its first frame",
            )
            loopDisplay.set(display)
            yieldRequested.value = true

            LoopThread.waitUntil { waitsWhileYielding.get() != null }
            val waits = assertNotNull(waitsWhileYielding.get(), "the yielding effect never finished its yields")
            // A pass per yield; half of that already rules out yields running back to back inside one pass.
            assertTrue(waits >= YIELDS / 2, "the loop waited only $waits times while an effect yielded $YIELDS times")
        }
    }

    private companion object {
        const val IDLE_NAMESPACE = "kortex-wake-idle"
        const val OPENER_NAMESPACE = "kortex-wake-opener"
        const val OPENED_NAMESPACE = "kortex-wake-opened"
        const val YIELDING_NAMESPACE = "kortex-wake-yielding"

        // Long enough for a fresh surface's own configure, first frames and buffer releases to come and go.
        const val QUIET_MILLIS = 1500L
        const val IDLE_WINDOW_MILLIS = 1000L

        // A 16ms tick wakes about 60 times in the window; an idle loop wakes only for a stray event of the desktop's.
        const val IDLE_WAIT_LIMIT = 5L
        const val YIELDS = 100

        val BOTTOM_LEFT = setOf(Edge.Bottom, Edge.Left)
    }
}
