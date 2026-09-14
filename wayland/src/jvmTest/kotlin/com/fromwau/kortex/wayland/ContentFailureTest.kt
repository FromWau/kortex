package com.fromwau.kortex.wayland

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.errorOrNull
import com.fromwau.kern.result.getOrElse
import com.fromwau.kern.result.onSuccess
import com.fromwau.kortex.compose.ContentFailure
import com.fromwau.kortex.compose.LocalKortexSurface
import kotlinx.coroutines.delay
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** Content that throws reaches the host as a [KortexError.SurfaceCrashed], wherever kortex was running it. */
class ContentFailureTest {
    @Test
    fun `content that throws while drawing its first frame fails the shell's creation`() {
        val reported = CopyOnWriteArrayList<KortexError.SurfaceCrashed>()
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }
        display.use {
            val spec = crashingSpec { Canvas(Modifier.fillMaxSize()) { error(DRAW_FAILURE) } }
            val error = KortexShell.create(display, spec, onCrashSurface = { reported += it })
                .onSuccess { it.close() }
                .errorOrNull()

            val crash = assertIs<KortexError.SurfaceCrashed>(
                error,
                "a first frame that throws must fail the shell's creation",
            )
            assertEquals(NAMESPACE, crash.namespace)
            assertIs<ContentFailure.Composition>(crash.failure)
            assertEquals(DRAW_FAILURE, crash.failure.cause.message)
            assertEquals(listOf(crash), reported, "the first frame's crash must reach onCrashSurface exactly once")
        }
    }

    @Test
    fun `an effect that throws ends the run with the surface it crashed`() {
        val reported = CopyOnWriteArrayList<KortexError.SurfaceCrashed>()
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }
        display.use {
            val shell = KortexShell
                .create(display, crashingSpec { ThrowingEffect() }, onCrashSurface = { reported += it })
                .getOrElse { error -> fail("shell creation failed: $error") }
            val error = shell.pump(PUMP_MILLIS).errorOrNull()
            shell.close()

            val crash = assertIs<KortexError.SurfaceCrashed>(error, "an effect that throws must end the run")
            assertEquals(NAMESPACE, crash.namespace)
            assertIs<ContentFailure.Composition>(crash.failure)
            assertEquals(EFFECT_FAILURE, crash.failure.cause.message)
            assertEquals(listOf(crash), reported, "the crash that ended the run must reach onCrashSurface exactly once")
        }
    }

    @Test
    fun `content whose cleanup throws as the shell closes fails the close and reaches onCrashSurface`() {
        val reported = CopyOnWriteArrayList<KortexError.SurfaceCrashed>()
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }
        display.use {
            val shell = KortexShell
                .create(display, crashingSpec { ThrowingOnClose() }, onCrashSurface = { reported += it })
                .getOrElse { error -> fail("shell creation failed: $error") }
            assertNull(
                shell.pump(SETTLE_MILLIS).errorOrNull(),
                "content that only throws in its cleanup must keep running",
            )

            val crash = assertIs<KortexError.SurfaceCrashed>(
                shell.close().errorOrNull(),
                "cleanup that throws as the shell closes must fail the close",
            )
            assertEquals(SHELL_CLOSE_FAILURE, crash.failure.cause.message)
            assertEquals(listOf(crash), reported, "the crash while closing must reach onCrashSurface exactly once")
        }
    }

    @Test
    fun `a replacement whose content throws ends the run`() {
        val placements = AtomicInteger()
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }
        display.use {
            val shell = KortexShell.create(display, crashingSpec { ThrowingOnReplacement(placements) })
                .getOrElse { error -> fail("shell creation failed: $error") }
            shell.useOrFail {
                shell.activeSurfaces.single().surface.simulateCompositorClose()
                val error = shell.pump(PUMP_MILLIS).errorOrNull()

                val crash = assertIs<KortexError.SurfaceCrashed>(error, "a replacement that throws must end the run")
                assertEquals(DRAW_FAILURE, crash.failure.cause.message)
            }
        }
    }

    @Test
    fun `content whose cleanup throws as its surface closes ends the run`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }
        display.use {
            val shell = KortexShell.create(display, crashingSpec { ThrowingCleanup() })
                .getOrElse { error -> fail("shell creation failed: $error") }
            shell.useOrFail {
                val error = shell.pump(PUMP_MILLIS).errorOrNull()

                val crash = assertIs<KortexError.SurfaceCrashed>(
                    error,
                    "cleanup that throws as its surface closes must end the run",
                )
                assertEquals(CLEANUP_FAILURE, crash.failure.cause.message)
            }
        }
    }

    @Test
    fun `content that throws while drawing a later frame ends the run, not the process`() {
        val probe = runProbe(PROBE_MAIN_CLASS)
        val raw = probe.output.joinToString("\n")

        assertEquals(0, probe.exitCode, "the probe did not exit cleanly; output:\n$raw")
        assertTrue(
            "$PROBE_MARKER crashed=$PROBE_NAMESPACE failure=Composition cause=$PROBE_FAILURE" in probe.output,
            "the run did not end in the later frame's crash; output:\n$raw",
        )
        assertTrue(
            "$PROBE_MARKER hook crashed=$PROBE_NAMESPACE cause=$PROBE_FAILURE" in probe.output,
            "the later frame's crash never reached onCrashSurface; output:\n$raw",
        )
    }

    private fun crashingSpec(content: @Composable () -> Unit): SurfaceSpec =
        SurfaceSpec(SPECK_CONFIG, OutputTarget.CompositorChoice, content)

    @Composable
    private fun ThrowingOnReplacement(placements: AtomicInteger) {
        val placement = remember { placements.incrementAndGet() }
        Canvas(Modifier.fillMaxSize()) { if (placement > 1) error(DRAW_FAILURE) }
    }

    @Composable
    private fun ThrowingCleanup() {
        val handle = LocalKortexSurface.current
        DisposableEffect(Unit) { onDispose { error(CLEANUP_FAILURE) } }
        LaunchedEffect(Unit) {
            delay(CLOSE_AFTER_MILLIS)
            handle.close()
        }
    }

    @Composable
    private fun ThrowingOnClose() {
        DisposableEffect(Unit) { onDispose { error(SHELL_CLOSE_FAILURE) } }
    }

    @Composable
    private fun ThrowingEffect() {
        LaunchedEffect(Unit) {
            delay(EFFECT_DELAY_MILLIS)
            error(EFFECT_FAILURE)
        }
    }

    private companion object {
        const val NAMESPACE = "kortex-crash"
        const val DRAW_FAILURE = "content threw while drawing"
        const val EFFECT_FAILURE = "an effect threw"
        const val CLEANUP_FAILURE = "cleanup threw as the surface closed"
        const val SHELL_CLOSE_FAILURE = "cleanup threw as the shell closed"
        const val SETTLE_MILLIS = 300L
        const val EFFECT_DELAY_MILLIS = 50L
        const val CLOSE_AFTER_MILLIS = 50L
        const val PUMP_MILLIS = 2_000L
        const val PROBE_MAIN_CLASS = "com.fromwau.kortex.wayland.CrashedSurfaceProbe"

        // A speck in the corner, where the pointer is least likely to be.
        val SPECK_CONFIG = SurfaceConfig(
            layer = Layer.Overlay,
            anchor = setOf(Edge.Bottom, Edge.Right),
            width = 8.dp,
            height = 8.dp,
            exclusiveZone = ExclusiveZone.Yield,
        ).copy(namespace = NAMESPACE)
    }
}
