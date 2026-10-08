package com.fromwau.kortex.wayland

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Hovers a [HoverTooltip] on a bar that reserves nothing, handing the bar's content pointer events directly, so the
 * user's pointer is never moved and no focus is taken.
 */
class HoverTooltipTest {
    @Test
    fun `a tooltip opens once the pointer rests, below the pointer and clear of it, sized by what it shows`() {
        onHoveredBar(Edge.Top) { shell, hover ->
            hover.rest()

            awaitTooltip(shell, shown = true)
            val tooltip = tooltipRole(shell)
            assertEquals(TIP_SIZE, IntSize(tooltip.logicalWidth, tooltip.logicalHeight), "the tooltip is not its size")
            assertEquals(
                IntOffset(POINTER.x, POINTER.y + cursorSize), tooltip.placedAt,
                "the tooltip did not open below the pointer, clear of it",
            )
        }
    }

    @Test
    fun `with no room below the pointer the tooltip opens above it`() {
        onHoveredBar(Edge.Bottom) { shell, hover ->
            hover.rest()

            awaitTooltip(shell, shown = true)
            assertEquals(
                IntOffset(POINTER.x, POINTER.y - TIP_SIZE.height), tooltipRole(shell).placedAt,
                "the tooltip did not open above the pointer at the bottom of the screen",
            )
        }
    }

    @Test
    fun `a pointer that has not rested long enough shows no tooltip`() {
        onHoveredBar(Edge.Top) { shell, hover ->
            hover.rest()

            shell.pumpOrFail(UNDER_DELAY_MILLIS)

            assertEquals(1, shell.shownSurfaces.size, "a tooltip opened before the pointer had rested long enough")
        }
    }

    @Test
    fun `leaving the area takes the tooltip away`() {
        onHoveredBar(Edge.Top) { shell, hover ->
            hover.rest()
            awaitTooltip(shell, shown = true)

            hover.send(PointerEventType.Exit)

            awaitTooltip(shell, shown = false)
        }
    }

    @Test
    fun `a press takes the tooltip away until the pointer has left and come back`() {
        onHoveredBar(Edge.Top) { shell, hover ->
            hover.rest()
            awaitTooltip(shell, shown = true)

            hover.send(PointerEventType.Press)
            awaitTooltip(shell, shown = false)
            hover.send(PointerEventType.Release)
            hover.send(PointerEventType.Move, nudged = true)

            assertFalse(
                shell.pumpOrFail(OVER_DELAY_MILLIS) { shell.shownSurfaces.size > 1 },
                "the tooltip came back over the area it was pressed in",
            )

            hover.send(PointerEventType.Exit)
            hover.rest()
            awaitTooltip(shell, shown = true)
        }
    }

    @Test
    fun `a press the content takes for itself still takes the tooltip away`() {
        onHoveredBar(Edge.Top, content = { SwallowsPresses() }) { shell, hover ->
            hover.rest()
            awaitTooltip(shell, shown = true)

            hover.send(PointerEventType.Press)

            awaitTooltip(shell, shown = false)
        }
    }

    /** Content that consumes every press, as a clickable widget does. */
    @Composable
    private fun SwallowsPresses() {
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type == PointerEventType.Press) event.changes.forEach { it.consume() }
                        }
                    }
                },
        )
    }

    /** Hands a bar's content pointer events at [POINTER], at the scale that content is drawn at. */
    private class Hover(private val scene: SurfaceScene) {
        private var timeMillis = 0L

        /** Sends [type] at [POINTER], or a pixel right of it where [nudged]: content hears no move that stays put. */
        fun send(
            type: PointerEventType,
            nudged: Boolean = false,
        ) {
            val scale = scene.composition.density.density
            val x = if (nudged) POINTER.x + 1 else POINTER.x
            timeMillis += EVENT_GAP_MILLIS
            scene.composition.sendPointerEvent(type, Offset(x * scale, POINTER.y * scale), timeMillis)
        }

        fun rest() {
            send(PointerEventType.Enter)
            send(PointerEventType.Move)
        }
    }

    /** Runs [block] on a bar along [edge] whose whole area has a tooltip, once the bar is on screen. */
    private fun onHoveredBar(
        edge: Edge,
        content: @Composable () -> Unit = { Grey() },
        block: (KortexShell, Hover) -> Unit,
    ) {
        val slot = AtomicReference<SurfaceSlot?>(null)
        val content: @Composable KortexApplicationScope.() -> Unit = {
            LayerSurface(
                namespace = NAMESPACE,
                anchor = setOf(edge, Edge.Left, Edge.Right),
                height = Length.Of(BAR_THICKNESS),
                exclusiveZone = ExclusiveZone.Overlap,
            ) {
                val own = LocalSurfaceSlot.current
                SideEffect { slot.set(own) }
                HoverTooltip(
                    tooltip = { Box(Modifier.size(TIP_WIDTH, TIP_HEIGHT)) },
                    modifier = Modifier.fillMaxSize(),
                    delay = DELAY_MILLIS.milliseconds,
                    content = content,
                )
            }
        }

        onApplication(content) { shell ->
            awaitPlaced(shell)
            val scene = assertNotNull(slot.get()?.scene, "the bar's content never saw the slot it runs in")
            block(shell, Hover(scene))
        }
    }

    private fun awaitTooltip(
        shell: KortexShell,
        shown: Boolean,
    ) {
        val surfaces = if (shown) 2 else 1
        assertTrue(
            shell.pumpOrFail(PUMP_MILLIS) { shell.shownSurfaces.size == surfaces },
            if (shown) "the tooltip never opened" else "the tooltip never went away",
        )
    }

    private fun tooltipRole(shell: KortexShell): XdgPopupSurface = assertIs<XdgPopupSurface>(
        shell.shownSurfaces.last().role,
        "the tooltip is not a popup over the bar",
    )

    private companion object {
        const val NAMESPACE = "kortex-tooltip-test"

        val BAR_THICKNESS = 32.dp
        val TIP_WIDTH = 120.dp
        val TIP_HEIGHT = 30.dp
        val TIP_SIZE = IntSize(TIP_WIDTH.toLogicalPx(), TIP_HEIGHT.toLogicalPx())

        val POINTER = IntOffset(60, 10)

        const val DELAY_MILLIS = 300L
        const val UNDER_DELAY_MILLIS = 100L
        const val OVER_DELAY_MILLIS = 600L
        const val EVENT_GAP_MILLIS = 10L
        const val PUMP_MILLIS = 6_000L
    }
}
