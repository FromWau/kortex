package com.fromwau.kortex.wayland

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.getOrElse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A surface shown on a chosen [Monitor] appears under that output and no other, and removing the monitor
 * drops the surface. The headless output is created before [WaylandDisplay.connect], so it is one of the
 * outputs the application binds and round-trips before its first composition, the path placing on a
 * monitor at startup depends on, since the compositor has not yet named a freshly bound output otherwise.
 */
@Hotplug
class NamedOutputTest {
    @Test
    fun `a surface shown on a chosen monitor appears under that output and no other`() {
        var pending: String? = null
        try {
            val outputName = Hyprctl.createHeadlessOutput()
            pending = outputName

            val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }
            display.use { wayland ->
                val shell = KortexShell.createApplicationOrFail(wayland, content(outputName))

                shell.useOrFail {
                    val appeared = shell.pumpOrFail(PUMP_TIMEOUT_MILLIS) { namedNamespace() != null }
                    assertTrue(appeared, "hyprctl layers never reported $NAMED_NAMESPACE")

                    assertEquals(
                        setOf(outputName), Hyprctl.monitorsShowing(NAMED_NAMESPACE),
                        "the surface is not under exactly the monitor it was shown on",
                    )
                }
            }
        } finally {
            // Guarantees the virtual output never survives a failed assertion above.
            pending?.let(Hyprctl::removeHeadlessOutput)
        }
    }

    @Test
    fun `removing a surface's monitor drops it and leaves the other panels standing`() {
        var pending: String? = null
        try {
            val outputName = Hyprctl.createHeadlessOutput()
            pending = outputName
            val expectedPanels = Hyprctl.monitorNames().size

            val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }
            display.use { wayland ->
                val shell = KortexShell.createApplicationOrFail(wayland, content(outputName))

                shell.useOrFail {
                    val appeared = shell.pumpOrFail(PUMP_TIMEOUT_MILLIS) { namedNamespace() != null }
                    assertTrue(appeared, "hyprctl layers never reported $NAMED_NAMESPACE")
                    val panelsBefore = awaitPanelCount(expectedPanels)
                    assertEquals(expectedPanels, panelsBefore.size, "expected one panel per connected monitor")

                    Hyprctl.removeHeadlessOutput(outputName)
                    pending = null

                    val dropped = shell.pumpOrFail(PUMP_TIMEOUT_MILLIS) { namedNamespace() == null }
                    assertTrue(dropped, "the surface outlived the monitor it was shown on")
                    assertNull(namedNamespace(), "hyprctl layers still reports $NAMED_NAMESPACE")

                    val remainingPanels = awaitPanelCount(expectedPanels - 1)
                    assertEquals(
                        expectedPanels - 1, remainingPanels.size,
                        "a panel on a remaining monitor disappeared along with the removed one",
                    )
                }
            }
        } finally {
            // Guarantees the virtual output never survives a failed assertion above.
            pending?.let(Hyprctl::removeHeadlessOutput)
        }
    }

    /** A [Panel] per monitor, and an [Osd] shown specifically on [outputName]'s own monitor. */
    private fun content(outputName: String): @Composable KortexApplicationScope.() -> Unit = {
        val monitors by rememberMonitors()
        for (monitor in monitors) {
            key(monitor) {
                Panel(
                    monitor = monitor,
                    edge = Edge.Top,
                    thickness = PANEL_HEIGHT.dp,
                    namespace = "$PANEL_NAMESPACE-${monitor.name}",
                ) {}
            }
        }
        monitors.firstOrNull { it.name == outputName }?.let { monitor ->
            Osd(
                monitor = monitor,
                width = OSD_SIZE.dp,
                height = OSD_SIZE.dp,
                namespace = NAMED_NAMESPACE,
            ) {}
        }
    }

    private fun panelNamespaces(): Set<String> =
        Hyprctl.namespaces().filterTo(mutableSetOf()) { it.startsWith("$PANEL_NAMESPACE-") }

    private fun namedNamespace(): String? = Hyprctl.namespaces().firstOrNull { it == NAMED_NAMESPACE }

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
    }
}
