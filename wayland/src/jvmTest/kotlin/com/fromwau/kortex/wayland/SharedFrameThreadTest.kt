package com.fromwau.kortex.wayland

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.getOrElse
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A panel and an OSD on one shell, each recording the thread its own `LaunchedEffect` runs on once the
 * shell has settled, while `System.out` is captured for Compose's `GlobalSnapshotManager` warning.
 */
class SharedFrameThreadTest {
    @Test
    fun `effects under pump run on the pumping thread and GlobalSnapshotManager never warns`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }
        val effects = EffectThreads()
        val pumping = Thread.currentThread()

        val printed = capturingStdout {
            display.use { wayland ->
                val shell = KortexShell.create(wayland, panel(effects), osd(effects))
                    .getOrElse { error -> fail("shell creation failed: $error") }

                shell.use {
                    val panelUp = shell.pump(PUMP_TIMEOUT_MILLIS) { panelNamespaces().isNotEmpty() }
                    assertTrue(panelUp, "hyprctl layers never reported a $PANEL_NAMESPACE- namespace")
                    val osdUp = shell.pump(PUMP_TIMEOUT_MILLIS) { Screen.geometry(OSD_NAMESPACE) != null }
                    assertTrue(osdUp, "hyprctl layers never reported $OSD_NAMESPACE")

                    effects.requested.value = true

                    val recorded = shell.pump(PUMP_TIMEOUT_MILLIS) { effects.ids.size >= 2 }
                    assertTrue(recorded, "not every surface's content ran its LaunchedEffect: ${effects.names}")
                }
            }
        }

        assertEquals(
            setOf(pumping.threadId()), effects.ids.toSet(),
            "the panel's and the OSD's effects did not all run on the thread pumping their shell: ${effects.names}",
        )
        assertFalse(printed.contains(WARNING), "GlobalSnapshotManager warned about concurrent registrations")
    }

    @Test
    fun `effects under a real loop run on its thread and GlobalSnapshotManager never warns`() {
        val effects = EffectThreads()
        val closeRequested = mutableStateOf(false)

        val printed = capturingStdout {
            LoopThread.run(
                panel(effects, closeRequested),
                osd(effects, closeRequested),
                end = { closeRequested.value = true },
            ) { _, loop ->
                val panelUp = LoopThread.waitUntil { panelNamespaces().isNotEmpty() }
                assertTrue(panelUp, "hyprctl layers never reported a $PANEL_NAMESPACE- namespace")
                val osdUp = LoopThread.awaitNamespace(OSD_NAMESPACE, present = true)
                assertTrue(osdUp, "hyprctl layers never reported $OSD_NAMESPACE")

                effects.requested.value = true

                val recorded = LoopThread.waitUntil { effects.ids.size >= 2 }
                assertTrue(recorded, "not every surface's content ran its LaunchedEffect: ${effects.names}")
                assertEquals(
                    setOf(loop.threadId()), effects.ids.toSet(),
                    "the panel's and the OSD's effects did not all run on their shell's loop thread: ${effects.names}",
                )
            }
        }

        assertFalse(printed.contains(WARNING), "GlobalSnapshotManager warned about concurrent registrations")
    }

    private fun panel(
        effects: EffectThreads,
        closeRequested: MutableState<Boolean> = mutableStateOf(false),
    ): SurfaceSpec = SurfaceSpec(PANEL_CONFIG) {
        RecordEffectThread(effects)
        CloseWhen(closeRequested)
        Box(Modifier.fillMaxSize())
    }

    private fun osd(
        effects: EffectThreads,
        closeRequested: MutableState<Boolean> = mutableStateOf(false),
    ): SurfaceSpec = SurfaceSpec(OSD_CONFIG, OutputTarget.CompositorChoice) {
        RecordEffectThread(effects)
        CloseWhen(closeRequested)
        Box(Modifier.fillMaxSize())
    }

    private fun panelNamespaces(): Set<String> =
        Hyprctl.namespaces().filterTo(mutableSetOf()) { it.startsWith("$PANEL_NAMESPACE-") }

    /** Runs [block] with `System.out` captured, and returns what was printed there meanwhile. */
    private fun capturingStdout(block: () -> Unit): String {
        val captured = ByteArrayOutputStream()
        val realOut = System.out
        System.setOut(PrintStream(captured))
        try {
            block()
        } finally {
            System.setOut(realOut)
        }
        return captured.toString()
    }

    private companion object {
        const val WARNING = "GlobalSnapshotManager: concurrent registrations"
        const val PANEL_NAMESPACE = "kortex-shared-frame-panel"
        const val OSD_NAMESPACE = "kortex-shared-frame-osd"
        const val PANEL_HEIGHT = 24
        const val OSD_WIDTH = 64
        const val OSD_HEIGHT = 64
        const val PUMP_TIMEOUT_MILLIS = 4000L

        val PANEL_CONFIG = SurfaceConfig.panel(Edge.Top, PANEL_HEIGHT.dp).copy(namespace = PANEL_NAMESPACE)
        val OSD_CONFIG = SurfaceConfig.osd(OSD_WIDTH.dp, OSD_HEIGHT.dp).copy(namespace = OSD_NAMESPACE)
    }
}

/** The threads each surface's `LaunchedEffect` ran on once [requested] turned true. */
private class EffectThreads {
    // Turned on only once both surfaces are up: a first composition's effects start on whichever thread
    // creates the shell, and these tests are about the thread that drives it.
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
