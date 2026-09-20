package com.fromwau.kortex.wayland

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.getOrElse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Runs two unrelated surfaces on one application: a [Panel] per monitor that reserves screen space, and one
 * compositor-placed [Osd] floating over it that reserves none. Each carries its own layer, size and content,
 * so an application that let one surface's settings or composition reach the other's shows up here.
 */
class MultiSurfaceTest {
    @Test
    fun `a panel and an OSD keep their own level, size and content, and only the panel reserves space`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }
        val before = Hyprctl.monitors().associateBy(HyprMonitor::name)

        display.use { wayland ->
            val shell = KortexShell.createApplicationOrFail(wayland, multiSurfaceContent())

            shell.useOrFail {
                awaitPanel(shell)
                val osd = awaitOsd(shell)
                val panelNamespace = assertNotNull(
                    panelNamespaces().firstOrNull { Screen.geometry(it)?.monitor == osd.monitor },
                    "no panel on ${osd.monitor}, the monitor the OSD landed on",
                )
                val panel = assertNotNull(Screen.geometry(panelNamespace))

                assertEquals(Layer.Top, panel.layer, "the panel is not on the layer its own settings name")
                assertEquals(Layer.Overlay, osd.layer, "the OSD is not on the layer its own settings name")
                assertEquals(PANEL_HEIGHT, panel.logicalHeight, "the panel is not the height its own settings ask for")
                assertEquals(OSD_HEIGHT, osd.logicalHeight, "the OSD is not the height its own settings ask for")
                assertEquals(OSD_WIDTH, osd.logicalWidth, "the OSD is not the width its own settings ask for")

                val was = assertNotNull(before[osd.monitor], "hyprctl did not report ${osd.monitor} before the shell")
                val now = awaitUsableTop(osd.monitor, was.usableY + PANEL_HEIGHT)
                assertEquals(
                    was.usableY + PANEL_HEIGHT, now.usableY,
                    "the monitor did not reserve exactly the panel's exclusive zone and nothing for the OSD",
                )

                // Hyprland centres a surface in what the exclusive zones leave, rounding a half pixel up.
                assertEquals(
                    now.usableX + (now.usableWidth - OSD_WIDTH + 1) / 2, osd.x,
                    "the OSD is not centred across the space left to it",
                )
                assertEquals(
                    now.usableY + (now.usableHeight - OSD_HEIGHT + 1) / 2, osd.y,
                    "the OSD is not centred down the space left to it",
                )

                assertEquals(PANEL_PIXEL, Screen.pixelReaching(panel, PANEL_PIXEL), "the panel is not showing its own content")
                assertEquals(OSD_PIXEL, Screen.pixelReaching(osd, OSD_PIXEL), "the OSD is not showing its own content")
            }
        }
    }

    @Test
    fun `an OSD that closes itself leaves the panel on screen`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }
        val closeRequested = mutableStateOf(false)

        display.use { wayland ->
            val shell = KortexShell.createApplicationOrFail(wayland, multiSurfaceContent(closeRequested))

            shell.useOrFail {
                val panelNamespace = awaitPanel(shell)
                awaitOsd(shell)
                val panelsAlone = shell.shownSurfaces.size - 1

                // Nothing on the test thread ends the OSD: only this flag can drop it, and it does so from the
                // composition's own thread.
                closeRequested.value = true

                val dropped = shell.pumpOrFail(PUMP_TIMEOUT_MILLIS) { shell.shownSurfaces.size == panelsAlone }
                assertTrue(dropped, "the application never dropped the OSD after its content closed it")
                assertTrue(
                    shell.pumpOrFail(PUMP_TIMEOUT_MILLIS) { Screen.geometry(OSD_NAMESPACE) == null },
                    "hyprctl layers still reports $OSD_NAMESPACE after it closed itself",
                )

                val panel = assertNotNull(Screen.geometry(panelNamespace), "the panel went away with the OSD")
                assertEquals(PANEL_PIXEL, Screen.pixelReaching(panel, PANEL_PIXEL), "the panel stopped showing its content")
            }
        }
    }

    @Hotplug
    @Test
    fun `a hotplugged output grows the per-monitor panel and nothing else`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use { wayland ->
            val shell = KortexShell.createApplicationOrFail(wayland, multiSurfaceContent())

            shell.useOrFail {
                awaitPanel(shell)
                awaitOsd(shell)
                val panelsBefore = panelNamespaces().size

                var pending: String? = null
                try {
                    pending = Hyprctl.createHeadlessOutput()

                    val panels = awaitPanelCount(panelsBefore + 1)
                    assertEquals(
                        panelsBefore + 1, panels.size,
                        "expected one panel namespace per monitor, got $panels",
                    )
                    assertEquals(1, osdCount(), "the compositor-placed OSD was placed a second time")

                    Hyprctl.removeHeadlessOutput(pending)
                    pending = null

                    val shrank = shell.pumpOrFail(PUMP_TIMEOUT_MILLIS) { panelNamespaces().size == panelsBefore }
                    assertTrue(shrank, "the removed output's panel outlived it")
                    assertEquals(1, osdCount(), "the compositor-placed OSD went with the removed output")
                } finally {
                    // Guarantees the virtual output never survives a failed assertion above.
                    pending?.let(Hyprctl::removeHeadlessOutput)
                }
            }
        }
    }

    /**
     * A panel per monitor, and a compositor-placed OSD over it; the OSD closes itself once [osdClosed] turns
     * true.
     */
    private fun multiSurfaceContent(
        osdClosed: MutableState<Boolean> = mutableStateOf(false),
    ): @Composable KortexApplicationScope.() -> Unit = {
        val monitors by rememberMonitors()
        for (monitor in monitors) {
            key(monitor) {
                Panel<Nothing>(
                    monitor = monitor,
                    edge = Edge.Top,
                    thickness = PANEL_HEIGHT.dp,
                    namespace = "$PANEL_NAMESPACE-${monitor.name}",
                ) {
                    Box(Modifier.fillMaxSize().background(PANEL_COLOUR))
                }
            }
        }
        Osd<Nothing>(width = OSD_WIDTH.dp, height = OSD_HEIGHT.dp, namespace = OSD_NAMESPACE) {
            val requested = osdClosed.value
            LaunchedEffect(requested) { if (requested) close() }
            Box(Modifier.fillMaxSize().background(OSD_COLOUR))
        }
    }

    /** Pumps [shell] until a panel reaches `hyprctl layers`, and returns the namespace it was filed under. */
    private fun awaitPanel(shell: KortexShell): String {
        val appeared = shell.pumpOrFail(PUMP_TIMEOUT_MILLIS) { panelNamespaces().isNotEmpty() }
        assertTrue(appeared, "hyprctl layers never reported a $PANEL_NAMESPACE- namespace")
        return panelNamespaces().first()
    }

    /** Pumps [shell] until the OSD reaches `hyprctl layers`, and returns where it landed. */
    private fun awaitOsd(shell: KortexShell): LayerGeometry {
        val appeared = shell.pumpOrFail(PUMP_TIMEOUT_MILLIS) { Screen.geometry(OSD_NAMESPACE) != null }
        assertTrue(appeared, "hyprctl layers never reported $OSD_NAMESPACE")
        return assertNotNull(Screen.geometry(OSD_NAMESPACE))
    }

    /** Polls [monitor] until its usable area starts at [expected], since a reservation lands a frame late. */
    private fun awaitUsableTop(monitor: String, expected: Int): HyprMonitor {
        val deadline = System.nanoTime() + HYPRCTL_SETTLE_MILLIS * NANOS_PER_MILLI
        var reported = Hyprctl.monitor(monitor)
        while (reported.usableY != expected && System.nanoTime() < deadline) {
            Thread.sleep(HYPRCTL_POLL_MILLIS)
            reported = Hyprctl.monitor(monitor)
        }
        return reported
    }

    private fun awaitPanelCount(count: Int): Set<String> {
        val deadline = System.nanoTime() + HYPRCTL_SETTLE_MILLIS * NANOS_PER_MILLI
        var namespaces = panelNamespaces()
        while (namespaces.size != count && System.nanoTime() < deadline) {
            Thread.sleep(HYPRCTL_POLL_MILLIS)
            namespaces = panelNamespaces()
        }
        return namespaces
    }

    /** Distinct, because a namespace mid-hotplug can transiently be reported under two monitors. */
    private fun panelNamespaces(): Set<String> =
        Hyprctl.namespaces().filterTo(mutableSetOf()) { it.startsWith("$PANEL_NAMESPACE-") }

    private fun osdCount(): Int = Hyprctl.namespaces().count { it == OSD_NAMESPACE }

    private companion object {
        const val PANEL_NAMESPACE = "kortex-multi-panel"
        const val OSD_NAMESPACE = "kortex-multi-osd"

        // Distinct from each other and from the sizes other tests use, so a config reaching the wrong
        // surface shows up as a wrong number rather than an accidental match.
        const val PANEL_HEIGHT = 43
        const val OSD_WIDTH = 320
        const val OSD_HEIGHT = 136

        const val PUMP_TIMEOUT_MILLIS = 4000L
        const val HYPRCTL_SETTLE_MILLIS = 2000L
        const val HYPRCTL_POLL_MILLIS = 100L
        const val NANOS_PER_MILLI = 1_000_000L

        val PANEL_COLOUR = Color(0x80, 0x80, 0x80)
        val OSD_COLOUR = Color(0x20, 0x60, 0xC0)
        const val PANEL_PIXEL = 0xFF808080.toInt()
        const val OSD_PIXEL = 0xFF2060C0.toInt()
    }
}
