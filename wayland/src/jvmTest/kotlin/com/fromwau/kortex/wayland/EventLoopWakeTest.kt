package com.fromwau.kortex.wayland

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fromwau.kortex.compose.LocalKortexSurface
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.delay

/** A real [KortexShell.runEventLoop], run through [LoopThread]: it sleeps with nothing to do, and wakes for content. */
class EventLoopWakeTest {
    @Test
    fun `an idle shell's loop stays asleep`() {
        val closeRequested = mutableStateOf(false)
        val speck = SurfaceSpec(speckConfig(IDLE_NAMESPACE), OutputTarget.CompositorChoice) {
            CloseWhen(closeRequested)
            Box(Modifier.fillMaxSize())
        }

        LoopThread.run(speck, end = { closeRequested.value = true }) { display, _ ->
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
    fun `a surface content opens is placed while the loop sleeps`() {
        val closeRequested = mutableStateOf(false)
        val opened = SurfaceSpec(speckConfig(OPENED_NAMESPACE), OutputTarget.CompositorChoice) {
            CloseWhen(closeRequested)
            Box(Modifier.fillMaxSize())
        }
        val opener = SurfaceSpec(speckConfig(OPENER_NAMESPACE), OutputTarget.CompositorChoice) {
            val host = LocalKortexHost.current
            // Once the loop has gone quiet, so nothing but open() itself can wake it.
            LaunchedEffect(Unit) {
                delay(QUIET_MILLIS)
                host.open(opened)
            }
            CloseWhen(closeRequested)
            Box(Modifier.fillMaxSize())
        }

        LoopThread.run(opener, end = { closeRequested.value = true }) { _, _ ->
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
                "hyprctl layers never reported $OPENED_NAMESPACE after content opened it",
            )
        }
    }

    @Test
    fun `content closing its own last surface ends the loop`() {
        val speck = SurfaceSpec(speckConfig(CLOSING_NAMESPACE), OutputTarget.CompositorChoice) {
            val surface = LocalKortexSurface.current
            // Once the loop has gone quiet, so nothing but close() itself can wake it.
            LaunchedEffect(Unit) {
                delay(QUIET_MILLIS)
                surface.close()
            }
            Box(Modifier.fillMaxSize())
        }

        LoopThread.run(speck, end = {}) { _, loop ->
            assertTrue(
                LoopThread.awaitNamespace(CLOSING_NAMESPACE, present = true),
                "hyprctl layers never reported $CLOSING_NAMESPACE",
            )
            loop.join(QUIET_MILLIS + LoopThread.JOIN_MILLIS)
            assertFalse(loop.isAlive, "the loop kept sleeping after content closed its only surface")
            assertTrue(
                LoopThread.awaitNamespace(CLOSING_NAMESPACE, present = false),
                "hyprctl layers still reports $CLOSING_NAMESPACE after the loop returned",
            )
        }
    }

    /** A speck in the output's bottom-right corner, where the pointer is least likely to wake the loop itself. */
    private fun speckConfig(namespace: String): SurfaceConfig = SurfaceConfig(
        namespace = namespace,
        layer = Layer.Overlay,
        anchor = setOf(Edge.Bottom, Edge.Right),
        width = SPECK_SIZE.dp,
        height = SPECK_SIZE.dp,
        exclusiveZone = ExclusiveZone.Yield,
    )

    private companion object {
        const val IDLE_NAMESPACE = "kortex-wake-idle"
        const val OPENER_NAMESPACE = "kortex-wake-opener"
        const val OPENED_NAMESPACE = "kortex-wake-opened"
        const val CLOSING_NAMESPACE = "kortex-wake-closing"
        const val SPECK_SIZE = 8

        // Long enough for a fresh surface's own configure, first frames and buffer releases to come and go.
        const val QUIET_MILLIS = 1500L
        const val IDLE_WINDOW_MILLIS = 1000L

        // A 16ms tick wakes about 60 times in the window; an idle loop wakes only for a stray event of the desktop's.
        const val IDLE_WAIT_LIMIT = 5L
    }
}
