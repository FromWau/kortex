package com.fromwau.kortex.wayland

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.getOrElse
import com.fromwau.kortex.compose.LocalKortexSurface
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.delay

/**
 * Runs a real [KortexShell.runEventLoop] on a thread of its own. That thread owns the connection until the
 * loop returns, so this one watches only through `hyprctl`, snapshot state and [WaylandDisplay.waits].
 */
class EventLoopWakeTest {
    @Test
    fun `an idle shell's loop stays asleep`() {
        val closeRequested = mutableStateOf(false)
        val speck = SurfaceSpec(speckConfig(IDLE_NAMESPACE), OutputTarget.CompositorChoice) {
            CloseWhen(closeRequested)
            Box(Modifier.fillMaxSize())
        }

        runLoop(speck, end = { closeRequested.value = true }) { display, _ ->
            assertTrue(awaitNamespace(IDLE_NAMESPACE, present = true), "hyprctl layers never reported $IDLE_NAMESPACE")
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

        runLoop(opener, end = { closeRequested.value = true }) { _, _ ->
            assertTrue(
                awaitNamespace(OPENER_NAMESPACE, present = true),
                "hyprctl layers never reported $OPENER_NAMESPACE",
            )
            assertTrue(
                awaitNamespace(OPENED_NAMESPACE, present = true, timeoutMillis = QUIET_MILLIS + APPEAR_MILLIS),
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

        runLoop(speck, end = {}) { _, loop ->
            assertTrue(
                awaitNamespace(CLOSING_NAMESPACE, present = true),
                "hyprctl layers never reported $CLOSING_NAMESPACE",
            )
            loop.join(QUIET_MILLIS + JOIN_MILLIS)
            assertFalse(loop.isAlive, "the loop kept sleeping after content closed its only surface")
            assertTrue(
                awaitNamespace(CLOSING_NAMESPACE, present = false),
                "hyprctl layers still reports $CLOSING_NAMESPACE after the loop returned",
            )
        }
    }

    /**
     * Runs a shell of [specs] on a loop thread of its own around [block], then calls [end], which has to make
     * the loop return. The shell and its display are closed only once it has, since until then the loop owns them.
     */
    private fun runLoop(
        vararg specs: SurfaceSpec,
        end: () -> Unit,
        block: (display: WaylandDisplay, loop: Thread) -> Unit,
    ) {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }
        val shell = KortexShell.create(display, *specs).getOrElse { error ->
            display.close()
            fail("shell creation failed: $error")
        }
        val loopFailure = AtomicReference<Throwable?>(null)
        val loop = Thread({ runCatching(shell::runEventLoop).onFailure(loopFailure::set) }, "kortex-wake-test-loop")
        loop.isDaemon = true
        loop.start()

        // Set in catch, read in finally, so a trouble found there attaches to the failure that surfaced first.
        var primary: Throwable? = null
        try {
            block(display, loop)
        } catch (thrown: Throwable) {
            primary = thrown
            throw thrown
        } finally {
            end()
            loop.join(JOIN_MILLIS)
            if (!loop.isAlive) {
                shell.close()
                display.close()
            }
            val trouble = when {
                loop.isAlive -> AssertionError(
                    "the loop never returned, so its connection, its surfaces and the thread stay live for the " +
                        "rest of this test JVM",
                )
                else -> loopFailure.get()?.let { AssertionError("the loop thread threw", it) }
            }
            if (trouble != null) {
                val existing = primary
                if (existing != null) existing.addSuppressed(trouble) else throw trouble
            }
        }
    }

    /** Polls `hyprctl layers` until [namespace] is there, or gone, as [present] asks, for at most [timeoutMillis]. */
    private fun awaitNamespace(namespace: String, present: Boolean, timeoutMillis: Long = APPEAR_MILLIS): Boolean {
        val deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLI
        while ((namespace in Hyprctl.namespaces()) != present) {
            if (System.nanoTime() >= deadline) return false
            Thread.sleep(HYPRCTL_POLL_MILLIS)
        }
        return true
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
        const val APPEAR_MILLIS = 3000L
        const val JOIN_MILLIS = 4000L
        const val HYPRCTL_POLL_MILLIS = 50L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

/** Closes the surface this content is on once [requested] turns true. */
@Composable
private fun CloseWhen(requested: MutableState<Boolean>) {
    val surface = LocalKortexSurface.current
    val close = requested.value
    LaunchedEffect(close) { if (close) surface.close() }
}
