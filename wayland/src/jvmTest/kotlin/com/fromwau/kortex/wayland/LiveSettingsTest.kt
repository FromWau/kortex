package com.fromwau.kortex.wayland

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.getOrElse
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A changed argument reaches the surface already on screen: the compositor moves, resizes and relevels the layer
 * surface it already has, and the composition drawn on it keeps running and keeps its state.
 *
 * Every surface here takes no keyboard focus, and the two that reserve space give it back within the test.
 */
class LiveSettingsTest {
    @Test
    fun `a changed width and height resize the surface, and its buffers follow the configure`() {
        val size = mutableStateOf(IntSize(SPECK, SPECK))
        val placements = AtomicInteger()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            TestSurface(
                NAMESPACE,
                width = Length.Of(size.value.width.dp),
                height = Length.Of(size.value.height.dp),
            ) {
                remember { placements.incrementAndGet() }
            }
        }

        onSurface(content) { shell, placed ->
            size.value = IntSize(WIDER, TALLER)

            val moved = awaitGeometry(shell) { it.logicalWidth == WIDER && it.logicalHeight == TALLER }
            assertChangedInPlace(placed, moved, placements)
            val surface = shell.shownSurfaces.single()
            val buffer = IntSize(WIDER * surface.currentBufferScale, TALLER * surface.currentBufferScale)
            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { surface.bufferSize == buffer },
                "the buffers never followed the configure the resize produced: ${surface.bufferSize}, not $buffer",
            )
        }
    }

    @Test
    fun `changed margins move the surface away from the edges it is anchored to`() {
        val margins = mutableStateOf(Margins.None)
        val placements = AtomicInteger()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            TestSurface(NAMESPACE, margins = margins.value) { remember { placements.incrementAndGet() } }
        }

        onSurface(content) { shell, placed ->
            margins.value = Margins(bottom = MARGIN_BOTTOM.dp, right = MARGIN_RIGHT.dp)

            val moved = awaitGeometry(shell) { it.x == placed.x - MARGIN_RIGHT }
            assertChangedInPlace(placed, moved, placements)
            assertEquals(placed.x - MARGIN_RIGHT, moved.x, "the right margin did not move the surface left")
            assertEquals(placed.y - MARGIN_BOTTOM, moved.y, "the bottom margin did not move the surface up")
        }
    }

    @Test
    fun `a change reaches a surface whose content asks for no frame of its own`() {
        val margins = mutableStateOf(Margins.None)
        val content: @Composable KortexApplicationScope.() -> Unit = {
            TestSurface(NAMESPACE, margins = margins.value)
        }

        onSurface(content) { shell, placed ->
            margins.value = Margins(bottom = MARGIN_BOTTOM.dp, right = MARGIN_RIGHT.dp)

            val moved = awaitGeometry(shell) { it.y == placed.y - MARGIN_BOTTOM }
            assertEquals(placed.address, moved.address, "the change made a new layer surface")
            assertEquals(placed.x - MARGIN_RIGHT, moved.x, "the change waited for a frame that never came")
        }
    }

    @Test
    fun `a changed anchor moves the surface to the other edge`() {
        val anchor = mutableStateOf(setOf(Edge.Bottom, Edge.Right))
        val placements = AtomicInteger()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            TestSurface(NAMESPACE, anchor = anchor.value) { remember { placements.incrementAndGet() } }
        }

        onSurface(content) { shell, placed ->
            anchor.value = setOf(Edge.Bottom, Edge.Left)

            val moved = awaitGeometry(shell) { it.x != placed.x }
            assertChangedInPlace(placed, moved, placements)
            val monitor = Hyprctl.monitor(moved.monitor)
            assertEquals(monitor.usableX, moved.x, "the surface did not land against the edge it moved to")
            assertEquals(placed.y, moved.y, "the surface moved on the axis whose anchor did not change")
        }
    }

    @Test
    fun `a changed layer moves the surface to another level`() {
        val layer = mutableStateOf(Layer.Overlay)
        val placements = AtomicInteger()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            TestSurface(NAMESPACE, layer = layer.value) { remember { placements.incrementAndGet() } }
        }

        onSurface(content) { shell, placed ->
            assertEquals(Layer.Overlay, placed.layer, "the surface was not placed on the level it asked for")

            layer.value = Layer.Bottom

            val moved = awaitGeometry(shell) { it.layer == Layer.Bottom }
            assertChangedInPlace(placed, moved, placements)
        }
    }

    @Test
    fun `a changed exclusiveZone changes what the monitor reserves, and Yield gives it all back`() {
        val zone = mutableStateOf<ExclusiveZone>(ExclusiveZone.Reserve(ZONE.dp))
        val placements = AtomicInteger()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            TestSurface(
                NAMESPACE,
                anchor = BOTTOM_BAR,
                width = Length.WholeAxis,
                height = Length.Of(WIDER_ZONE.dp),
                exclusiveZone = zone.value,
            ) {
                remember { placements.incrementAndGet() }
            }
        }
        val before = Hyprctl.monitors().associateBy(HyprMonitor::name)

        onSurface(content) { shell, placed ->
            val monitor = assertNotNull(before[placed.monitor], "hyprctl did not report ${placed.monitor} before")
            assertReservesMore(shell, monitor, Edge.Bottom, ZONE)

            zone.value = ExclusiveZone.Reserve(WIDER_ZONE.dp)

            assertReservesMore(shell, monitor, Edge.Bottom, WIDER_ZONE)
            assertChangedInPlace(placed, awaitGeometry(shell), placements)

            zone.value = ExclusiveZone.Yield

            assertReservesMore(shell, monitor, Edge.Bottom, RESERVES_NOTHING)
            assertChangedInPlace(placed, awaitGeometry(shell), placements)
        }
    }

    @Test
    fun `a corner-anchored surface's reservation follows its anchor and exclusiveEdge, and null stops it`() {
        val corner = mutableStateOf(RESERVING_RIGHT)
        val placements = AtomicInteger()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            TestSurface(
                NAMESPACE,
                anchor = corner.value.anchor,
                width = Length.Of(WIDER.dp),
                height = Length.Of(TALLER.dp),
                exclusiveZone = ExclusiveZone.Reserve(ZONE.dp),
                exclusiveEdge = corner.value.exclusiveEdge,
            ) {
                remember { placements.incrementAndGet() }
            }
        }
        val before = Hyprctl.monitors().associateBy(HyprMonitor::name)

        onSurface(content) { shell, placed ->
            val monitor = assertNotNull(before[placed.monitor], "hyprctl did not report ${placed.monitor} before")
            assertReservesMore(shell, monitor, Edge.Right, ZONE)

            // The edge it moves to is pinned only by the anchor it moves to, so the anchor must reach Hyprland first.
            corner.value = RESERVING_LEFT

            assertReservesMore(shell, monitor, Edge.Left, ZONE)
            assertReservesMore(shell, monitor, Edge.Right, RESERVES_NOTHING)
            assertChangedInPlace(placed, awaitGeometry(shell), placements)

            corner.value = RESERVING_LEFT.copy(exclusiveEdge = null)

            assertReservesMore(shell, monitor, Edge.Left, RESERVES_NOTHING)
            assertChangedInPlace(placed, awaitGeometry(shell), placements)
        }
    }

    @Test
    fun `a change the compositor could not place ends the surface with the reason, and the connection lives on`() {
        val anchor = mutableStateOf(BOTTOM_BAR)
        val bar = SurfaceState()
        val unspannable = setOf(Edge.Bottom, Edge.Left)
        val content: @Composable KortexApplicationScope.() -> Unit = {
            TestSurface(
                NAMESPACE,
                anchor = anchor.value,
                width = Length.WholeAxis,
                state = bar,
            )
        }
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use {
            KortexShell.createApplicationOrFail(display, content).useOrFail { shell ->
                awaitPlaced(shell)

                anchor.value = unspannable

                assertTrue(
                    shell.pumpOrFail(PUMP_MILLIS) { bar.hasEnded },
                    "the rejected change ended nothing",
                )
                bar.assertEnded(
                    Err(KortexError.UnspannableAxis(Axis.Horizontal, unspannable)),
                    "a change that leaves an axis unspannable did not end the surface with that reason",
                )
                assertTrue(shell.shownSurfaces.isEmpty(), "the surface a rejected change ended is listed as shown")
                assertEquals(
                    Ok(Unit),
                    display.requireAlive(),
                    "the rejected change reached the compositor, which answered it by dropping the connection",
                )
            }
        }
    }

    /** Runs [content]'s one surface and hands [body] the shell and what hyprctl says of the surface as placed. */
    private fun onSurface(
        content: @Composable KortexApplicationScope.() -> Unit,
        body: (KortexShell, LayerGeometry) -> Unit,
    ) {
        onApplication(content) { shell ->
            awaitPlaced(shell)
            body(shell, awaitGeometry(shell))
        }
    }

    /** Pumps [shell] until hyprctl reports the surface and [settled] holds of what it reports. */
    private fun awaitGeometry(
        shell: KortexShell,
        settled: (LayerGeometry) -> Boolean = { true },
    ): LayerGeometry {
        var found: LayerGeometry? = null
        val reached = shell.pumpOrFail(PUMP_MILLIS) {
            found = Screen.geometry(NAMESPACE)
            found?.let(settled) == true
        }
        assertTrue(reached, "hyprctl never reported $NAMESPACE as the change asked for; it last reported $found")
        return assertNotNull(found, "hyprctl never reported $NAMESPACE at all")
    }

    /** Fails unless the surface hyprctl reports now is the one [placed], still drawing the content it was given. */
    private fun assertChangedInPlace(
        placed: LayerGeometry,
        now: LayerGeometry,
        placements: AtomicInteger,
    ) {
        assertEquals(placed.address, now.address, "the change made a new layer surface instead of changing this one")
        assertEquals(1, placements.get(), "the content composed again, so what it held behind remember did not survive")
    }

    /** A corner anchor, and which of the two edges it pins the exclusive zone is measured from. */
    private data class Corner(
        val anchor: Set<Edge>,
        val exclusiveEdge: Edge?,
    )

    private companion object {
        val RESERVING_RIGHT = Corner(setOf(Edge.Bottom, Edge.Right), Edge.Right)
        val RESERVING_LEFT = Corner(setOf(Edge.Bottom, Edge.Left), Edge.Left)

        const val NAMESPACE = "kortex-live-settings"
        const val PUMP_MILLIS = 4_000L
        const val SPECK = 8
        const val RESERVES_NOTHING = 0

        // Distinct from each other, so a width that reached the height's place shows up as a wrong number.
        const val WIDER = 24
        const val TALLER = 16
        const val MARGIN_RIGHT = 32
        const val MARGIN_BOTTOM = 20
        const val ZONE = 12
        const val WIDER_ZONE = 20

        // Clear of the desktop's own bar at the top, which already reserves space there.
        val BOTTOM_BAR = setOf(Edge.Bottom, Edge.Left, Edge.Right)
    }
}
