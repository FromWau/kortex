package com.fromwau.kortex.wayland

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.Ok
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

/** Which of a surface's Compose work has run by the time its close returns, and what is dropped after. */
class SurfaceCloseCancellationTest {
    @Test
    fun `a surface's effects and recomposer have finished cancelling by the time its scene's close returns`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }
        val started = AtomicBoolean(false)
        val cancelled = AtomicBoolean(false)

        display.use { wayland ->
            val before = Recomposer.runningRecomposers.value
            // A bare surface: a shell's own close runs its queue once more, which would hide what the scene's did.
            val (surface, scene) = bareSurface(wayland, speckConfig(BARE_NAMESPACE))
            val ours = Recomposer.runningRecomposers.value - before

            try {
                scene.setContent {
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
                assertTrue(
                    surface.pumpOrFail(PUMP_TIMEOUT_MILLIS) { started.get() },
                    "the surface's effect never started",
                )
            } finally {
                surface.close()
                scene.close()
            }

            assertEquals(1, ours.size, "the surface did not run exactly one recomposer")
            assertTrue(cancelled.get(), "an effect's finally had not run by the time its scene's close returned")
            assertTrue(
                Recomposer.runningRecomposers.value.none { it in ours },
                "a closed scene's recomposer was still running after its close returned",
            )
        }
    }

    @Test
    fun `a closed surface's late work is dropped while another surface keeps its application running`() {
        val closeFirst = mutableStateOf(false)
        val waiting = AtomicBoolean(false)
        val finished = AtomicBoolean(false)
        val content: @Composable KortexApplicationScope.() -> Unit = {
            TestSurface<Nothing>(FIRST_NAMESPACE) {
                LaunchedEffect(Unit) {
                    try {
                        awaitCancellation()
                    } finally {
                        // Suspends past the close, so it would resume only once its surface is gone.
                        withContext(NonCancellable) {
                            waiting.set(true)
                            delay(LATE_MILLIS)
                        }
                        finished.set(true)
                    }
                }
                CloseWhen(closeFirst)
            }
            TestSurface<Nothing>(SECOND_NAMESPACE, anchor = BOTTOM_LEFT)
        }

        val result = LoopThread.runApplication(content) { _, loop ->
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
            assertTrue(LoopThread.waitUntil { waiting.get() }, "a closed surface's finally never reached its delay")
            assertFalse(
                LoopThread.waitUntil(PAST_LATE_MILLIS) { finished.get() },
                "a closed surface's finally got past its delay, after the surface had gone",
            )
            assertTrue(loop.isAlive, "the application ended while $SECOND_NAMESPACE was still shown")
            assertTrue(
                SECOND_NAMESPACE in Hyprctl.namespaces(),
                "hyprctl layers stopped reporting $SECOND_NAMESPACE once $FIRST_NAMESPACE closed",
            )
        }
        assertEquals(Ok(Unit), result, "the application did not return Ok on exitApplication")
    }

    @Test
    fun `closing one surface returns while a sibling's content keeps yielding`() {
        val closeQuiet = mutableStateOf(false)
        val spin = Spin()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            TestSurface<Nothing>(QUIET_NAMESPACE) {
                CloseWhen(closeQuiet)
                Box(Modifier.fillMaxSize())
            }
            TestSurface<Nothing>(SPINNING_NAMESPACE, anchor = BOTTOM_LEFT) {
                Spinning(spin)
                Box(Modifier.fillMaxSize())
            }
        }

        LoopThread.runApplication(content) { _, _ ->
            try {
                assertTrue(
                    LoopThread.awaitNamespace(QUIET_NAMESPACE, present = true),
                    "hyprctl layers never reported $QUIET_NAMESPACE",
                )
                assertTrue(
                    LoopThread.waitUntil { spin.composed.get() },
                    "$SPINNING_NAMESPACE's content never drew its first frame",
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
                    "hyprctl layers still reports $QUIET_NAMESPACE after its content closed it beside a " +
                        "yielding sibling",
                )
                val stepsOnceClosed = spin.steps.get()
                assertTrue(
                    LoopThread.waitUntil { spin.steps.get() > stepsOnceClosed },
                    "$SPINNING_NAMESPACE's content stopped yielding once $QUIET_NAMESPACE closed",
                )
            } finally {
                // Lets exitApplication's own teardown, right after this block, actually finish, on every path.
                spin.stop.set(true)
            }
        }
    }

    @Test
    fun `an application's close returns while one of its surfaces keeps yielding`() {
        val spin = Spin()
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use { wayland ->
            // The quiet surface first: an application closes its surfaces in the order it placed them.
            val content: @Composable KortexApplicationScope.() -> Unit = {
                TestSurface<Nothing>(APPLICATION_QUIET_NAMESPACE)
                TestSurface<Nothing>(APPLICATION_SPINNING_NAMESPACE, anchor = BOTTOM_LEFT) {
                    Spinning(spin)
                    Box(Modifier.fillMaxSize())
                }
            }
            val shell = KortexShell.createApplicationOrFail(wayland, content)

            closeAfter(
                shell,
                spin,
                setUp = { startSpinning(shell, spin) },
                heldMessage = "the application's close returned only once the yielding content was made to stop",
            )
        }
    }

    @Test
    fun `work that reaches the queue after its surface closed is dropped, the application's final drain included`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }
        val close = mutableStateOf(false)
        val parked = AtomicReference<CancellableContinuation<Unit>?>(null)
        val finished = AtomicBoolean(false)

        display.use { wayland ->
            val content: @Composable KortexApplicationScope.() -> Unit = {
                TestSurface<Nothing>(DRAIN_NAMESPACE) {
                    LaunchedEffect(Unit) {
                        try {
                            awaitCancellation()
                        } finally {
                            withContext(NonCancellable) { suspendCancellableCoroutine { parked.set(it) } }
                            finished.set(true)
                        }
                    }
                    CloseWhen(close)
                }
            }
            val shell = KortexShell.createApplicationOrFail(wayland, content)

            val up = shell.pumpOrFail(PUMP_TIMEOUT_MILLIS) { DRAIN_NAMESPACE in Hyprctl.namespaces() }
            assertTrue(up, "hyprctl layers never reported $DRAIN_NAMESPACE")

            close.value = true
            val gone = shell.pumpOrFail(PUMP_TIMEOUT_MILLIS) {
                DRAIN_NAMESPACE !in Hyprctl.namespaces() && parked.get() != null
            }
            assertTrue(gone, "the surface never left hyprctl layers with its finally parked")

            val continuation = parked.get() ?: fail("the finally never reached its parked suspension point")
            // The scene's dispatcher always redispatches, so this offers the rest of the finally to the queue.
            continuation.resume(Unit)
            assertFalse(finished.get(), "the resume ran the finally inline, past the surface that had closed")

            shell.close()
            assertFalse(
                finished.get(),
                "the application's final drain ran what its closed surface's content offered after the close",
            )
        }
    }

    @Test
    fun `closing a surface runs its cleanup's yields to the drain's bound and drops the rest`() {
        val closeCleanup = mutableStateOf(false)
        val spin = Spin()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            TestSurface<Nothing>(CLEANUP_NAMESPACE) {
                SpinningCleanup(spin)
                CloseWhen(closeCleanup)
                Box(Modifier.fillMaxSize())
            }
            // Keeps the application running once the other surface is gone, so its passes are all that can run cleanup.
            TestSurface<Nothing>(CLEANUP_SIBLING_NAMESPACE, anchor = BOTTOM_LEFT)
        }

        LoopThread.runApplication(content) { _, loop ->
            try {
                assertTrue(
                    LoopThread.awaitNamespace(CLEANUP_NAMESPACE, present = true),
                    "hyprctl layers never reported $CLEANUP_NAMESPACE",
                )
                assertTrue(
                    LoopThread.awaitNamespace(CLEANUP_SIBLING_NAMESPACE, present = true),
                    "hyprctl layers never reported $CLEANUP_SIBLING_NAMESPACE",
                )

                closeCleanup.value = true

                assertTrue(
                    LoopThread.awaitNamespace(CLEANUP_NAMESPACE, present = false),
                    "hyprctl layers still reports $CLEANUP_NAMESPACE after its content closed it, its cleanup yielding",
                )
                assertTrue(
                    LoopThread.waitUntil { spin.steps.get() >= DRAIN_BOUND_STEPS },
                    "$CLEANUP_NAMESPACE's cleanup did not yield the drain's rounds before its surface let it go",
                )
                assertFalse(
                    LoopThread.waitUntil(SETTLE_MILLIS) { spin.steps.get() > DRAIN_BOUND_STEPS },
                    "$CLEANUP_NAMESPACE's cleanup kept yielding past the drain's bound, its surface gone",
                )
                // Checked after the steps: an application that had ended would have advanced them in its final drain.
                assertTrue(loop.isAlive, "the application ended while $CLEANUP_SIBLING_NAMESPACE was still shown")
                assertTrue(
                    CLEANUP_SIBLING_NAMESPACE in Hyprctl.namespaces(),
                    "hyprctl layers stopped reporting $CLEANUP_SIBLING_NAMESPACE once $CLEANUP_NAMESPACE closed",
                )
            } finally {
                spin.stop.set(true)
            }
        }
    }

    @Test
    fun `an application's close returns once a closed surface's yielding cleanup has hit the drain's bound`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }
        val close = mutableStateOf(false)
        val spin = Spin()

        display.use { wayland ->
            val content: @Composable KortexApplicationScope.() -> Unit = {
                TestSurface<Nothing>(APPLICATION_CLEANUP_NAMESPACE) {
                    SpinningCleanup(spin)
                    CloseWhen(close)
                    Box(Modifier.fillMaxSize())
                }
            }
            val shell = KortexShell.createApplicationOrFail(wayland, content)

            closeAfter(
                shell,
                spin,
                setUp = { closeWhileCleanupYields(shell, spin, close) },
                heldMessage = "the application's close returned only once a closed surface's cleanup was made to stop",
            )
        }
    }

    /** Runs [setUp], then closes [shell] under [CLOSE_BOUND_MILLIS], failing with [heldMessage] if [spin] held it. */
    private fun closeAfter(
        shell: KortexShell,
        spin: Spin,
        setUp: () -> Unit,
        heldMessage: String,
    ) {
        // Rethrown only once the application has closed under its own bound, which a failed setup must not skip.
        val setUpResult = runCatching(setUp)
        val closeHeld = try {
            spin.stopIfHeldPast(CLOSE_BOUND_MILLIS) { shell.close() }
        } finally {
            spin.stop.set(true)
        }

        setUpResult.getOrThrow()
        assertFalse(closeHeld, heldMessage)
    }

    /** Pumps [shell] until both specks are up and [spin] runs, failing if the spin held a pump past the bound. */
    private fun startSpinning(shell: KortexShell, spin: Spin) {
        // This thread runs the spin, so a render of its surface during a pump would hold it there.
        val held = spin.stopIfHeldPast(SET_UP_BOUND_MILLIS) {
            val up = shell.pumpOrFail(PUMP_TIMEOUT_MILLIS) {
                Hyprctl.namespaces().containsAll(APPLICATION_NAMESPACES)
            }
            assertTrue(up, "hyprctl layers never reported all of $APPLICATION_NAMESPACES")
            spin.go.value = true
            val yielding = shell.pumpOrFail(PUMP_TIMEOUT_MILLIS) { spin.steps.get() > 0 }
            assertTrue(yielding, "$APPLICATION_SPINNING_NAMESPACE's content never started yielding")
        }
        assertFalse(held, "setting the application up was held until its yielding content was made to stop")
    }

    /** Pumps [shell] until its surface is up, closes it through [close], and checks where [spin] stopped. */
    private fun closeWhileCleanupYields(shell: KortexShell, spin: Spin, close: MutableState<Boolean>) {
        val up = shell.pumpOrFail(PUMP_TIMEOUT_MILLIS) { APPLICATION_CLEANUP_NAMESPACE in Hyprctl.namespaces() }
        assertTrue(up, "hyprctl layers never reported $APPLICATION_CLEANUP_NAMESPACE")
        var gone = false
        var steps = 0L
        // The close and the passes after it all run on this thread, where a cleanup that never ends could hold them.
        val held = spin.stopIfHeldPast(SET_UP_BOUND_MILLIS) {
            close.value = true
            // The teardown, its drain included, runs inside this pump, so its steps are final once the surface is gone.
            gone = shell.pumpOrFail(PUMP_TIMEOUT_MILLIS) { APPLICATION_CLEANUP_NAMESPACE !in Hyprctl.namespaces() }
            steps = spin.steps.get()
            shell.pumpOrFail(SETTLE_MILLIS)
        }
        // Checked first: a spin the watchdog stopped would otherwise show up as a cleanup that stopped too early.
        assertFalse(
            held,
            "closing $APPLICATION_CLEANUP_NAMESPACE, or a pass after it, was held until its cleanup was made to stop",
        )
        assertTrue(gone, "hyprctl layers still reports $APPLICATION_CLEANUP_NAMESPACE after its content closed it")
        assertEquals(
            DRAIN_BOUND_STEPS,
            steps,
            "$APPLICATION_CLEANUP_NAMESPACE's cleanup did not yield exactly the drain's rounds before it was dropped",
        )
        assertEquals(
            DRAIN_BOUND_STEPS,
            spin.steps.get(),
            "$APPLICATION_CLEANUP_NAMESPACE's cleanup kept yielding in the passes after its surface had gone",
        )
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
        const val APPLICATION_QUIET_NAMESPACE = "kortex-close-cancel-application-quiet"
        const val APPLICATION_SPINNING_NAMESPACE = "kortex-close-cancel-application-spinning"
        const val DRAIN_NAMESPACE = "kortex-close-cancel-drain"
        const val CLEANUP_NAMESPACE = "kortex-close-cancel-cleanup"
        const val CLEANUP_SIBLING_NAMESPACE = "kortex-close-cancel-cleanup-sibling"
        const val APPLICATION_CLEANUP_NAMESPACE = "kortex-close-cancel-application-cleanup"
        const val SPECK_SIZE = 8
        const val LATE_MILLIS = 300L
        const val PUMP_TIMEOUT_MILLIS = 4000L

        // Past the delay, so work the loop would have taken has had its chance to arrive and be turned away.
        const val PAST_LATE_MILLIS = 3 * LATE_MILLIS
        const val SETTLE_MILLIS = 500L

        // One yield per round of the drain a closing scene runs, and none after it, since its work closes there.
        const val DRAIN_BOUND_STEPS = LoopQueue.DRAIN_BOUND_ROUNDS.toLong()

        // Past both pumps' own timeouts, so only a pump the spin holds can outlast it.
        const val SET_UP_BOUND_MILLIS = 2 * PUMP_TIMEOUT_MILLIS + 1000L

        // Far past the fraction of a second an application of two specks takes to close.
        const val CLOSE_BOUND_MILLIS = 2000L

        val APPLICATION_NAMESPACES = listOf(APPLICATION_QUIET_NAMESPACE, APPLICATION_SPINNING_NAMESPACE)
        val BOTTOM_LEFT = setOf(Edge.Bottom, Edge.Left)
    }
}

