package com.fromwau.kortex.wayland

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.getOrElse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Aims a surface at a chosen output with [OutputTarget.NamedOutput], against a real compositor.
 *
 * The headless output is created before [WaylandDisplay.connect], so it is one of the outputs
 * [KortexShell.create] binds and rounds-trips before placing anything, the path a `NamedOutput` at
 * startup depends on, since the compositor has not yet named a freshly bound output otherwise.
 */
class NamedOutputTest {
    @Test
    fun `a surface named for a chosen output appears under that output and no other`() {
        var pending: String? = null
        try {
            val outputName = Hyprctl.createHeadlessOutput()
            pending = outputName

            val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }
            display.use { wayland ->
                // Named first: after a panel, its own dispatch could mask a missing roundtrip by coincidence.
                val shell = KortexShell.create(wayland, namedSpec(outputName), panelSpec())
                    .getOrElse { error -> fail("shell creation failed: $error") }

                shell.use {
                    val appeared = shell.pump(PUMP_TIMEOUT_MILLIS) { namedNamespace() != null }
                    assertTrue(appeared, "hyprctl layers never reported a $NAMED_NAMESPACE- namespace")

                    val namespace = assertNotNull(namedNamespace(), "the named surface's namespace vanished mid-check")
                    assertEquals(
                        setOf(outputName), monitorsShowing(namespace),
                        "the named surface is not under exactly the output it named",
                    )
                }
            }
        } finally {
            // Guarantees the virtual output never survives a failed assertion above.
            pending?.let(Hyprctl::removeHeadlessOutput)
        }
    }

    @Test
    fun `removing the named output drops its surface and leaves the other panels standing`() {
        var pending: String? = null
        try {
            val outputName = Hyprctl.createHeadlessOutput()
            pending = outputName
            val expectedPanels = Hyprctl.monitorNames().size

            val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }
            display.use { wayland ->
                // Named first: after a panel, its own dispatch could mask a missing roundtrip by coincidence.
                val shell = KortexShell.create(wayland, namedSpec(outputName), panelSpec())
                    .getOrElse { error -> fail("shell creation failed: $error") }

                shell.use {
                    val appeared = shell.pump(PUMP_TIMEOUT_MILLIS) { namedNamespace() != null }
                    assertTrue(appeared, "hyprctl layers never reported a $NAMED_NAMESPACE- namespace")
                    val panelsBefore = awaitPanelCount(expectedPanels)
                    assertEquals(expectedPanels, panelsBefore.size, "expected one panel per connected output")

                    Hyprctl.removeHeadlessOutput(outputName)
                    pending = null

                    val dropped = shell.pump(PUMP_TIMEOUT_MILLIS) { namedNamespace() == null }
                    assertTrue(dropped, "the named surface outlived the output it was placed on")
                    assertNull(namedNamespace(), "hyprctl layers still reports a $NAMED_NAMESPACE- namespace")

                    val remainingPanels = awaitPanelCount(expectedPanels - 1)
                    assertEquals(
                        expectedPanels - 1, remainingPanels.size,
                        "a panel on a remaining output disappeared along with the named surface's own output",
                    )
                }
            }
        } finally {
            // Guarantees the virtual output never survives a failed assertion above.
            pending?.let(Hyprctl::removeHeadlessOutput)
        }
    }

    private fun panelSpec(): SurfaceSpec =
        SurfaceSpec(PANEL_CONFIG) { Box(Modifier.fillMaxSize()) }

    private fun namedSpec(name: String): SurfaceSpec =
        SurfaceSpec(NAMED_CONFIG, OutputTarget.NamedOutput(name)) { Box(Modifier.fillMaxSize()) }

    /** Every monitor `hyprctl layers -j` reports [namespace] under. */
    private fun monitorsShowing(namespace: String): Set<String> = Hyprctl.layers()
        .filterValues { layers -> layers.levels.values.flatten().any { it.namespace == namespace } }
        .keys

    private fun panelNamespaces(): Set<String> =
        Hyprctl.namespaces().filterTo(mutableSetOf()) { it.startsWith("$PANEL_NAMESPACE-") }

    // A per-output surface's namespace carries the output's registry id as a suffix, like any other.
    private fun namedNamespace(): String? = Hyprctl.namespaces().firstOrNull { it.startsWith("$NAMED_NAMESPACE-") }

    /** Polls `hyprctl layers -j` until it reports [count] panel namespaces, since it lags the shell's own state. */
    private fun awaitPanelCount(count: Int): Set<String> {
        val deadline = System.nanoTime() + HYPRCTL_SETTLE_MILLIS * NANOS_PER_MILLI
        var namespaces = panelNamespaces()
        while (namespaces.size != count && System.nanoTime() < deadline) {
            Thread.sleep(HYPRCTL_POLL_MILLIS)
            namespaces = panelNamespaces()
        }
        return namespaces
    }

    private companion object {
        const val PANEL_NAMESPACE = "kortex-named-panel"
        const val NAMED_NAMESPACE = "kortex-named-target"
        const val PANEL_HEIGHT = 24
        const val OSD_SIZE = 96

        const val PUMP_TIMEOUT_MILLIS = 4000L
        const val HYPRCTL_SETTLE_MILLIS = 2000L
        const val HYPRCTL_POLL_MILLIS = 100L
        const val NANOS_PER_MILLI = 1_000_000L

        val PANEL_CONFIG = SurfaceConfig.panel(Edge.Top, PANEL_HEIGHT.dp).copy(namespace = PANEL_NAMESPACE)
        val NAMED_CONFIG = SurfaceConfig.osd(OSD_SIZE.dp, OSD_SIZE.dp).copy(namespace = NAMED_NAMESPACE)
    }
}
