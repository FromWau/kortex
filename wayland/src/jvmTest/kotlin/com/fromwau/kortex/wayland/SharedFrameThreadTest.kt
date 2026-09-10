package com.fromwau.kortex.wayland

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
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
 * A shell running a panel and an OSD against a real compositor, each recording the thread its own
 * `LaunchedEffect` runs on once the shell has already settled. One shared `kortex-frame` thread across
 * both surfaces is what keeps Compose's `GlobalSnapshotManager` from ever seeing more than one
 * registered thread.
 */
class SharedFrameThreadTest {
    @Test
    fun `a panel and an OSD share one kortex-frame thread and GlobalSnapshotManager never warns`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }
        val recordRequested = mutableStateOf(false)
        // Both the id (compared) and the name (reported): a coroutine debug agent suffixes the live
        // thread's own name with "@coroutine#N" per active coroutine, so two effects on the very same
        // thread can carry different names.
        val threadIds = CopyOnWriteArrayList<Long>()
        val threadNames = CopyOnWriteArrayList<String>()

        display.use { wayland ->
            val panel = SurfaceSpec(PANEL_CONFIG) {
                val requested = recordRequested.value
                LaunchedEffect(requested) {
                    if (!requested) return@LaunchedEffect
                    threadIds += Thread.currentThread().threadId()
                    threadNames += Thread.currentThread().name
                }
                Box(Modifier.fillMaxSize())
            }
            val osd = SurfaceSpec(OSD_CONFIG, OutputTarget.CompositorChoice) {
                val requested = recordRequested.value
                LaunchedEffect(requested) {
                    if (!requested) return@LaunchedEffect
                    threadIds += Thread.currentThread().threadId()
                    threadNames += Thread.currentThread().name
                }
                Box(Modifier.fillMaxSize())
            }

            val captured = ByteArrayOutputStream()
            val realOut = System.out
            System.setOut(PrintStream(captured))
            try {
                val shell = KortexShell.create(wayland, panel, osd)
                    .getOrElse { error -> fail("shell creation failed: $error") }

                shell.use {
                    val panelUp = shell.pump(PUMP_TIMEOUT_MILLIS) { panelNamespaces().isNotEmpty() }
                    assertTrue(panelUp, "hyprctl layers never reported a $PANEL_NAMESPACE- namespace")
                    val osdUp = shell.pump(PUMP_TIMEOUT_MILLIS) { Screen.geometry(OSD_NAMESPACE) != null }
                    assertTrue(osdUp, "hyprctl layers never reported $OSD_NAMESPACE")

                    // Only after both surfaces have already rendered once: recording here, rather than
                    // from the very first composition, gives each effect's dispatch a real compositor
                    // round trip ahead of it instead of racing the render setContent() triggers itself.
                    recordRequested.value = true

                    val recorded = shell.pump(PUMP_TIMEOUT_MILLIS) { threadIds.size >= 2 }
                    assertTrue(recorded, "not every surface's content ran its LaunchedEffect: $threadNames")
                }
            } finally {
                System.setOut(realOut)
            }

            assertEquals(
                1, threadIds.toSet().size,
                "the panel and the OSD did not share one frame thread: $threadNames",
            )
            assertFalse(
                captured.toString().contains(WARNING),
                "GlobalSnapshotManager warned about concurrent registrations",
            )
        }
    }

    private fun panelNamespaces(): Set<String> =
        Hyprctl.namespaces().filterTo(mutableSetOf()) { it.startsWith("$PANEL_NAMESPACE-") }

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