/** An endless yield loop: [stop] ends it, [steps] counts the yields it has made, and [go] starts [Spinning]'s. */
private class Spin {
    val go = mutableStateOf(false)
    val stop = AtomicBoolean(false)
    val steps = AtomicLong()

    // Set once Spinning draws a frame outside setContent. hyprctl lists the surface sooner, and a go set that early
    // can start the spin inside setContent's own flush, which then never returns.
    val composed = AtomicBoolean(false)

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

/** Yields for as long as [spin] runs, from once its `go` turns true and its own first frame has drawn. */
@Composable
private fun Spinning(spin: Spin) {
    LaunchedEffect(Unit) {
        // Twice: a first frame can still land inside setContent's own initial flush; a second one cannot,
        // since nothing but a later, separate loop pass produces it.
        withFrameNanos {}
        withFrameNanos {}
        spin.composed.set(true)
        // Read outside composition: a recomposition renders, and a render's flush would run this loop to its end.
        snapshotFlow { spin.go.value }.first { it }
        while (!spin.stop.get()) {
            spin.steps.incrementAndGet()
            yield()
        }
    }
}

/** Yields from once its effect is cancelled, under [NonCancellable], for as long as [spin] runs. */
@Composable
private fun SpinningCleanup(spin: Spin) {
    LaunchedEffect(Unit) {
        try {
            awaitCancellation()
        } finally {
            withContext(NonCancellable) {
                while (!spin.stop.get()) {
                    spin.steps.incrementAndGet()
                    yield()
                }
            }
        }
    }
}
