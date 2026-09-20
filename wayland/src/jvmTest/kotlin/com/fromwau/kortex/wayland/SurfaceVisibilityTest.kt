package com.fromwau.kortex.wayland

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.getOrElse
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.delay

/**
 * `visible = false` takes a surface off screen without disposing anything: it hands back the space it reserved and
 * draws nothing, while its content keeps its state, keeps running and keeps reading the size it last had. `true` puts
 * the same surface back.
 *
 * The panel here reserves against the bottom edge, clear of the desktop's own bar at the top, and every surface it
 * places takes no keyboard focus.
 */
class SurfaceVisibilityTest {
    @Test
    fun `a panel taken off screen hands back the space it reserved, and reserves it again on its return`() {
        val visible = mutableStateOf(true)
        val watch = Watch()
        val before = Hyprctl.monitors().associateBy(HyprMonitor::name)

        onPanel(visible, watch = watch) { shell, _ ->
            val placed = panelGeometry()
            val monitor = assertNotNull(before[placed.monitor], "hyprctl did not report ${placed.monitor} before")
            assertReservesMore(shell, monitor, Edge.Bottom, THICKNESS)

            visible.value = false

            awaitOffScreen(shell)
            assertReservesMore(shell, monitor, Edge.Bottom, RESERVES_NOTHING)

            visible.value = true

            awaitOnScreen(shell)
            assertReservesMore(shell, monitor, Edge.Bottom, THICKNESS)
        }
    }

