package com.fromwau.kortex.wayland

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.getOrElse
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/** Which of a surface's Compose work has run by the time its close returns, and what still runs after. */
class SurfaceCloseCancellationTest {
    @Test
    fun `a surface's effects and recomposer have finished cancelling by the time its close returns`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }
        val started = AtomicBoolean(false)
        val cancelled = AtomicBoolean(false)

        display.use { wayland ->
            val before = Recomposer.runningRecomposers.value
            // A bare surface: a shell's own close runs its queue once more, which would hide what the surface's did.
            val surface = KortexSurface.create(wayland, speckConfig(BARE_NAMESPACE))
                .getOrElse { error -> fail("surface creation failed: $error") }
            val ours = Recomposer.runningRecomposers.value - before

            surface.use {
                surface.setContent {
                    LaunchedEffect(Unit) {
                        started.set(true)
                        try {
                            awaitCancellation()
                        } finally {
                            cancelled.set(true)
                        }
                    }
                    Box(Modifier.fillMaxSize())
                }
                assertTrue(surface.pump(PUMP_TIMEOUT_MILLIS) { started.get() }, "the surface's effect never started")
            }

            assertEquals(1, ours.size, "the surface did not run exactly one recomposer")
            assertTrue(cancelled.get(), "an effect's finally had not run by the time its surface's close returned")
            assertTrue(
                Recomposer.runningRecomposers.value.none { it in ours },
                "a closed surface's recomposer was still running after its close returned",
            )
        }
    }

    @Test
    fun `a closed surface's late work still runs while another surface keeps its shell running`() {
        val closeFirst = mutableStateOf(false)
        val closeSecond = mutableStateOf(false)
        val finished = AtomicBoolean(false)
        val first = SurfaceSpec(speckConfig(FIRST_NAMESPACE), OutputTarget.CompositorChoice) {
            LaunchedEffect(Unit) {
                try {
                    awaitCancellation()
                } finally {
                    // Suspends past the close, so it resumes only once its surface is gone.
                    withContext(NonCancellable) { delay(LATE_MILLIS) }
                    finished.set(true)
                }
            }
            CloseWhen(closeFirst)
            Box(Modifier.fillMaxSize())
        }
        val second = SurfaceSpec(speckConfig(SECOND_NAMESPACE), OutputTarget.CompositorChoice) {
            CloseWhen(closeSecond)
            Box(Modifier.fillMaxSize())
        }

        LoopThread.run(first, second, end = { closeSecond.value = true }) { _, loop ->
            assertTrue(
                LoopThread.awaitNamespace(FIRST_NAMESPACE, present = true),
                "hyprctl layers never reported $FIRST_NAMESPACE",
            )
            assertTrue(
                LoopThread.awaitNamespace(SECOND_NAMESPACE, present = true),
                "hyprctl layers never reported $SECOND_NAMESPACE",
            )

            closeFirst.value = true

            assertTrue(
                LoopThread.awaitNamespace(FIRST_NAMESPACE, present = false),
                "hyprctl layers still reports $FIRST_NAMESPACE after its content closed it",
            )
            assertTrue(LoopThread.waitUntil { finished.get() }, "a closed surface's finally never got past its delay")
            assertTrue(loop.isAlive, "the loop ended while $SECOND_NAMESPACE was still open")
        }
    }

    @Test
    fun `closing one surface returns while a sibling's content keeps yielding`() {
        val closeQuiet = mutableStateOf(false)
        val closeSpinning = mutableStateOf(false)
        val spin = Spin()

        LoopThread.run(
            quiet(QUIET_NAMESPACE, closeQuiet),
            spinning(SPINNING_NAMESPACE, spin, closeSpinning),
            end = {
                spin.stop.set(true)
                closeSpinning.value = true
            },
        ) { _, _ ->
            assertTrue(
                LoopThread.awaitNamespace(QUIET_NAMESPACE, present = true),
                "hyprctl layers never reported $QUIET_NAMESPACE",
            )
            assertTrue(
                LoopThread.awaitNamespace(SPINNING_NAMESPACE, present = true),
                "hyprctl layers never reported $SPINNING_NAMESPACE",
            )
            spin.go.value = true
            assertTrue(
                LoopThread.waitUntil { spin.steps.get() > 0 },
                "$SPINNING_NAMESPACE's content never started yielding",
            )

            closeQuiet.value = true

            // The wl_surface is destroyed only after the close has run the Compose work it waits for.
            assertTrue(
                LoopThread.awaitNamespace(QUIET_NAMESPACE, present = false),
                "hyprctl layers still reports $QUIET_NAMESPACE after its content closed it beside a yielding sibling",
            )
            val stepsOnceClosed = spin.steps.get()
            assertTrue(
                LoopThread.waitUntil { spin.steps.get() > stepsOnceClosed },
                "$SPINNING_NAMESPACE's content stopped yielding once $QUIET_NAMESPACE closed",
            )
        }
    }

    @Test
    fun `a shell's close returns while one of its surfaces keeps yielding`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }
        val spin = Spin()

        display.use { wayland ->
            // The quiet surface first: a shell closes its surfaces in the order it placed them.
            val shell = KortexShell
                .create(wayland, quiet(SHELL_QUIET_NAMESPACE), spinning(SHELL_SPINNING_NAMESPACE, spin))
                .getOrElse { error -> fail("shell creation failed: $error") }

            // Rethrown only once the shell has closed under its own bound, which a failed setup must not skip.
            val setUp = runCatching { startSpinning(shell, spin) }
            val closeHeld = try {
                spin.stopIfHeldPast(CLOSE_BOUND_MILLIS) { shell.close() }
            } finally {
                spin.stop.set(true)
            }

            setUp.getOrThrow()
            assertFalse(closeHeld, "the shell's close returned only once the yielding content was made to stop")
        }
    }

    @Test
    fun `a shell's final drain runs work that reaches the queue after its last surface closed`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }
        val close = mutableStateOf(false)
        val parked = AtomicReference<CancellableContinuation<Unit>?>(null)
        val finished = AtomicBoolean(false)

        display.use { wayland ->
            val spec = SurfaceSpec(speckConfig(DRAIN_NAMESPACE), OutputTarget.CompositorChoice) {
                LaunchedEffect(Unit) {
                    try {
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) { suspendCancellableCoroutine { parked.set(it) } }
                        finished.set(true)
                    }
                }
                CloseWhen(close)
                Box(Modifier.fillMaxSize())
            }
            val shell = KortexShell.create(wayland, spec).getOrElse { error -> fail("shell creation failed: $error") }

            val up = shell.pump(PUMP_TIMEOUT_MILLIS) { DRAIN_NAMESPACE in Hyprctl.namespaces() }
            assertTrue(up, "hyprctl layers never reported $DRAIN_NAMESPACE")

            close.value = true
            val gone = shell.pump(PUMP_TIMEOUT_MILLIS) {
                DRAIN_NAMESPACE !in Hyprctl.namespaces() && parked.get() != null
            }
            assertTrue(gone, "the surface never left hyprctl layers with its finally parked")

            val continuation = parked.get() ?: fail("the finally never reached its parked suspension point")
            // The scene's dispatcher always redispatches, so this only queues the rest of the finally.
            continuation.resume(Unit)
            assertFalse(finished.get(), "the resume ran the finally inline instead of through the queue")

            shell.close()
            assertTrue(
                finished.get(),
                "the shell's final drain never ran what reached the queue after its last surface closed",
            )
        }
    }

    /** Pumps [shell] until both specks are up and [spin] runs, failing if the spin held a pump past the bound. */
    private fun startSpinning(shell: KortexShell, spin: Spin) {
        // This thread runs the spin, so a render of its surface during a pump would hold it there.
        val held = spin.stopIfHeldPast(SET_UP_BOUND_MILLIS) {
            val up = shell.pump(PUMP_TIMEOUT_MILLIS) { Hyprctl.namespaces().containsAll(SHELL_NAMESPACES) }
            assertTrue(up, "hyprctl layers never reported all of $SHELL_NAMESPACES")
            spin.go.value = true
            val yielding = shell.pump(PUMP_TIMEOUT_MILLIS) { spin.steps.get() > 0 }
            assertTrue(yielding, "$SHELL_SPINNING_NAMESPACE's content never started yielding")
        }
        assertFalse(held, "setting the shell up was held until its yielding content was made to stop")
    }

    private fun quiet(
        namespace: String,
        close: MutableState<Boolean> = mutableStateOf(false),
    ): SurfaceSpec = SurfaceSpec(speckConfig(namespace), OutputTarget.CompositorChoice) {
        CloseWhen(close)
        Box(Modifier.fillMaxSize())
    }

    private fun spinning(
        namespace: String,
        spin: Spin,
        close: MutableState<Boolean> = mutableStateOf(false),
    ): SurfaceSpec = SurfaceSpec(speckConfig(namespace), OutputTarget.CompositorChoice) {
        Spinning(spin)
        CloseWhen(close)
        Box(Modifier.fillMaxSize())
    }

    /** A speck in the output's bottom-right corner, where the pointer is least likely to reach it. */
    private fun speckConfig(namespace: String): SurfaceConfig = SurfaceConfig(
        namespace = namespace,
        layer = Layer.Overlay,
        anchor = setOf(Edge.Bottom, Edge.Right),
        width = SPECK_SIZE.dp,
        height = SPECK_SIZE.dp,
        exclusiveZone = ExclusiveZone.Yield,
    )

    private companion object {
        const val BARE_NAMESPACE = "kortex-close-cancel"
        const val FIRST_NAMESPACE = "kortex-close-cancel-first"
        const val SECOND_NAMESPACE = "kortex-close-cancel-second"
        const val QUIET_NAMESPACE = "kortex-close-cancel-quiet"
        const val SPINNING_NAMESPACE = "kortex-close-cancel-spinning"
        const val SHELL_QUIET_NAMESPACE = "kortex-close-cancel-shell-quiet"
        const val SHELL_SPINNING_NAMESPACE = "kortex-close-cancel-shell-spinning"
        const val DRAIN_NAMESPACE = "kortex-close-cancel-drain"
        const val SPECK_SIZE = 8
        const val LATE_MILLIS = 300L
        const val PUMP_TIMEOUT_MILLIS = 4000L

        // Past both pumps' own timeouts, so only a pump the spin holds can outlast it.
        const val SET_UP_BOUND_MILLIS = 2 * PUMP_TIMEOUT_MILLIS + 1000L

        // Far past the fraction of a second a shell of two specks takes to close.
        const val CLOSE_BOUND_MILLIS = 2000L

        val SHELL_NAMESPACES = listOf(SHELL_QUIET_NAMESPACE, SHELL_SPINNING_NAMESPACE)
    }
}

