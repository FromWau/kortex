package com.fromwau.kortex.wayland

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.getOrElse
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.awaitCancellation

/** What a surface's Compose dispatcher still runs once its surface has closed, and what it no longer does. */
class SceneDispatcherTest {
    @Test
    fun `a dispatch after close neither reaches the loop nor wakes it`() {
        var wakes = 0
        val loop = LoopQueue { wakes++ }
        val dispatcher = SceneDispatcher(loop)
        dispatcher.close()

        var ran = false
        dispatcher.dispatch(EmptyCoroutineContext) { ran = true }
        loop.drain()

        assertFalse(ran, "work dispatched after its surface closed ran on the loop")
        assertEquals(0, wakes, "work dispatched after its surface closed woke the loop")
    }

    @Test
    fun `a closed shell's effects and recomposers have finished cancelling by the time close returns`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }
        val cancelled = AtomicBoolean(false)

        display.use { wayland ->
            val speck = SurfaceSpec(CONFIG, OutputTarget.CompositorChoice) {
                LaunchedEffect(Unit) {
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled.set(true)
                    }
                }
                Box(Modifier.fillMaxSize())
            }
            val before = Recomposer.runningRecomposers.value
            val shell = KortexShell.create(wayland, speck).getOrElse { error -> fail("shell creation failed: $error") }
            val ours = shell.use {
                val up = shell.pump(PUMP_TIMEOUT_MILLIS) { NAMESPACE in Hyprctl.namespaces() }
                assertTrue(up, "hyprctl layers never reported $NAMESPACE")
                Recomposer.runningRecomposers.value - before
            }

            assertEquals(1, ours.size, "the shell's one surface did not run exactly one recomposer")
            assertTrue(cancelled.get(), "an effect's finally had not run by the time its shell's close returned")
            assertTrue(
                Recomposer.runningRecomposers.value.none { it in ours },
                "a closed surface's recomposer was still running after its shell's close returned",
            )
        }
    }

    private companion object {
        const val NAMESPACE = "kortex-scene-dispatcher"
        const val SPECK_SIZE = 8
        const val PUMP_TIMEOUT_MILLIS = 4000L

        val CONFIG = SurfaceConfig(
            namespace = NAMESPACE,
            layer = Layer.Overlay,
            anchor = setOf(Edge.Bottom, Edge.Right),
            width = SPECK_SIZE.dp,
            height = SPECK_SIZE.dp,
            exclusiveZone = ExclusiveZone.Yield,
        )
    }
}
