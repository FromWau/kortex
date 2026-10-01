package com.fromwau.kortex.wayland

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame

/**
 * Pins that each preset asks for the [SurfaceConfig] preset of its kind, and that those which take no keyboard focus
 * land where that preset places a surface, as hyprctl reports it.
 *
 * [Dock], [AppMenu] and [LockScreen] take the keyboard as they map, so where they land is [SurfacePresetTest]'s,
 * which needs the desktop to itself; what they ask for is checked here, where nothing is placed.
 */
class PresetTest {
    @Test
    fun `a Bar left at its defaults takes no keyboard focus, spans the top edge, 32 dp thick, and reserves 32 dp`() {
        // Before anything is shown: Hyprland hands a surface that takes the keyboard the user's focus as it maps.
        assertEquals(
            KeyboardInteractivity.None,
            settingsAskedBy { Bar(namespace = BAR_NAMESPACE) {} }.config.keyboard,
            "a Bar left at its defaults takes the keyboard",
        )
        // Every monitor's usable area: the compositor picks the bar's monitor, and a desktop bar may reserve space.
        val before = Hyprctl.monitors().associateBy { it.name }

        onApplication({ Bar(namespace = BAR_NAMESPACE) {} }) { shell ->
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
            Panel(
                monitor = monitors.first(),
                edge = Edge.Bottom,
                thickness = PANEL_THICKNESS.dp,
                namespace = PANEL_NAMESPACE,
            ) {}
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
    fun `a Bar asks for a SurfaceConfig panel's settings, with every value it was given`() {
        withUnboundMonitors(MONITOR_NAME) { (monitor) ->
            val settings = settingsAskedBy {
                Bar(
                    monitor = monitor,
                    edge = Edge.Left,
                    thickness = THICKNESS.dp,
                    length = Length.Of(LENGTH.dp),
                    margins = MARGINS,
                    keyboard = KeyboardInteractivity.OnDemand,
                    namespace = BAR_NAMESPACE,
                ) {}
            }

            assertSame(monitor, settings.monitor, "the bar was not put on the monitor it was given")
            assertEquals(
                SurfaceConfig
                    .panel(Edge.Left, THICKNESS.dp, Length.Of(LENGTH.dp))
                    .copy(namespace = BAR_NAMESPACE, margins = MARGINS, keyboard = KeyboardInteractivity.OnDemand),
                settings.config,
                "the bar did not ask for a panel's settings with the values it was given",
            )
        }
    }

    @Test
    fun `a Panel asks for a SurfaceConfig panel's settings, with every value it was given`() {
        withUnboundMonitors(MONITOR_NAME) { (monitor) ->
            val settings = settingsAskedBy {
                Panel(
                    monitor = monitor,
                    edge = Edge.Right,
                    thickness = THICKNESS.dp,
                    length = Length.Of(LENGTH.dp),
                    margins = MARGINS,
                    namespace = PANEL_NAMESPACE,
                ) {}
            }

            assertSame(monitor, settings.monitor, "the panel was not put on the monitor it was given")
            assertEquals(
                SurfaceConfig
                    .panel(Edge.Right, THICKNESS.dp, Length.Of(LENGTH.dp))
                    .copy(namespace = PANEL_NAMESPACE, margins = MARGINS),
                settings.config,
                "the panel did not ask for a panel's settings with the values it was given",
            )
        }
    }

    @Test
    fun `a DesktopBackground covers its whole monitor on the background layer`() {
        val content: @Composable KortexApplicationScope.() -> Unit = {
            val monitors by rememberMonitors()
            DesktopBackground(monitor = monitors.first(), namespace = BACKGROUND_NAMESPACE) {}
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
            Osd(
                monitor = monitors.first(),
                width = OSD_WIDTH.dp,
                height = OSD_HEIGHT.dp,
                namespace = OSD_NAMESPACE,
            ) {}
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
    fun `a DesktopBackground asks for a SurfaceConfig desktopBackground's settings, with the values it was given`() {
        withUnboundMonitors(MONITOR_NAME) { (monitor) ->
            val settings = settingsAskedBy {
                DesktopBackground(monitor = monitor, namespace = BACKGROUND_NAMESPACE) {}
            }

            assertSame(monitor, settings.monitor, "the background was not put on the monitor it was given")
            assertEquals(
                SurfaceConfig.desktopBackground().copy(namespace = BACKGROUND_NAMESPACE),
                settings.config,
                "the background did not ask for a desktopBackground's settings with the values it was given",
            )
        }
    }

    @Test
    fun `an Osd asks for a SurfaceConfig osd's settings, with every value it was given`() {
        withUnboundMonitors(MONITOR_NAME) { (monitor) ->
            val settings = settingsAskedBy {
                Osd(
                    monitor = monitor,
                    width = OSD_WIDTH.dp,
                    height = OSD_HEIGHT.dp,
                    namespace = OSD_NAMESPACE,
                ) {}
            }

            assertSame(monitor, settings.monitor, "the osd was not put on the monitor it was given")
            assertEquals(
                SurfaceConfig.osd(OSD_WIDTH.dp, OSD_HEIGHT.dp).copy(namespace = OSD_NAMESPACE),
                settings.config,
                "the osd did not ask for an osd's settings with the values it was given",
            )
        }
    }

    @Test
    fun `a Dock asks for a SurfaceConfig dock's settings, with every value it was given`() {
        withUnboundMonitors(MONITOR_NAME) { (monitor) ->
            val settings = settingsAskedBy {
                Dock(
                    monitor = monitor,
                    edge = Edge.Bottom,
                    thickness = THICKNESS.dp,
                    length = Length.Of(LENGTH.dp),
                    margins = MARGINS,
                    namespace = DOCK_NAMESPACE,
                ) {}
            }

            assertEquals(
                KeyboardInteractivity.OnDemand,
                settings.config.keyboard,
                "the dock does not take the keyboard on demand",
            )
            assertSame(monitor, settings.monitor, "the dock was not put on the monitor it was given")
            assertEquals(
                SurfaceConfig
                    .dock(Edge.Bottom, THICKNESS.dp, Length.Of(LENGTH.dp))
                    .copy(namespace = DOCK_NAMESPACE, margins = MARGINS),
                settings.config,
                "the dock did not ask for a dock's settings with the values it was given",
            )
        }
    }

    @Test
    fun `an AppMenu asks for a SurfaceConfig appMenu's settings, with every value it was given`() {
        withUnboundMonitors(MONITOR_NAME) { (monitor) ->
            val settings = settingsAskedBy {
                AppMenu(
                    monitor = monitor,
                    width = OSD_WIDTH.dp,
                    height = OSD_HEIGHT.dp,
                    namespace = APP_MENU_NAMESPACE,
                ) {}
            }

            assertEquals(
                KeyboardInteractivity.OnDemand,
                settings.config.keyboard,
                "the app menu does not take the keyboard on demand",
            )
            assertSame(monitor, settings.monitor, "the app menu was not put on the monitor it was given")
            assertEquals(
                SurfaceConfig.appMenu(OSD_WIDTH.dp, OSD_HEIGHT.dp).copy(namespace = APP_MENU_NAMESPACE),
                settings.config,
                "the app menu did not ask for an appMenu's settings with the values it was given",
            )
        }
    }

    @Test
    fun `a LockScreen asks for a SurfaceConfig lockScreen's settings, with every value it was given`() {
        withUnboundMonitors(MONITOR_NAME) { (monitor) ->
            val settings = settingsAskedBy {
                LockScreen(monitor = monitor, namespace = LOCK_NAMESPACE) {}
            }

            assertEquals(
                KeyboardInteractivity.Exclusive,
                settings.config.keyboard,
                "the lock screen does not take the keyboard exclusively",
            )
            assertSame(monitor, settings.monitor, "the lock screen was not put on the monitor it was given")
            assertEquals(
                SurfaceConfig.lockScreen().copy(namespace = LOCK_NAMESPACE),
                settings.config,
                "the lock screen did not ask for a lockScreen's settings with the values it was given",
            )
        }
    }

    private companion object {
        const val BAR_NAMESPACE = "kortex-preset-shown-bar"
        const val PANEL_NAMESPACE = "kortex-preset-shown-panel"
        const val BACKGROUND_NAMESPACE = "kortex-preset-shown-background"
        const val OSD_NAMESPACE = "kortex-preset-shown-osd"
        const val DOCK_NAMESPACE = "kortex-preset-asked-dock"
        const val APP_MENU_NAMESPACE = "kortex-preset-asked-app-menu"
        const val LOCK_NAMESPACE = "kortex-preset-asked-lock"
        const val MONITOR_NAME = "PRESET-1"

        // What Bar promises when left at its defaults.
        const val DEFAULT_BAR_THICKNESS = 32

        // Distinct from each other and from the sizes other tests use, so a value reaching the wrong field shows up
        // as a wrong number rather than an accidental match.
        const val PANEL_THICKNESS = 67
        const val THICKNESS = 41
        const val LENGTH = 307
        const val OSD_WIDTH = 239
        const val OSD_HEIGHT = 43
        val MARGINS = Margins(top = 3.dp, right = 5.dp, bottom = 7.dp, left = 11.dp)
    }
}
