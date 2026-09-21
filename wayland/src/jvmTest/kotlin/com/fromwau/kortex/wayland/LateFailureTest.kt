package com.fromwau.kortex.wayland

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kortex.compose.LocalKortexSurface
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * What a surface's content still runs once that surface has ended: the work its scene had already queued, and
 * nothing it schedules after, so every failure of it reaches `onClose` rather than arriving too late to be reported.
 */
class LateFailureTest {
    @Test
    fun `cleanup that waits stops at the wait, the surface reports first, and the run stays Ok`() {
        val reports = CopyOnWriteArrayList<Result<SurfaceEnd, SurfaceError<Nothing>>>()
        val reachedTheWait = AtomicBoolean(false)
        val ranPastTheWait = AtomicBoolean(false)
        val content: @Composable KortexApplicationScope.() -> Unit = {
            TestSurface<Nothing>(NAMESPACE, onClose = { reports += it }) {
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
                shell.pumpOrFail(PUMP_MILLIS) { reports.isNotEmpty() },
                "a surface whose cleanup waits reported nothing",
            )
            assertEquals(listOf(Ok(SurfaceEnd.Closed)), reports.toList(), "the surface did not report its own close")
            assertTrue(reachedTheWait.get(), "the cleanup never reached its wait, so nothing of it was left to drop")

            // Well past the wait, so the loop has been offered its resumption and has turned it away.
            shell.pumpOrFail(WAIT_MILLIS * PAST_THE_WAIT)

            assertFalse(ranPastTheWait.get(), "the cleanup ran on past its wait, after the surface had reported")
        }
    }

    @Test
    fun `cleanup that throws without waiting reports the crash`() {
        val reports = CopyOnWriteArrayList<Result<SurfaceEnd, SurfaceError<Nothing>>>()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            TestSurface<Nothing>(NAMESPACE, onClose = { reports += it }) {
                ClosingAfterCleanup { error(CLEANUP_FAILURE) }
            }
        }

        onApplication(content) { shell ->
            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { reports.isNotEmpty() },
                "a surface whose cleanup throws reported nothing",
            )
            val crash = crashIn(reports.single(), "cleanup that threw did not report Failed(SurfaceCrashed)")
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
        const val PAST_THE_WAIT = 3
        const val CLOSE_AFTER_MILLIS = 50L
        const val PUMP_MILLIS = 2_000L
    }
}
