package com.fromwau.kortex.wayland

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
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

    @Test
    fun `a DesktopBackground covers its whole monitor on the background layer`() {
        val content: @Composable KortexApplicationScope.() -> Unit = {
            val monitors by rememberMonitors()
            Show(
                object : DesktopBackground<Nothing>(monitor = monitors.first(), namespace = BACKGROUND_NAMESPACE) {
                    @Composable
                    override fun invoke() = Unit
                },
            )
        }

        onApplication(content) { shell ->
            val monitor = Hyprctl.monitor(shell.monitors.value.first().name)
            awaitPlaced(shell)

            val geometry = assertNotNull(
                Screen.awaitGeometry(BACKGROUND_NAMESPACE),
                "hyprctl never listed $BACKGROUND_NAMESPACE",
            )
            assertEquals(Layer.Background, geometry.layer, "the background did not land on Layer.Background")
            assertEquals(monitor.x, geometry.x, "the background did not cover its monitor")
            assertEquals(monitor.y, geometry.y, "the background did not cover its monitor")
            assertEquals(monitor.logicalWidth, geometry.logicalWidth, "the background did not cover its monitor")
            assertEquals(monitor.logicalHeight, geometry.logicalHeight, "the background did not cover its monitor")
        }
    }

    @Test
    fun `an Osd is exactly its own size, centred in the usable area above every other layer`() {
        val content: @Composable KortexApplicationScope.() -> Unit = {
            val monitors by rememberMonitors()
            Show(
                object : Osd<Nothing>(
                    monitor = monitors.first(),
                    width = OSD_WIDTH.dp,
                    height = OSD_HEIGHT.dp,
                    namespace = OSD_NAMESPACE,
                ) {
                    @Composable
                    override fun invoke() = Unit
                },
            )
        }

        onApplication(content) { shell ->
            // The usable area, not the raw monitor: yielding centres an unanchored surface in what others leave free.
            val before = Hyprctl.monitor(shell.monitors.value.first().name)
            awaitPlaced(shell)

            val geometry = assertNotNull(Screen.awaitGeometry(OSD_NAMESPACE), "hyprctl never listed $OSD_NAMESPACE")
            assertEquals(Layer.Overlay, geometry.layer, "the osd did not land above every other layer")
            assertEquals(OSD_WIDTH, geometry.logicalWidth, "the osd is not its own width")
            assertEquals(OSD_HEIGHT, geometry.logicalHeight, "the osd is not its own height")
            assertCentredInUsableArea(before, OSD_WIDTH, OSD_HEIGHT, geometry)
        }
    }

    @Test
    fun `a DesktopBackground's settings are a SurfaceConfig desktopBackground's, with every value it was given`() {
        withUnboundMonitors(MONITOR_NAME) { (monitor) ->
            val background = object : DesktopBackground<Nothing>(monitor = monitor, namespace = BACKGROUND_NAMESPACE) {
                @Composable
                override fun invoke() = Unit
            }

            assertSame(monitor, background.monitor, "the background was not put on the monitor it was given")
            assertEquals(
                SurfaceConfig.desktopBackground().copy(namespace = BACKGROUND_NAMESPACE),
                background.settings.config,
                "the background's settings are not a desktopBackground's with the values it was given",
            )
        }
    }

    @Test
    fun `an Osd's settings are a SurfaceConfig osd's, with every value it was given`() {
        withUnboundMonitors(MONITOR_NAME) { (monitor) ->
            val osd = object : Osd<Nothing>(
                monitor = monitor,
                width = OSD_WIDTH.dp,
                height = OSD_HEIGHT.dp,
                namespace = OSD_NAMESPACE,
            ) {
                @Composable
                override fun invoke() = Unit
            }

            assertSame(monitor, osd.monitor, "the osd was not put on the monitor it was given")
            assertEquals(
                SurfaceConfig.osd(OSD_WIDTH.dp, OSD_HEIGHT.dp).copy(namespace = OSD_NAMESPACE),
                osd.settings.config,
                "the osd's settings are not an osd's with the values it was given",
            )
        }
    }

    @Test
    fun `a ContextMenu sits at the point it was given on its monitor, across a panel's reserved space`() {
        val content: @Composable KortexApplicationScope.() -> Unit = {
            val monitors by rememberMonitors()
            val monitor = monitors.first()
            // A reservation of the test's own, so the point is shown to be the monitor's, not the usable area's.
            Show(
                object : Panel<Nothing>(
                    monitor = monitor,
                    edge = Edge.Left,
                    thickness = MENU_PANEL_THICKNESS.dp,
                    namespace = MENU_PANEL_NAMESPACE,
                ) {
                    @Composable
                    override fun invoke() = Unit
                },
            )
            Show(
                object : ContextMenu<Nothing>(
                    monitor = monitor,
                    at = IntOffset(MENU_X, MENU_Y),
                    size = MENU_SIZE,
                    namespace = MENU_NAMESPACE,
                ) {
                    @Composable
                    override fun invoke() = Unit
                },
            )
        }

        onApplication(content) { shell ->
            val before = Hyprctl.monitor(shell.monitors.value.first().name)
            awaitPlaced(shell, count = 2)
            assertReservesMore(shell, before, Edge.Left, MENU_PANEL_THICKNESS)

            val geometry = assertNotNull(Screen.awaitGeometry(MENU_NAMESPACE), "hyprctl never listed $MENU_NAMESPACE")
            assertEquals(Layer.Overlay, geometry.layer, "the menu did not land above every other layer")
            assertEquals(before.x + MENU_X, geometry.x, "the menu is not at the x it was given, on its monitor")
            assertEquals(before.y + MENU_Y, geometry.y, "the menu is not at the y it was given, on its monitor")
            assertEquals(MENU_SIZE.width, geometry.logicalWidth, "the menu is not its own width")
            assertEquals(MENU_SIZE.height, geometry.logicalHeight, "the menu is not its own height")
        }
    }

    @Test
    fun `a ContextMenu near its monitor's bottom-right corner opens up and to the left of its point`() {
        val screen = Hyprctl.monitors().first()
        val at = IntOffset(screen.logicalWidth - FLIP_INSET, screen.logicalHeight - FLIP_INSET)
        val content: @Composable KortexApplicationScope.() -> Unit = {
            val monitors by rememberMonitors()
            Show(
                object : ContextMenu<Nothing>(
                    monitor = monitors.first { it.name == screen.name },
                    at = at,
                    size = MENU_SIZE,
                    namespace = MENU_NAMESPACE,
                ) {
                    @Composable
                    override fun invoke() = Unit
                },
            )
        }

        onApplication(content) { shell ->
            awaitPlaced(shell)

            val geometry = assertNotNull(Screen.awaitGeometry(MENU_NAMESPACE), "hyprctl never listed $MENU_NAMESPACE")
            assertEquals(
                screen.x + at.x - MENU_SIZE.width,
                geometry.x,
                "the menu did not open to the left of its point, near its monitor's right edge",
            )
            assertEquals(
                screen.y + at.y - MENU_SIZE.height,
                geometry.y,
                "the menu did not open upwards from its point, near its monitor's bottom edge",
            )
        }
    }

    @Test
    fun `a ContextMenu's settings are a SurfaceConfig contextMenu's, with every value it was given`() {
        withUnboundMonitors(MONITOR_NAME, mode = MONITOR_MODE) { (monitor) ->
            val at = IntOffset(MENU_X, MENU_Y)
            val menu = object : ContextMenu<Nothing>(
                monitor = monitor,
                at = at,
                size = MENU_SIZE,
                namespace = MENU_NAMESPACE,
            ) {
                @Composable
                override fun invoke() = Unit
            }

            assertSame(monitor, menu.monitor, "the menu was not put on the monitor it was given")
            assertEquals(
                SurfaceConfig.contextMenu(at, MENU_SIZE, MONITOR_MODE).copy(namespace = MENU_NAMESPACE),
                menu.settings.config,
                "the menu's settings are not a contextMenu's with the values it was given",
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
        const val BACKGROUND_NAMESPACE = "kortex-preset-class-background"
        const val OSD_NAMESPACE = "kortex-preset-class-osd"
        const val MENU_NAMESPACE = "kortex-preset-class-context-menu"
        const val MENU_PANEL_NAMESPACE = "kortex-preset-class-context-menu-panel"
        const val MONITOR_NAME = "PRESET-1"
        const val PUMP_MILLIS = 4_000L

        // An unbound monitor's mode at scale 1, so its logical size too.
        val MONITOR_MODE = IntSize(1920, 1080)

        // What Bar promises when left at its defaults.
        const val DEFAULT_BAR_THICKNESS = 32

        // Distinct from each other and from the sizes other tests use, so a value reaching the wrong field shows up
        // as a wrong number rather than an accidental match.
        const val PANEL_THICKNESS = 67
        const val THICKNESS = 41
        const val LENGTH = 307
        const val OSD_WIDTH = 239
        const val OSD_HEIGHT = 43
        const val MENU_PANEL_THICKNESS = 37
        val MENU_SIZE = IntSize(173, 131)
        val MARGINS = Margins(top = 3.dp, right = 5.dp, bottom = 7.dp, left = 11.dp)

        // Clear of every edge of any mode the monitor takes, so the menu keeps its top-left corner at the point.
        const val MENU_X = 601
        const val MENU_Y = 397

        // Close enough to the monitor's right and bottom edges that MENU_SIZE overflows both.
        const val FLIP_INSET = 19
    }
}
