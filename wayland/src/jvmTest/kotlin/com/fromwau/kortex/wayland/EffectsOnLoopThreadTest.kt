package com.fromwau.kortex.wayland

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import com.fromwau.kern.result.Ok
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Two surfaces on one application, each recording the thread its own `LaunchedEffect` runs on once the
 * application has settled, while `System.out` is captured for Compose's `GlobalSnapshotManager` warning.
 */
class EffectsOnLoopThreadTest {
    @Test
    fun `effects under pump run on the pumping thread and GlobalSnapshotManager never warns`() {
        val effects = EffectThreads()
        val pumping = Thread.currentThread()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            Show(TestSurface<Nothing>(FIRST_NAMESPACE) { RecordEffectThread(effects) })
            Show(TestSurface<Nothing>(SECOND_NAMESPACE, anchor = BOTTOM_LEFT) { RecordEffectThread(effects) })
        }

        val printed = capturingStdout {
            onApplication(content) { shell ->
                awaitPlaced(shell, count = 2)

                effects.requested.value = true

                val recorded = shell.pumpOrFail(PUMP_TIMEOUT_MILLIS) { effects.ids.size >= 2 }
                assertTrue(recorded, "not every surface's content ran its LaunchedEffect: ${effects.names}")
            }
        }

        assertEquals(
            setOf(pumping.threadId()), effects.ids.toSet(),
            "the two surfaces' effects did not all run on the thread pumping their application: ${effects.names}",
        )
        assertFalse(
            printed.contains(SNAPSHOT_PUMP_WARNING),
            "GlobalSnapshotManager warned about concurrent registrations",
        )
    }

    @Test
    fun `effects under a real loop run on its thread and GlobalSnapshotManager never warns`() {
        val effects = EffectThreads()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            Show(TestSurface<Nothing>(FIRST_NAMESPACE) { RecordEffectThread(effects) })
            Show(TestSurface<Nothing>(SECOND_NAMESPACE, anchor = BOTTOM_LEFT) { RecordEffectThread(effects) })
        }

        val printed = capturingStdout {
            val result = LoopThread.runApplication(content) { _, loop ->
                assertTrue(
                    LoopThread.awaitNamespace(FIRST_NAMESPACE, present = true),
                    "hyprctl layers never reported $FIRST_NAMESPACE",
                )
                assertTrue(
                    LoopThread.awaitNamespace(SECOND_NAMESPACE, present = true),
                    "hyprctl layers never reported $SECOND_NAMESPACE",
                )

                effects.requested.value = true

                val recorded = LoopThread.waitUntil { effects.ids.size >= 2 }
                assertTrue(recorded, "not every surface's content ran its LaunchedEffect: ${effects.names}")
                assertEquals(
                    setOf(loop.threadId()), effects.ids.toSet(),
                    "the two surfaces' effects did not all run on the application's loop thread: ${effects.names}",
                )
            }
            assertEquals(Ok(Unit), result, "the application did not return Ok on exitApplication")
        }

        assertFalse(
            printed.contains(SNAPSHOT_PUMP_WARNING),
            "GlobalSnapshotManager warned about concurrent registrations",
        )
    }

    private companion object {
        const val FIRST_NAMESPACE = "kortex-loop-thread-first"
        const val SECOND_NAMESPACE = "kortex-loop-thread-second"
        const val PUMP_TIMEOUT_MILLIS = 4000L

        val BOTTOM_LEFT = setOf(Edge.Bottom, Edge.Left)
    }
}

/** The threads each surface's `LaunchedEffect` ran on once [requested] turned true. */
private class EffectThreads {
    // Turned on only once both surfaces are up, so each effect runs again through the snapshot pump and a
    // recomposition rather than inside the first composition's own flush.
    val requested = mutableStateOf(false)

    // Both the id (compared) and the name (reported): a coroutine debug agent suffixes the live thread's own
    // name with "@coroutine#N" per active coroutine, so two effects on the very same thread can carry
    // different names.
    val ids = CopyOnWriteArrayList<Long>()
    val names = CopyOnWriteArrayList<String>()
}

@Composable
private fun RecordEffectThread(effects: EffectThreads) {
    val requested = effects.requested.value
    LaunchedEffect(requested) {
        if (!requested) return@LaunchedEffect
        effects.ids += Thread.currentThread().threadId()
        effects.names += Thread.currentThread().name
    }
}
