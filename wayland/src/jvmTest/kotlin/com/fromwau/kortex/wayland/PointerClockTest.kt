package com.fromwau.kortex.wayland

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.getOrElse
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * That the time on a pointer event still advances by the time content reads it.
 *
 * `KortexScene.sendPointerEvent` states the contract on its own parameter: the origin does not matter, only
 * that it advances. Compose reads it off every change for double click, long press and fling velocity, and
 * nothing else in this suite asserts on it, so a stopped clock is invisible: every gesture test keeps
 * passing, timed against nothing. The virtual pointer sent a constant zero for every event it had ever
 * sent, and [PointerInput] hands the wire's stamp straight through, so that zero reached content.
 *
 * Two motions with a real pause between them, because a press and the release after it land in the same
 * millisecond whether the clock runs or not, and so cannot tell a running one from a stopped one.
 *
 * Drives the user's pointer across their screen, so like the window tests this runs only in a session kept
 * free for it.
 */
class PointerClockTest {
    @Test
    fun `two motions a pause apart reach content with the pause between their timestamps`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use { wayland ->
            val manager = VirtualPointerManager.bind(wayland)
                .getOrElse { error -> fail("virtual pointer manager bind failed: $error") }
            val monitor = assertNotNull(Hyprctl.monitors().firstOrNull(), "hyprctl monitors reported no usable monitor")
            val moved = CopyOnWriteArrayList<Long>()

            onBareSurface(wayland, CONFIG) { bar, scene ->
                scene.setContent {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        if (event.type != PointerEventType.Move) continue
                                        moved += event.changes.first().uptimeMillis
                                    }
                                }
                            },
                    )
                }
                bar.pumpOrFail(SETTLE_MILLIS)
                wayland.roundtrip()

                val geometry = assertNotNull(Screen.geometry(NAMESPACE), "hyprctl layers did not report $NAMESPACE")
                val targetX = geometry.x + geometry.logicalWidth / 2
                val targetY = geometry.y + geometry.logicalHeight / 2

                manager.createVirtualPointer().use { pointer ->
                    try {
                        // Off the bar first: the compositor re-evaluates pointer focus on motion, so a
                        // cursor already parked on the target would never enter the surface.
                        pointer.moveTo(monitor, monitor.logicalWidth / 2, monitor.logicalHeight - 1)
                        bar.pumpOrFail(SETTLE_MILLIS)

                        // Arriving on the surface is a wl_pointer.enter, which carries no time and is no
                        // motion: both samples have to be moves made once the pointer is already inside.
                        pointer.moveTo(monitor, targetX, targetY)
                        bar.pumpOrFail(SETTLE_MILLIS)

                        pointer.moveTo(monitor, targetX + STEP, targetY)
                        assertTrue(
                            bar.pumpOrFail(PUMP_MILLIS) { moved.isNotEmpty() },
                            "no motion reached the content at all, so its timestamps prove nothing",
                        )

                        // Real time has to pass on the clock the pointer stamps from, not just on this
                        // thread, which is the whole difference a constant hides.
                        Thread.sleep(PAUSE_MILLIS)

                        pointer.moveTo(monitor, targetX + STEP + STEP, targetY)
                        assertTrue(
                            bar.pumpOrFail(PUMP_MILLIS) { moved.size >= 2 },
                            "the second motion never reached the content; times so far: $moved",
                        )
                    } finally {
                        // Unconditional, so a failure above cannot leave the cursor on the target and deny
                        // the next test's own move here its enter.
                        pointer.moveTo(monitor, monitor.logicalWidth / 2, monitor.logicalHeight - 1)
                        bar.pumpOrFail(SETTLE_MILLIS)
                    }
                }

                assertTrue(
                    moved.first() > 0L,
                    "the first motion reached the content stamped $moved, and a zero is the stopped clock",
                )
                assertTrue(
                    moved.last() - moved.first() >= PAUSE_MILLIS - SLACK_MILLIS,
                    "a ${PAUSE_MILLIS}ms pause moved the content's clock by ${moved.last() - moved.first()}ms " +
                        "(times: $moved)",
                )
            }
        }
    }

    private companion object {
        const val NAMESPACE = "kortex-pointer-clock"
        const val BAR_HEIGHT = 32
        const val SETTLE_MILLIS = 300L
        const val PUMP_MILLIS = 3000L

        /** Long enough that no scheduling jitter can account for it, short enough not to pad the suite. */
        const val PAUSE_MILLIS = 250L

        /** What `Thread.sleep` and the round trips either side of it are allowed to be off by. */
        const val SLACK_MILLIS = 50L

        /** How far each motion inside the surface goes: any distance the compositor will report as a move. */
        const val STEP = 2

        val CONFIG = SurfaceConfig.panel(edge = Edge.Top, thickness = BAR_HEIGHT.dp).copy(namespace = NAMESPACE)
    }
}