    @Test
    fun `a panel off screen draws nothing although its content invalidates, and draws again once it is back`() {
        val visible = mutableStateOf(true)
        val colour = mutableStateOf(Color.Red)
        val watch = Watch()

        onPanel(visible, colour, watch = watch) { shell, surface ->
            visible.value = false

            awaitOffScreen(shell)
            shell.pumpOrFail(SETTLE_MILLIS)
            val offScreen = surface.renders

            colour.value = Color.Blue

            shell.pumpOrFail(IDLE_WINDOW_MILLIS)
            assertEquals(offScreen, surface.renders, "a panel off screen drew the frame its content asked for")

            visible.value = true

            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { surface.renders > offScreen },
                "the panel never drew the frame its content had asked for while it was off screen",
            )
            val backOnScreen = surface.renders

            colour.value = Color.Green

            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { surface.renders > backOnScreen },
                "the panel drew nothing for the state that changed after it came back on screen",
            )
        }
    }

    @Test
    fun `content keeps running and keeps what it holds while its panel is off screen`() {
        val visible = mutableStateOf(true)
        val watch = Watch()

        onPanel(visible, watch = watch) { shell, _ ->
            visible.value = false

            awaitOffScreen(shell)
            val ticks = watch.ticks.get()

            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { watch.ticks.get() >= ticks + TICKS },
                "the effect running in the content stopped while the panel was off screen",
            )

            visible.value = true

            awaitOnScreen(shell)
            assertEquals(1, watch.compositions.get(), "the content was composed again from scratch")
            assertEquals(1, watch.held.get(), "the content lost what it held behind remember")
        }
    }

    @Test
    fun `content reads the size the panel last had while it is off screen`() {
        val visible = mutableStateOf(true)
        val watch = Watch()

        onPanel(visible, watch = watch) { shell, surface ->
            val placed = panelGeometry()
            val onScreen = IntSize(placed.logicalWidth, placed.logicalHeight)
            assertEquals(onScreen, surface.logicalSize, "the panel was placed at another size than hyprctl reports")

            visible.value = false

            awaitOffScreen(shell)
            val ticks = watch.ticks.get()

            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { watch.ticks.get() > ticks },
                "the content never read its size again while the panel was off screen",
            )
            assertEquals(onScreen, watch.size.get(), "content read another size while the panel was off screen")
            assertEquals(onScreen, surface.logicalSize, "the panel's own size changed while it was off screen")
        }
    }

    @Test
    fun `a panel put back on screen is the same surface, still drawing the content it was given`() {
        val visible = mutableStateOf(true)
        val watch = Watch()

        onPanel(visible, watch = watch) { shell, surface ->
            val placed = panelGeometry()

            visible.value = false

            awaitOffScreen(shell)

            visible.value = true

            awaitOnScreen(shell)
            assertSame(surface, shell.shownSurfaces.single(), "the panel was made again instead of put back")
            assertEquals(placed.address, panelGeometry().address, "the round trip made a new layer surface")
            assertEquals(1, watch.compositions.get(), "the content was composed again from scratch")
        }
    }

    @Test
    fun `a call that asks for a panel off screen never puts one on screen until it asks for one`() {
        val visible = mutableStateOf(false)
        val watch = Watch()
        val before = Hyprctl.monitors().associateBy(HyprMonitor::name)

        onPanel(visible, watch = watch) { shell, surface ->
            assertTrue(surface.hidden, "a panel its call asked to keep off screen was put on screen")
            assertEquals(0, surface.renders, "a panel that was never on screen drew a frame")
            val placed = panelGeometry()
            val monitor = assertNotNull(before[placed.monitor], "hyprctl did not report ${placed.monitor} before")
            assertReservesMore(shell, monitor, Edge.Bottom, RESERVES_NOTHING)

            visible.value = true

            awaitOnScreen(shell)
            assertReservesMore(shell, monitor, Edge.Bottom, THICKNESS)
            assertTrue(surface.renders > 0, "the panel drew nothing once it was put on screen")
        }
    }

    @Test
    fun `a panel put back on screen at another thickness draws at the size that came back with it`() {
        val visible = mutableStateOf(true)
        val thickness = mutableStateOf(THICKNESS)
        val watch = Watch()

        onPanel(visible, thickness = thickness, watch = watch) { shell, surface ->
            visible.value = false

            awaitOffScreen(shell)

            // Both in one recomposition, so the configure that carries the new size is the one putting it back.
            thickness.value = THICKER
            visible.value = true

            awaitOnScreen(shell)
            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { Screen.geometry(NAMESPACE)?.logicalHeight == THICKER },
                "the compositor never placed the panel at the thickness it came back with",
            )
            val shown = panelGeometry()
            val scale = surface.currentBufferScale
            assertEquals(
                IntSize(shown.logicalWidth * scale, shown.logicalHeight * scale),
                surface.bufferSize,
                "the panel came back drawing at the size it had before",
            )
            assertEquals(THICKER, surface.logicalSize.height, "content reads the size the panel had before")
        }
    }

    @Test
    fun `a panel that grows thicker while off screen reserves nothing until it comes back`() {
        val visible = mutableStateOf(true)
        val thickness = mutableStateOf(THICKNESS)
        val watch = Watch()
        val before = Hyprctl.monitors().associateBy(HyprMonitor::name)

        onPanel(visible, thickness = thickness, watch = watch) { shell, _ ->
            val placed = panelGeometry()
            val monitor = assertNotNull(before[placed.monitor], "hyprctl did not report ${placed.monitor} before")
            assertReservesMore(shell, monitor, Edge.Bottom, THICKNESS)

            visible.value = false

            awaitOffScreen(shell)
            assertReservesMore(shell, monitor, Edge.Bottom, RESERVES_NOTHING)

            thickness.value = THICKER

            // Long enough for a zone this pass sent to reach the compositor and show up in what it reserves.
            shell.pumpOrFail(SETTLE_MILLIS)
            assertReservesMore(shell, monitor, Edge.Bottom, RESERVES_NOTHING)

            visible.value = true

            awaitOnScreen(shell)
            assertReservesMore(shell, monitor, Edge.Bottom, THICKER)
        }
    }

    @Test
    fun `a surface created off screen reserves nothing from its first commit on`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }
        val config = SurfaceConfig.panel(edge = Edge.Bottom, thickness = THICKNESS.dp).copy(namespace = NAMESPACE)
        val before = Hyprctl.monitors().associateBy(HyprMonitor::name)

        display.use {
            val surface = KortexSurface
                .create(display, config, visible = false)
                .getOrElse { error -> fail("the surface was not created: $error") }

            surface.use {
                // A reservation lands a frame after the commit that asks for it, so give one the frames to land in.
                surface.pumpOrFail(SETTLE_MILLIS)

                val placed = assertNotNull(Screen.geometry(NAMESPACE), "hyprctl never reported $NAMESPACE")
                val monitor = assertNotNull(before[placed.monitor], "hyprctl did not report ${placed.monitor} before")
                assertEquals(
                    monitor.reservedAgainst(Edge.Bottom),
                    Hyprctl.monitor(monitor.name).reservedAgainst(Edge.Bottom),
                    "a surface created off screen reserved space against the edge it is anchored to",
                )
            }
        }
    }

    @Test
    fun `a config that cannot be placed ends a surface that is off screen, as it would one on screen`() {
        val visible = mutableStateOf(true)
        val anchor = mutableStateOf(BOTTOM_BAR)
        val reports = CopyOnWriteArrayList<Result<SurfaceEnd, SurfaceError<Nothing>>>()
        val unspannable = setOf(Edge.Bottom, Edge.Left)
        val content: @Composable KortexApplicationScope.() -> Unit = {
            TestSurface<Nothing>(
                NAMESPACE,
                anchor = anchor.value,
                width = SPAN_ANCHORED_AXIS.dp,
                visible = visible.value,
                onClose = { reports += it },
            )
        }
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use {
            KortexShell.createApplicationOrFail(display, content).useOrFail { shell ->
                awaitPlaced(shell)

                visible.value = false

                awaitOffScreen(shell)

                anchor.value = unspannable

                assertTrue(
                    shell.pumpOrFail(PUMP_MILLIS) { reports.isNotEmpty() },
                    "the config an off-screen surface cannot be placed with reported nothing",
                )
                assertEquals(
                    listOf(Err(SurfaceError.Failed(KortexError.UnspannableAxis(Axis.Horizontal, unspannable)))),
                    reports.toList(),
                    "a config that leaves an axis unspannable did not end the off-screen surface with that reason",
                )
                assertEquals(
                    Ok(Unit),
                    display.requireAlive(),
                    "the rejected config reached the compositor, which answered it by dropping the connection",
                )
            }
        }
    }

    /** A bottom panel whose content reports itself to [watch] from an effect that outlives going off screen. */
    @Composable
    private fun WatchedPanel(
        visible: Boolean,
        thickness: Int,
        colour: Color,
        watch: Watch,
    ) {
        Panel<Nothing>(
            edge = Edge.Bottom,
            thickness = thickness.dp,
            namespace = NAMESPACE,
            visible = visible,
        ) {
            val panel = this
            val held = remember { watch.compositions.incrementAndGet() }
            LaunchedEffect(Unit) {
                while (true) {
                    watch.held.set(held)
                    watch.size.set(panel.size)
                    watch.ticks.incrementAndGet()
                    delay(TICK_MILLIS)
                }
            }
            Box(Modifier.fillMaxSize().background(colour))
        }
    }

    /** Runs one bottom panel the states drive, and hands [body] the shell and the surface the panel was placed as. */
    private fun onPanel(
        visible: MutableState<Boolean>,
        colour: MutableState<Color> = mutableStateOf(Color.Red),
        thickness: MutableState<Int> = mutableStateOf(THICKNESS),
        watch: Watch,
        body: (KortexShell, KortexSurface) -> Unit,
    ) {
        onApplication({ WatchedPanel(visible.value, thickness.value, colour.value, watch) }) { shell ->
            awaitPlaced(shell)
            body(shell, shell.shownSurfaces.single())
        }
    }

    /** What hyprctl reports of the panel, which it lists whether or not the panel is on screen. */
    private fun panelGeometry(): LayerGeometry =
        assertNotNull(Screen.awaitGeometry(NAMESPACE), "hyprctl never reported $NAMESPACE")

    // Both wait on whatever surface the shell holds now, not on the one the test was handed, so a change that made
    // a surface of its own is caught by the assertion that looks for it rather than by a wait that times out.
    private fun awaitOffScreen(shell: KortexShell) {
        assertTrue(
            shell.pumpOrFail(PUMP_MILLIS) { shell.shownSurfaces.singleOrNull()?.hidden == true },
            "the panel was never taken off screen",
        )
    }

    private fun awaitOnScreen(shell: KortexShell) {
        assertTrue(
            shell.pumpOrFail(PUMP_MILLIS) { shell.shownSurfaces.singleOrNull()?.hidden == false },
            "the panel was never put back on screen",
        )
    }

    /** What the panel's content publishes: how often it was composed, what it holds, that it runs, and its size. */
    private class Watch {
        val compositions = AtomicInteger()
        val held = AtomicInteger()
        val ticks = AtomicInteger()
        val size = AtomicReference(IntSize.Zero)
    }

    private companion object {
        const val NAMESPACE = "kortex-visibility"
        const val THICKNESS = 18
        const val THICKER = 30
        const val RESERVES_NOTHING = 0
        const val SPAN_ANCHORED_AXIS = 0
        const val PUMP_MILLIS = 4_000L
        const val SETTLE_MILLIS = 500L

        // A frame fires at the output's refresh rate, so a panel that draws at all draws many times in this window.
        const val IDLE_WINDOW_MILLIS = 1_000L

        const val TICK_MILLIS = 50L
        const val TICKS = 3

        // Clear of the desktop's own bar at the top, which already reserves space there.
        val BOTTOM_BAR = setOf(Edge.Bottom, Edge.Left, Edge.Right)
    }
}
