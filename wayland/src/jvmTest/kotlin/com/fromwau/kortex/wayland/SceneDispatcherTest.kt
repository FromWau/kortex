package com.fromwau.kortex.wayland

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.getOrElse
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** What a surface's Compose dispatcher runs as its surface closes, and what it still runs after. */
class SceneDispatcherTest {
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
        const val BARE_NAMESPACE = "kortex-scene-dispatcher"
        const val FIRST_NAMESPACE = "kortex-scene-dispatcher-first"
        const val SECOND_NAMESPACE = "kortex-scene-dispatcher-second"
        const val SPECK_SIZE = 8
        const val LATE_MILLIS = 300L
        const val PUMP_TIMEOUT_MILLIS = 4000L
    }
}
