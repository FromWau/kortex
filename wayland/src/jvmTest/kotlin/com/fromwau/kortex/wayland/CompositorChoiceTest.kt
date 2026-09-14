package com.fromwau.kortex.wayland

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.getOrElse
import com.fromwau.kortex.compose.LocalKortexSurface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Drives [OutputTarget.CompositorChoice]'s replacement policy against a real compositor, using
 * [KortexSurface.simulateCompositorClose] to stand in for the one event a headless output cannot
 * produce here: the compositor closing the surface that sits on the real, unpluggable monitor.
 */
class CompositorChoiceTest {
    @Test
    fun `the compositor taking a standing CompositorChoice surface away brings it back`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use { wayland ->
            val osd = SurfaceSpec(OSD_CONFIG, OutputTarget.CompositorChoice) { Box(Modifier.fillMaxSize()) }
            val shell = KortexShell.create(wayland, osd).getOrElse { error -> fail("shell creation failed: $error") }

            shell.useOrFail {
                assertTrue(
                    shell.pumpOrFail(PUMP_TIMEOUT_MILLIS) { Screen.geometry(OSD_NAMESPACE) != null },
                    "hyprctl layers never reported $OSD_NAMESPACE",
                )
                val before = shell.activeSurfaces.size

                val standing = assertNotNull(
                    shell.activeSurfaces.firstOrNull { it.spec.target == OutputTarget.CompositorChoice },
                    "no standing CompositorChoice surface to close",
                )
                val original = standing.surface
                original.simulateCompositorClose()

                // Identity, not just count: removal and replacement can land in the same tick, so the
                // count never visibly dips, and a same-count check would pass even if nothing replaced it.
                val replaced = shell.pumpOrFail(PUMP_TIMEOUT_MILLIS) {
                    shell.activeSurfaces
                        .firstOrNull { it.spec.target == OutputTarget.CompositorChoice }
                        ?.surface
                        ?.let { it !== original } ?: false
                }
                assertTrue(replaced, "the shell never replaced the surface the compositor took away")
                assertEquals(before, shell.activeSurfaces.size, "replacement left a different surface count")
                assertTrue(
                    shell.pumpOrFail(PUMP_TIMEOUT_MILLIS) { Screen.geometry(OSD_NAMESPACE) != null },
                    "hyprctl layers never reported $OSD_NAMESPACE again after replacement",
                )
            }
        }
    }

    @Test
    fun `content closing a CompositorChoice surface stays gone`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }
        val closeRequested = mutableStateOf(false)

        display.use { wayland ->
            val osd = SurfaceSpec(OSD_CONFIG, OutputTarget.CompositorChoice) {
                val surface = LocalKortexSurface.current
                val requested = closeRequested.value
                LaunchedEffect(requested) { if (requested) surface.close() }
                Box(Modifier.fillMaxSize())
            }
            val shell = KortexShell.create(wayland, osd).getOrElse { error -> fail("shell creation failed: $error") }

            shell.useOrFail {
                assertTrue(
                    shell.pumpOrFail(PUMP_TIMEOUT_MILLIS) { Screen.geometry(OSD_NAMESPACE) != null },
                    "hyprctl layers never reported $OSD_NAMESPACE",
                )

                // Nothing on the test thread calls close(): only this flag can drop the OSD, and it does
                // so from the composition's own thread.
                closeRequested.value = true
                // Removal and any replacement both happen inside the same serviceSurfaces() tick (see
                // KortexShell), so this single wait already proves no replacement followed the removal.
                val dropped = shell.pumpOrFail(PUMP_TIMEOUT_MILLIS) { shell.activeSurfaces.isEmpty() }
                assertTrue(dropped, "the shell never dropped the surface after its content called close()")
                assertTrue(
                    Screen.geometry(OSD_NAMESPACE) == null,
                    "hyprctl layers still reports $OSD_NAMESPACE after content closed it",
                )
            }
        }
    }

    @Test
    fun `an opened CompositorChoice surface is not replaced when the compositor takes it away`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }
        val openRequested = mutableStateOf(false)

        display.use { wayland ->
            val panel = SurfaceSpec(PANEL_CONFIG) {
                val host = LocalKortexHost.current
                val requested = openRequested.value
                LaunchedEffect(requested) { if (requested) host.open(openedOsdSpec()) }
                Box(Modifier.fillMaxSize())
            }
            val shell = KortexShell.create(wayland, panel)
                .getOrElse { error -> fail("shell creation failed: $error") }

            shell.useOrFail {
                assertTrue(
                    shell.pumpOrFail(PUMP_TIMEOUT_MILLIS) { panelNamespaces().isNotEmpty() },
                    "hyprctl layers never reported a $PANEL_NAMESPACE- namespace",
                )
                val panelsAlone = shell.activeSurfaces.size

                openRequested.value = true
                assertTrue(
                    shell.pumpOrFail(PUMP_TIMEOUT_MILLIS) { Screen.geometry(OPENED_NAMESPACE) != null },
                    "hyprctl layers never reported $OPENED_NAMESPACE after host.open()",
                )

                val opened = assertNotNull(
                    shell.activeSurfaces.firstOrNull { it.spec.config.namespace == OPENED_NAMESPACE },
                    "the opened surface is not among the shell's active surfaces",
                )
                opened.surface.simulateCompositorClose()

                // Removal and any replacement both happen inside the same serviceSurfaces() tick (see
                // KortexShell), so this single wait already proves the opened surface was not replaced.
                val droppedToPanelAlone =
                    shell.pumpOrFail(PUMP_TIMEOUT_MILLIS) { shell.activeSurfaces.size == panelsAlone }
                assertTrue(droppedToPanelAlone, "the opened surface the compositor took away never went away")
                assertTrue(
                    Screen.geometry(OPENED_NAMESPACE) == null,
                    "hyprctl layers still reports $OPENED_NAMESPACE after the compositor took it away",
                )
            }
        }
    }

    private fun openedOsdSpec(): SurfaceSpec =
        SurfaceSpec(OPENED_CONFIG, OutputTarget.CompositorChoice) { Box(Modifier.fillMaxSize()) }

    private fun panelNamespaces(): Set<String> =
        Hyprctl.namespaces().filterTo(mutableSetOf()) { it.startsWith("$PANEL_NAMESPACE-") }

    private companion object {
        const val OSD_NAMESPACE = "kortex-compositorchoice-osd"
        const val PANEL_NAMESPACE = "kortex-compositorchoice-panel"
        const val OPENED_NAMESPACE = "kortex-compositorchoice-opened"

        const val OSD_SIZE = 96
        const val PANEL_HEIGHT = 24
        const val OPENED_SIZE = 64

        const val PUMP_TIMEOUT_MILLIS = 4000L

        val OSD_CONFIG = SurfaceConfig.osd(OSD_SIZE.dp, OSD_SIZE.dp).copy(namespace = OSD_NAMESPACE)
        val PANEL_CONFIG = SurfaceConfig.panel(Edge.Top, PANEL_HEIGHT.dp).copy(namespace = PANEL_NAMESPACE)
        val OPENED_CONFIG = SurfaceConfig.osd(OPENED_SIZE.dp, OPENED_SIZE.dp).copy(namespace = OPENED_NAMESPACE)
    }
}