/** An effect's endless yield loop: [go] starts it, [stop] ends it, and [steps] counts the yields it has made. */
private class Spin {
    val go = mutableStateOf(false)
    val stop = AtomicBoolean(false)
    val steps = AtomicLong()

    /** Runs [block], setting [stop] if it has not returned within [boundMillis]; returns whether it had to. */
    fun stopIfHeldPast(boundMillis: Long, block: () -> Unit): Boolean {
        val returned = CountDownLatch(1)
        val heldPast = AtomicBoolean(false)
        val watchdog = thread(isDaemon = true, name = "kortex-test-watchdog") {
            if (!returned.await(boundMillis, TimeUnit.MILLISECONDS)) {
                heldPast.set(true)
                stop.set(true)
            }
        }
        try {
            block()
        } finally {
            returned.countDown()
            watchdog.join()
        }
        return heldPast.get()
    }
}

/** Yields for as long as [spin] runs, from once its `go` turns true. */
@Composable
private fun Spinning(spin: Spin) {
    LaunchedEffect(Unit) {
        // Read outside composition: a recomposition renders, and a render's flush would run this loop to its end.
        snapshotFlow { spin.go.value }.first { it }
        while (!spin.stop.get()) {
            spin.steps.incrementAndGet()
            yield()
        }
    }
}
