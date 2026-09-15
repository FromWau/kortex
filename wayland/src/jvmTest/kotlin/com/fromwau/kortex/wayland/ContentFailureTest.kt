package com.fromwau.kortex.wayland

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.EmptyResult
import com.fromwau.kortex.compose.ContentFailure
import com.fromwau.kortex.compose.LocalKortexSurface
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.delay

/** Content that throws reaches its `onClose` as a [KortexError.SurfaceCrashed], and the run goes on. */
class ContentFailureTest {
    @Test
    fun `content that throws on its first frame reports the crash, and the run goes on`() {
        val reports = CopyOnWriteArrayList<EmptyResult<SurfaceError<Nothing>>>()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            Show(
                TestSurface<Nothing>(NAMESPACE, onClose = { reports += it }) {
                    Canvas(Modifier.fillMaxSize()) { error(DRAW_FAILURE) }
                },
            )
        }

        onApplication(content) { shell ->
            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { reports.isNotEmpty() },
                "a first frame that throws reported nothing",
            )
            val crash = crashIn(reports.single(), "a first frame that throws did not report Failed(SurfaceCrashed)")
            assertEquals(NAMESPACE, crash.namespace)
            assertIs<ContentFailure.Composition>(crash.failure)
            assertEquals(DRAW_FAILURE, crash.failure.cause.message)
            // A run that ended would fail this pump with an Err; letting it settle proves the crash did not end it.
            shell.pumpOrFail(SETTLE_MILLIS)
        }
    }

    @Test
    fun `content that closes itself and whose cleanup then throws reports the crash, not Ok`() {
        val reports = CopyOnWriteArrayList<EmptyResult<SurfaceError<Nothing>>>()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            Show(
                TestSurface<Nothing>(NAMESPACE, onClose = { reports += it }) {
                    val surface = LocalKortexSurface.current
                    DisposableEffect(Unit) { onDispose { error(CLEANUP_FAILURE) } }
                    LaunchedEffect(Unit) {
                        delay(CLOSE_AFTER_MILLIS)
                        surface.close()
                    }
                },
            )
        }

        onApplication(content) { shell ->
            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { reports.isNotEmpty() },
                "a self-close whose cleanup throws reported nothing",
            )
            val crash = crashIn(
                reports.single(),
                "a self-close whose cleanup throws reported Ok instead of the crash",
            )
            assertEquals(CLEANUP_FAILURE, crash.failure.cause.message)
        }
    }

    @Test
    fun `a replacement's content that throws on its first frame reports the crash, and nothing takes its place`() {
        val height = mutableIntStateOf(SHORT)
        val reports = CopyOnWriteArrayList<EmptyResult<SurfaceError<Nothing>>>()
        val placements = AtomicInteger()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            Show(
                TestSurface<Nothing>(NAMESPACE, height = height.intValue.dp, onClose = { reports += it }) {
                    val placement = remember { placements.incrementAndGet() }
                    Canvas(Modifier.fillMaxSize()) { if (placement > 1) error(DRAW_FAILURE) }
                },
            )
        }

        onApplication(content) { shell ->
            awaitPlaced(shell)

            height.intValue = TALL

            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { reports.isNotEmpty() },
                "a replacement that throws on its first frame reported nothing",
            )
            val crash = crashIn(
                reports.single(),
                "a replacement that throws on its first frame did not report the crash",
            )
            assertEquals(DRAW_FAILURE, crash.failure.cause.message)
            shell.pumpOrFail(SETTLE_MILLIS)
            assertTrue(shell.shownSurfaces.isEmpty(), "a replacement whose content crashed was placed anyway")
        }
    }

    @Test
    fun `content that throws while drawing a later frame reports the crash, and the process survives`() {
        val probe = runProbe(PROBE_MAIN_CLASS)
        val raw = probe.output.joinToString("\n")

        assertEquals(0, probe.exitCode, "the probe did not exit cleanly; output:\n$raw")
        assertTrue(
            "$PROBE_MARKER crashed=$PROBE_NAMESPACE failure=Composition cause=$PROBE_FAILURE" in probe.output,
            "the later frame's crash was not reported; output:\n$raw",
        )
        assertTrue(
            "$PROBE_MARKER hook crashed=$PROBE_NAMESPACE cause=$PROBE_FAILURE" in probe.output,
            "the later frame's crash never reached onClose; output:\n$raw",
        )
    }

    private companion object {
        const val NAMESPACE = "kortex-crash"
        const val DRAW_FAILURE = "content threw while drawing"
        const val CLEANUP_FAILURE = "cleanup threw as the surface closed"
        const val SETTLE_MILLIS = 300L
        const val CLOSE_AFTER_MILLIS = 50L
        const val PUMP_MILLIS = 2_000L
        const val SHORT = 8
        const val TALL = 16
        const val PROBE_MAIN_CLASS = "com.fromwau.kortex.wayland.CrashedSurfaceProbe"
    }
}
