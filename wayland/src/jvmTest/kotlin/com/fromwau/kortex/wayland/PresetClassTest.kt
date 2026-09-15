package com.fromwau.kortex.wayland

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame

/**
 * Pins that each preset class takes its settings from the [SurfaceConfig] preset of its kind, and that the presets
 * which take no keyboard focus, shown through [Show], land where hyprctl says that preset places a surface.
 *
 * [Dock], [AppMenu] and [LockScreen] take the keyboard as they map, so where they land is [SurfacePresetTest]'s, which
 * needs the desktop to itself; their settings are checked here, where nothing is shown.
 */
class PresetClassTest {
    @Test
    fun `a Bar left at its defaults spans the top edge, 32 dp thick, and reserves 32 dp`() {
        val content: @Composable KortexApplicationScope.() -> Unit = {
            Show(
                object : Bar<Nothing>(namespace = BAR_NAMESPACE) {
                    @Composable
                    override fun invoke() = Unit
                },
            )
        }
        // Every monitor's, since the compositor chooses the bar's; the usable area, as a desktop's own bar may reserve.
        val before = Hyprctl.monitors().associateBy { it.name }

        onApplication(content) { shell ->
            awaitPlaced(shell)

            val geometry = assertNotNull(Screen.awaitGeometry(BAR_NAMESPACE), "hyprctl never listed $BAR_NAMESPACE")
            val monitor = before.getValue(geometry.monitor)
            assertEquals(Layer.Top, geometry.layer, "the bar did not land on Layer.Top")
            assertEquals(monitor.usableX, geometry.x, "the bar did not sit clear of what already reserves space")
            assertEquals(monitor.usableY, geometry.y, "the bar did not sit on the top edge of the usable area")
            assertEquals(monitor.usableWidth, geometry.logicalWidth, "the bar did not span the top edge")
            assertEquals(DEFAULT_BAR_THICKNESS, geometry.logicalHeight, "the bar is not 32 dp thick")
            assertReservesMore(shell, monitor, Edge.Top, DEFAULT_BAR_THICKNESS)
        }
    }

    @Test
    fun `a Panel spans its edge and reserves exactly its own thickness`() {
        val content: @Composable KortexApplicationScope.() -> Unit = {
            val monitors by rememberMonitors()
            Show(
                object : Panel<Nothing>(
                    monitor = monitors.first(),
                    edge = Edge.Bottom,
                    thickness = PANEL_THICKNESS.dp,
                    namespace = PANEL_NAMESPACE,
                ) {
                    @Composable
                    override fun invoke() = Unit
                },
            )
        }

        onApplication(content) { shell ->
            // The usable area, not the raw monitor: a desktop's own bar may already reserve space.
            val before = Hyprctl.monitor(shell.monitors.value.first().name)
            awaitPlaced(shell)

            val geometry = assertNotNull(Screen.awaitGeometry(PANEL_NAMESPACE), "hyprctl never listed $PANEL_NAMESPACE")
            assertEquals(Layer.Top, geometry.layer, "the panel did not land on Layer.Top")
            assertEquals(before.usableX, geometry.x, "the panel did not sit clear of what already reserves space")
            assertEquals(
                before.usableY + before.usableHeight - PANEL_THICKNESS,
                geometry.y,
                "the panel did not sit on the bottom edge of the usable area",
            )
            assertEquals(before.usableWidth, geometry.logicalWidth, "the panel did not span the bottom edge")
            assertEquals(PANEL_THICKNESS, geometry.logicalHeight, "the panel is not its own thickness")
            assertReservesMore(shell, before, Edge.Bottom, PANEL_THICKNESS)
        }
    }

    @Test
    fun `a Bar's settings are a SurfaceConfig panel's, with every value it was given`() {
        withUnboundMonitors(MONITOR_NAME) { (monitor) ->
            val bar = object : Bar<Nothing>(
                monitor = monitor,
                edge = Edge.Left,
                thickness = THICKNESS.dp,
                length = LENGTH.dp,
                margins = MARGINS,
                keyboard = KeyboardInteractivity.OnDemand,
                namespace = BAR_NAMESPACE,
            ) {
                @Composable
                override fun invoke() = Unit
            }

            assertSame(monitor, bar.monitor, "the bar was not put on the monitor it was given")
            assertEquals(
                SurfaceConfig
                    .panel(Edge.Left, THICKNESS.dp, LENGTH.dp)
                    .copy(namespace = BAR_NAMESPACE, margins = MARGINS, keyboard = KeyboardInteractivity.OnDemand),
                bar.settings.config,
                "the bar's settings are not a panel's with the values it was given",
            )
        }
    }

    @Test
    fun `a Panel's settings are a SurfaceConfig panel's, with every value it was given`() {
        withUnboundMonitors(MONITOR_NAME) { (monitor) ->
            val panel = object : Panel<Nothing>(
                monitor = monitor,
                edge = Edge.Right,
                thickness = THICKNESS.dp,
                length = LENGTH.dp,
                margins = MARGINS,
                namespace = PANEL_NAMESPACE,
            ) {
                @Composable
                override fun invoke() = Unit
            }

            assertSame(monitor, panel.monitor, "the panel was not put on the monitor it was given")
            assertEquals(
                SurfaceConfig
                    .panel(Edge.Right, THICKNESS.dp, LENGTH.dp)
                    .copy(namespace = PANEL_NAMESPACE, margins = MARGINS),
                panel.settings.config,
                "the panel's settings are not a panel's with the values it was given",
            )
        }
    }

    /**
     * Pumps [shell] until [before]'s monitor reserves [amount] more against [edge] than it did, since a reservation
     * lands a frame late, and fails if it never reserves exactly that.
     */
    private fun assertReservesMore(shell: KortexShell, before: HyprMonitor, edge: Edge, amount: Int) {
        fun added() = Hyprctl.monitor(before.name).reservedAgainst(edge) - before.reservedAgainst(edge)
        shell.pumpOrFail(PUMP_MILLIS) { added() == amount }
        assertEquals(amount, added(), "the surface did not reserve exactly $amount against $edge")
    }

    private companion object {
        const val BAR_NAMESPACE = "kortex-preset-class-bar"
        const val PANEL_NAMESPACE = "kortex-preset-class-panel"
        const val MONITOR_NAME = "PRESET-1"
        const val PUMP_MILLIS = 4_000L

        // What Bar promises when left at its defaults.
        const val DEFAULT_BAR_THICKNESS = 32

        // Distinct from each other and from the sizes other tests use, so a value reaching the wrong field shows up
        // as a wrong number rather than an accidental match.
        const val PANEL_THICKNESS = 67
        const val THICKNESS = 41
        const val LENGTH = 307
        val MARGINS = Margins(top = 3.dp, right = 5.dp, bottom = 7.dp, left = 11.dp)
    }
}
