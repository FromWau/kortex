package com.fromwau.kortex.wayland

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.IntSize
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.getOrElse
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

/** The monitors an application lists, and surfaces put on one of them. */
class MonitorTest {
    @Test
    fun `rememberMonitors() lists every connected monitor, named and sized as hyprctl reports it`() {
        val listed = AtomicReference<List<Monitor>>(emptyList())
        val content: @Composable KortexApplicationScope.() -> Unit = {
            val monitors by rememberMonitors()
            SideEffect { listed.set(monitors) }
        }

        onApplication(content) { shell ->
            assertTrue(shell.pumpOrFail(PUMP_MILLIS) { listed.get().isNotEmpty() }, "rememberMonitors() listed nothing")
            assertEquals(
                Hyprctl.monitors().associate { it.name to IntSize(it.width, it.height) },
                listed.get().associate { it.name to IntSize(it.geometry.width, it.geometry.height) },
                "rememberMonitors() did not list the monitors hyprctl reports, by name and mode size",
            )
        }
    }

    @Test
    fun `rememberMonitors() inside a surface's content lists the same monitors as the application`() {
        val inApplication = AtomicReference<List<Monitor>>(emptyList())
        val inContent = AtomicReference<List<Monitor>?>(null)
        val content: @Composable KortexApplicationScope.() -> Unit = {
            val monitors by rememberMonitors()
            SideEffect { inApplication.set(monitors) }
            TestSurface(NAMESPACE) {
                val seen by rememberMonitors()
                SideEffect { inContent.set(seen) }
            }
        }

        onApplication(content) { shell ->
            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { inContent.get() != null },
                "the surface's content listed nothing",
            )
            assertTrue(inApplication.get().isNotEmpty(), "the application listed no monitor")
            assertEquals(
                inApplication.get(),
                inContent.get(),
                "the surface's content listed other monitors than the application",
            )
        }
    }

    @Test
    fun `a surface shown on a monitor lands on it, under its namespace as written`() {
        val content: @Composable KortexApplicationScope.() -> Unit = {
            val monitors by rememberMonitors()
            monitors.firstOrNull()?.let { TestSurface(NAMESPACE, monitor = it) }
        }

        onApplication(content) { shell ->
            awaitPlaced(shell)
            val monitor = shell.monitors.value.first()

            val geometry = assertNotNull(
                Screen.awaitGeometry(NAMESPACE),
                "hyprctl never listed $NAMESPACE, its namespace as written",
            )
            assertEquals(monitor.name, geometry.monitor, "hyprctl lists the surface under another monitor")
        }
    }

    @Test
    fun `a surface shown on a monitor asks the compositor for that monitor's own output`() {
        val probe = runProbe(MonitorPlacementProbe::class.java.name, environment = WIRE_LOGGING)
        // This JVM's FORCE_COLOR reaches the probe, and colours what libwayland logs.
        val lines = probe.output.map { it.replace(ANSI_ESCAPE, "") }
        val printed = lines.joinToString("\n")
        assertEquals(0, probe.exitCode, "the probe exited ${probe.exitCode}:\n$printed")
        assertTrue(
            "$MONITOR_PROBE_RESULT${Ok(Unit)}" in lines,
            "the probe's application did not end cleanly:\n$printed",
        )

        val shownOn = assertNotNull(
            lines.firstNotNullOfOrNull { line -> line.substringAfter(MONITOR_PROBE_MARKER, "").ifEmpty { null } },
            "the probe never showed its surface:\n$printed",
        )
        val request = assertNotNull(
            lines.firstNotNullOfOrNull { LAYER_SURFACE_REQUEST.find(it) },
            "the probe never asked for a layer surface named $MONITOR_PROBE_NAMESPACE:\n$printed",
        )
        val outputNames = lines
            .mapNotNull { OUTPUT_NAME_EVENT.find(it) }
            .associate { it.groupValues[1] to it.groupValues[2] }
        assertEquals(
            shownOn,
            outputNames[request.groupValues[1]],
            "get_layer_surface did not name $shownOn's own output: ${request.value}",
        )
    }

    @Test
    fun `settings that differ only by monitor are different, and equal monitors make equal settings`() {
        withUnboundMonitors("LEFT-1", "RIGHT-1") { (left, right) ->
            val onLeft = settingsAskedBy { TestSurface(NAMESPACE, monitor = left) }
            assertNotEquals(
                onLeft,
                settingsAskedBy { TestSurface(NAMESPACE, monitor = right) },
                "surfaces on two monitors had equal settings",
            )
            assertNotEquals(
                onLeft,
                settingsAskedBy { TestSurface(NAMESPACE) },
                "a surface on a monitor had the settings of one the compositor places",
            )

            val leftAgain = Monitor(left.output)
            assertEquals(left, leftAgain, "two Monitors of one output were not equal")
            assertEquals(left.hashCode(), leftAgain.hashCode(), "two Monitors of one output hashed apart")
            assertEquals(
                onLeft,
                settingsAskedBy { TestSurface(NAMESPACE, monitor = leftAgain) },
                "surfaces on equal monitors had different settings",
            )
        }
    }

    @Test
    fun `a call replaces its surface when only the monitor changes, and keeps it for an equal monitor`() {
        val placement = mutableStateOf(Placement.CompositorChoice)
        val composed = AtomicReference<Placement?>(null)
        val speck = SurfaceState()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            val monitors by rememberMonitors()
            val listed = monitors.first()
            val current = placement.value
            val monitor = when (current) {
                Placement.CompositorChoice -> null
                Placement.Listed -> listed
                Placement.EqualToListed -> Monitor(listed.output)
            }
            SideEffect { composed.set(current) }
            TestSurface(NAMESPACE, monitor = monitor, state = speck)
        }

        onApplication(content) { shell ->
            awaitPlaced(shell)
            val placedByCompositor = shell.shownSurfaces.single()

            placement.value = Placement.Listed

            val replaced = shell.pumpOrFail(PUMP_MILLIS) {
                shell.shownSurfaces.singleOrNull()?.let { it !== placedByCompositor } == true
            }
            assertTrue(replaced, "putting the surface on a monitor did not replace it")
            val placedOnMonitor = shell.shownSurfaces.single()

            placement.value = Placement.EqualToListed

            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { composed.get() == Placement.EqualToListed },
                "the application never composed its surface on an equal Monitor",
            )
            shell.pumpOrFail(SETTLE_MILLIS)
            assertSame(placedOnMonitor, shell.shownSurfaces.singleOrNull(), "an equal Monitor replaced the surface")
            assertFalse(speck.hasEnded, "changing the monitor ended the surface: ${speck.status}")
        }
    }

    @Test
    fun `a surface shown on a monitor that has gone ends as MonitorUnplugged and is never placed`() {
        val speck = SurfaceState()
        withUnboundMonitors("GONE-1") { (gone) ->
            val content: @Composable KortexApplicationScope.() -> Unit = {
                TestSurface(NAMESPACE, monitor = gone, state = speck)
            }

            onApplication(content) { shell ->
                assertTrue(
                    shell.pumpOrFail(PUMP_MILLIS) { speck.hasEnded },
                    "a surface on a monitor that has gone ended nothing",
                )
                shell.pumpOrFail(SETTLE_MILLIS)
                speck.assertEnded(
                    Ok(SurfaceEnd.MonitorUnplugged),
                    "a surface on a monitor that has gone did not end as MonitorUnplugged",
                )
                assertTrue(shell.shownSurfaces.isEmpty(), "a surface on a monitor that has gone was placed")
                assertNull(Screen.geometry(NAMESPACE), "hyprctl lists a surface on a monitor that has gone")
            }
        }
    }

    @Test
    fun `a monitor the registry removes leaves the list, and a surface on it ends as MonitorUnplugged`() {
        val listed = AtomicReference<List<Monitor>>(emptyList())
        val speck = SurfaceState()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            val monitors by rememberMonitors()
            // Kept once taken, so the call stays in composition after its monitor has left the list.
            val first = remember { monitors.first() }
            SideEffect { listed.set(monitors) }
            TestSurface(NAMESPACE, monitor = first, state = speck)
        }
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use {
            KortexShell.createApplicationOrFail(display, content).useOrFail { shell ->
                awaitPlaced(shell)
                val monitor = shell.monitors.value.first()
                val global = display.globals.first { it.name == monitor.output.name }

                // What the registry reports as a monitor is unplugged; the compositor's own outputs stay as they are.
                assertNotNull(display.onGlobalRemoved, "the shell listens for no removed global").invoke(global)

                assertTrue(
                    shell.pumpOrFail(PUMP_MILLIS) { speck.hasEnded },
                    "a surface on a monitor that went away ended nothing",
                )
                shell.pumpOrFail(SETTLE_MILLIS)
                speck.assertEnded(
                    Ok(SurfaceEnd.MonitorUnplugged),
                    "a surface on a monitor that went away did not end as MonitorUnplugged",
                )
                assertTrue(shell.shownSurfaces.isEmpty(), "a surface outlived its monitor")
                assertTrue(listed.get().isEmpty(), "a monitor that went away stayed listed: ${listed.get()}")
                assertTrue(
                    shell.pumpOrFail(PUMP_MILLIS) { Screen.geometry(NAMESPACE) == null },
                    "hyprctl still lists a surface whose monitor went away",
                )
            }
        }
    }

    /** Where a test's surface asks to go. */
    private enum class Placement {
        CompositorChoice,
        Listed,
        EqualToListed,
    }

    private companion object {
        const val NAMESPACE = "kortex-monitor"
        const val PUMP_MILLIS = 4_000L
        const val SETTLE_MILLIS = 300L
        val WIRE_LOGGING = mapOf("WAYLAND_DEBUG" to "client")

        // How libwayland logs a request: an object argument as interface#id, and a null one as nil.
        val LAYER_SURFACE_REQUEST = Regex(
            """get_layer_surface\(new id [^,]+, [^,]+, (nil|wl_output#\d+), \d+, "$MONITOR_PROBE_NAMESPACE"\)""",
        )
        val OUTPUT_NAME_EVENT = Regex("""(wl_output#\d+)\.name\("([^"]*)"\)""")
        val ANSI_ESCAPE = Regex("""\x1B\[[0-9;]*m""")
    }
}
