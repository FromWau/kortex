package com.fromwau.kortex.wayland

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import com.fromwau.kortex.compose.ContentFailure
import com.fromwau.kortex.compose.LocalKortexSurface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.delay

/** Content that throws ends its surface with a [KortexError.SurfaceCrashed], and the run goes on. */
class ContentFailureTest {
    @Test
    fun `content that throws on its first frame ends with the crash, and the run goes on`() {
        val speck = SurfaceState()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            TestSurface(NAMESPACE, state = speck) {
                Canvas(Modifier.fillMaxSize()) { error(DRAW_FAILURE) }
            }
        }

        onApplication(content) { shell ->
            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { speck.hasEnded },
                "a first frame that throws ended nothing",
            )
            val crash = speck.crashOrFail("a first frame that throws did not end with SurfaceCrashed")
            assertEquals(NAMESPACE, crash.namespace)
            assertIs<ContentFailure.Composition>(crash.failure)
            assertEquals(DRAW_FAILURE, crash.failure.cause.message)
            // A run that ended would fail this pump with an Err; letting it settle proves the crash did not end it.
            shell.pumpOrFail(SETTLE_MILLIS)
        }
    }

    @Test
    fun `content that closes itself and whose cleanup then throws ends with the crash, not Ok`() {
        val speck = SurfaceState()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            TestSurface(NAMESPACE, state = speck) {
                val surface = LocalKortexSurface.current
                DisposableEffect(Unit) { onDispose { error(CLEANUP_FAILURE) } }
                LaunchedEffect(Unit) {
                    delay(CLOSE_AFTER_MILLIS)
                    surface.close()
                }
            }
        }

        onApplication(content) { shell ->
            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { speck.hasEnded },
                "a self-close whose cleanup throws ended nothing",
            )
            val crash = speck.crashOrFail("a self-close whose cleanup throws ended in Ok instead of the crash")
            assertEquals(CLEANUP_FAILURE, crash.failure.cause.message)
        }
    }

    @Test
    fun `content that throws while drawing a later frame ends with the crash, and the process survives`() {
        val probe = runProbe(PROBE_MAIN_CLASS)
        val raw = probe.output.joinToString("\n")

        assertEquals(0, probe.exitCode, "the probe did not exit cleanly; output:\n$raw")
        assertTrue(
            "$PROBE_MARKER crashed=$PROBE_NAMESPACE failure=Composition cause=$PROBE_FAILURE" in probe.output,
            "the later frame's crash was not reported; output:\n$raw",
        )
        assertTrue(
            "$PROBE_MARKER hook crashed=$PROBE_NAMESPACE cause=$PROBE_FAILURE" in probe.output,
            "the later frame's crash never reached the surface's state; output:\n$raw",
        )
    }

    private companion object {
        const val NAMESPACE = "kortex-crash"
        const val DRAW_FAILURE = "content threw while drawing"
        const val CLEANUP_FAILURE = "cleanup threw as the surface closed"
        const val SETTLE_MILLIS = 300L
        const val CLOSE_AFTER_MILLIS = 50L
        const val PUMP_MILLIS = 2_000L
        const val PROBE_MAIN_CLASS = "com.fromwau.kortex.wayland.CrashedSurfaceProbe"
    }
}
