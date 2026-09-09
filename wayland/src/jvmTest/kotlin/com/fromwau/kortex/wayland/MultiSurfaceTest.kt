package com.fromwau.kortex.wayland

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.getOrElse
import com.fromwau.kortex.compose.LocalKortexSurface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Runs two unrelated surfaces on one connection against a real compositor: a panel on every output
 * that reserves screen space, and one compositor-placed OSD floating over it that reserves none. Each
 * carries its own layer, size and content, so a shell that let one spec's configuration or composition
 * reach the other's surface shows up here.
 */
class MultiSurfaceTest {
    @Test
    fun `a panel and an OSD keep their own level, size and content, and only the panel reserves space`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }
        val before = Hyprctl.monitors().associateBy(Monitor::name)

        display.use { wayland ->
            val shell = KortexShell.create(wayland, panelSpec(), osdSpec())
                .getOrElse { error -> fail("shell creation failed: $error") }

            shell.use {
                awaitPanel(shell)
                val osd = awaitOsd(shell)
                val panelNamespace = assertNotNull(
                    panelNamespaces().firstOrNull { Screen.geometry(it)?.monitor == osd.monitor },
                    "no panel on ${osd.monitor}, the monitor the OSD landed on",
                )
                val panel = assertNotNull(Screen.geometry(panelNamespace))

                assertEquals(Layer.Top, panel.layer, "the panel is not on the layer its own config names")
                assertEquals(Layer.Overlay, osd.layer, "the OSD is not on the layer its own config names")
                assertEquals(PANEL_HEIGHT, panel.logicalHeight, "the panel is not the height its own config asks for")
                assertEquals(OSD_HEIGHT, osd.logicalHeight, "the OSD is not the height its own config asks for")
                assertEquals(OSD_WIDTH, osd.logicalWidth, "the OSD is not the width its own config asks for")

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

                assertEquals(PANEL_PIXEL, Screen.settledPixel(panel), "the panel is not showing its own content")
                assertEquals(OSD_PIXEL, Screen.settledPixel(osd), "the OSD is not showing its own content")
            }
        }
    }

    @Test
    fun `an OSD that closes itself leaves the panel on screen`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }
        val closeRequested = mutableStateOf(false)

        display.use { wayland ->
            val shell = KortexShell.create(wayland, panelSpec(), osdSpec(closeRequested))
                .getOrElse { error -> fail("shell creation failed: $error") }

            shell.use {
                val panelNamespace = awaitPanel(shell)
                awaitOsd(shell)
                val panelsAlone = shell.activeSurfaces.size - 1

                // Nothing on the test thread calls close(): only this flag can drop the OSD, and it does
                // so from the composition's own thread.
                closeRequested.value = true

                val dropped = shell.pump(PUMP_TIMEOUT_MILLIS) { shell.activeSurfaces.size == panelsAlone }
                assertTrue(dropped, "the shell never dropped the OSD after its content called close()")
                assertTrue(
                    shell.pump(PUMP_TIMEOUT_MILLIS) { Screen.geometry(OSD_NAMESPACE) == null },
                    "hyprctl layers still reports $OSD_NAMESPACE after close()",
                )

                val panel = assertNotNull(Screen.geometry(panelNamespace), "the panel went away with the OSD")
                assertEquals(PANEL_PIXEL, Screen.settledPixel(panel), "the panel stopped showing its content")
            }
        }
    }

    @Test
    fun `a hotplugged output grows the per-output surface and nothing else`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use { wayland ->
            val shell = KortexShell.create(wayland, panelSpec(), osdSpec())
                .getOrElse { error -> fail("shell creation failed: $error") }

            shell.use {
                awaitPanel(shell)
                awaitOsd(shell)
                val before = shell.activeSurfaces.size
                val panelsBefore = panelNamespaces().size

                var pending: String? = null
                try {
                    pending = Hyprctl.createHeadlessOutput()

                    val grew = shell.pump(PUMP_TIMEOUT_MILLIS) { shell.activeSurfaces.size == before + 1 }
                    assertTrue(grew, "the per-output spec did not follow the new output")

                    val panels = awaitPanelCount(panelsBefore + 1)
                    assertEquals(
                        panelsBefore + 1, panels.size,
                        "expected one panel namespace per output, got $panels",
                    )
                    assertEquals(1, osdCount(), "the compositor-placed spec was placed a second time")

                    Hyprctl.removeHeadlessOutput(pending)
                    pending = null

                    val shrank = shell.pump(PUMP_TIMEOUT_MILLIS) { shell.activeSurfaces.size == before }
                    assertTrue(shrank, "the removed output's panel outlived it")
                    assertEquals(1, osdCount(), "the compositor-placed surface went with the output")
                } finally {
                    // Guarantees the virtual output never survives a failed assertion above.
                    pending?.let(Hyprctl::removeHeadlessOutput)
                }
            }
        }
    }

    private fun panelSpec(): SurfaceSpec =
        SurfaceSpec(PANEL_CONFIG) { Box(Modifier.fillMaxSize().background(PANEL_COLOUR)) }

    /** Its content closes the surface itself once [closeRequested] flips, on the composition's own thread. */
    private fun osdSpec(closeRequested: MutableState<Boolean> = mutableStateOf(false)): SurfaceSpec =
        SurfaceSpec(OSD_CONFIG, OutputTarget.CompositorChoice) {
            val surface = LocalKortexSurface.current
            val requested = closeRequested.value
            LaunchedEffect(requested) { if (requested) surface.close() }
            Box(Modifier.fillMaxSize().background(OSD_COLOUR))
        }

    /** Pumps [shell] until a panel reaches `hyprctl layers`, and returns the namespace it was filed under. */
    private fun awaitPanel(shell: KortexShell): String {
        val appeared = shell.pump(PUMP_TIMEOUT_MILLIS) { panelNamespaces().isNotEmpty() }
        assertTrue(appeared, "hyprctl layers never reported a $PANEL_NAMESPACE- namespace")
        return panelNamespaces().first()
    }

    /** Pumps [shell] until the OSD reaches `hyprctl layers`, and returns where it landed. */
    private fun awaitOsd(shell: KortexShell): LayerGeometry {
        val appeared = shell.pump(PUMP_TIMEOUT_MILLIS) { Screen.geometry(OSD_NAMESPACE) != null }
        assertTrue(appeared, "hyprctl layers never reported $OSD_NAMESPACE")
        return assertNotNull(Screen.geometry(OSD_NAMESPACE))
    }

    /** Polls [monitor] until its usable area starts at [expected], since a reservation lands a frame late. */
    private fun awaitUsableTop(monitor: String, expected: Int): Monitor {
        val deadline = System.nanoTime() + HYPRCTL_SETTLE_MILLIS * NANOS_PER_MILLI
        var reported = monitor(monitor)
        while (reported.usableY != expected && System.nanoTime() < deadline) {
            Thread.sleep(HYPRCTL_POLL_MILLIS)
            reported = monitor(monitor)
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

    private fun monitor(name: String): Monitor =
        assertNotNull(Hyprctl.monitors().firstOrNull { it.name == name }, "hyprctl lost monitor $name")

    private fun namespaces(): List<String> = Hyprctl.layers()
        .values
        .flatMap { it.levels.values.flatten() }
        .map { it.namespace }

    /** Distinct, because a namespace mid-hotplug can transiently be reported under two monitors. */
    private fun panelNamespaces(): Set<String> =
        namespaces().filterTo(mutableSetOf()) { it.startsWith("$PANEL_NAMESPACE-") }

    private fun osdCount(): Int = namespaces().count { it == OSD_NAMESPACE }

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

        val PANEL_CONFIG = SurfaceConfig.panel(Edge.Top, PANEL_HEIGHT.dp).copy(namespace = PANEL_NAMESPACE)

        // Anchored to nothing, which is how the compositor is asked to centre it, so it must yield:
        // Overlap extends a surface to its anchored edges and this one has none. Yielding centres it
        // inside what the panel's zone leaves rather than across it.
        val OSD_CONFIG = SurfaceConfig(
            namespace = OSD_NAMESPACE,
            layer = Layer.Overlay,
            anchor = emptySet(),
            width = OSD_WIDTH.dp,
            height = OSD_HEIGHT.dp,
            exclusiveZone = ExclusiveZone.Yield,
        )
    }
}
