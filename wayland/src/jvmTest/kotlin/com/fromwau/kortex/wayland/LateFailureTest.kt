package com.fromwau.kortex.wayland

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.fromwau.kern.result.Ok
import com.fromwau.kortex.compose.LocalKortexSurface
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * What a surface's content still runs once that surface has ended: the work its scene had already queued, and
 * nothing it schedules after. Every failure of that content reaches the surface's state, rather than arriving too
 * late for anyone to see it.
 */
class LateFailureTest {
    @Test
    fun `cleanup that waits stops at the wait, the surface ends first, and the run stays Ok`() {
        val speck = SurfaceState()
        val reachedTheWait = AtomicBoolean(false)
        val ranPastTheWait = AtomicBoolean(false)
        val content: @Composable KortexApplicationScope.() -> Unit = {
            TestSurface(NAMESPACE, state = speck) {
                ClosingAfterCleanup {
                    reachedTheWait.set(true)
                    delay(WAIT_MILLIS)
                    ranPastTheWait.set(true)
                    error(LATE_FAILURE)
                }
            }
        }

        onApplication(content) { shell ->
            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { speck.hasEnded },
                "a surface whose cleanup waits ended nothing",
            )
            speck.assertEnded(Ok(SurfaceEnd.Closed), "the surface did not end with its own close")
            assertTrue(reachedTheWait.get(), "the cleanup never reached its wait, so nothing of it was left to drop")

            // Well past the wait, so the loop has been offered its resumption and has turned it away.
            shell.pumpOrFail(PAST_THE_WAIT_MILLIS)

            assertFalse(ranPastTheWait.get(), "the cleanup ran on past its wait, after the surface had ended")
        }
    }

    @Test
    fun `cleanup that hops to another dispatcher runs there, and the run stays Ok`() {
        val speck = SurfaceState()
        val loopThread = Thread.currentThread()
        val hoppedTo = AtomicReference<Thread?>(null)
        val content: @Composable KortexApplicationScope.() -> Unit = {
            TestSurface(NAMESPACE, state = speck) {
                ClosingAfterCleanup {
                    withContext(Dispatchers.IO) { hoppedTo.set(Thread.currentThread()) }
                }
            }
        }

        onApplication(content) { shell ->
            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { speck.hasEnded },
                "a surface whose cleanup hops away ended nothing",
            )
            speck.assertEnded(Ok(SurfaceEnd.Closed), "the surface did not end with its own close")
            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { hoppedTo.get() != null },
                "the cleanup never reached the dispatcher it hopped to",
            )
            assertNotEquals(
                loopThread,
                hoppedTo.get(),
                "the cleanup ran on the loop's thread, not the dispatcher it hopped to",
            )
        }
    }

    @Test
    fun `cleanup that throws without waiting ends with the crash`() {
        val speck = SurfaceState()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            TestSurface(NAMESPACE, state = speck) {
                ClosingAfterCleanup { error(CLEANUP_FAILURE) }
            }
        }

        onApplication(content) { shell ->
            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { speck.hasEnded },
                "a surface whose cleanup throws ended nothing",
            )
            val crash = speck.crashOrFail("cleanup that threw did not end the surface with SurfaceCrashed")
            assertEquals(CLEANUP_FAILURE, crash.failure.cause.message, "the crash did not carry what cleanup threw")
        }
    }

    /** Content that closes its own surface, and whose effect runs [cleanup] as the surface takes it down. */
    @Composable
    private fun ClosingAfterCleanup(cleanup: suspend () -> Unit) {
        val surface = LocalKortexSurface.current
        LaunchedEffect(Unit) {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { cleanup() }
            }
        }
        LaunchedEffect(Unit) {
            delay(CLOSE_AFTER_MILLIS)
            surface.close()
        }
    }

    private companion object {
        const val NAMESPACE = "kortex-late"
        const val LATE_FAILURE = "cleanup threw after its wait"
        const val CLEANUP_FAILURE = "cleanup threw as the surface went"
        const val WAIT_MILLIS = 200L
        const val CLOSE_AFTER_MILLIS = 50L
        const val PUMP_MILLIS = 2_000L

        // Past the wait, so work the loop would have taken has had its chance to arrive and be turned away.
        const val PAST_THE_WAIT_MILLIS = 3 * WAIT_MILLIS
    }
}
