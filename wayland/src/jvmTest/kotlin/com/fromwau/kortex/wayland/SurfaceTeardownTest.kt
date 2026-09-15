package com.fromwau.kortex.wayland

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.getOrElse
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Tears one surface down while a second stays live and keeps taking pointer input, on one connection: the sibling
 * still takes a click, the teardown costs the connection nothing, and the closed scene records no crash.
 *
 * It cannot see the closed surface's own `wl_pointer` outlive it. On Hyprland 0.56.2 the surface's released
 * `wl_seat` takes that pointer out of the devices the compositor sends clicks to, so this test passes with the
 * pointer left alive, and with it left alive after its listener stubs are freed.
 */
class SurfaceTeardownTest {
    @Test
    fun `a click still reaches the surface that stays after a sibling surface closes under the pointer`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }
        val closeRequested = mutableStateOf(false)
        val clicks = AtomicInteger()
        val menuHovers = AtomicInteger()

        display.use { wayland ->
            val manager = VirtualPointerManager.bind(wayland)
                .getOrElse { error -> fail("virtual pointer manager bind failed: $error") }
            val monitor = assertNotNull(Hyprctl.monitors().firstOrNull(), "hyprctl monitors reported no usable monitor")
            val shell = KortexShell.createApplicationOrFail(wayland, content(clicks, closeRequested, menuHovers))

            shell.useOrFail {
                val panelNamespace = awaitPanel(shell)
                val menu = awaitMenu(shell)
                val before = shell.shownSurfaces.size
                // Kept past the menu's own removal below, so its scene can still be asked whether it crashed.
                val menuSurface = shell.shownSurfaces.last()

                manager.createVirtualPointer().use { pointer ->
                    try {
                        // Off both surfaces first: the compositor re-evaluates pointer focus on motion, so a
                        // cursor already parked on these coordinates would never enter the new surface.
                        moveTo(shell, pointer, monitor, monitor.logicalWidth / 2, monitor.logicalHeight - 1)
                        // The menu must hold the pointer focus when it goes: the leave that losing focus
                        // produces is what its own listener would still be dispatched afterwards.
                        moveTo(
                            shell, pointer, monitor,
                            menu.x + menu.logicalWidth / 2, menu.y + menu.logicalHeight / 2,
                        )

                        // Asserts the premise the comment above only states: without this, a layout change
                        // that moved the menu off the pointer would silently degrade this into a test that
                        // closes a surface nobody was pointing at, and it would keep passing.
                        val entered = shell.pumpOrFail(PUMP_TIMEOUT_MILLIS) { menuHovers.get() > 0 }
                        assertTrue(entered, "the menu never received the pointer enter this test's premise needs")

                        // Nothing on the test thread calls close(): only this flag can drop the menu, and it
                        // does so from the composition's own thread.
                        closeRequested.value = true
                        val dropped = shell.pumpOrFail(PUMP_TIMEOUT_MILLIS) { shell.shownSurfaces.size == before - 1 }
                        assertTrue(dropped, "the application never dropped the menu after its content called close()")

                        val panel =
                            assertNotNull(Screen.geometry(panelNamespace), "the panel went away with the menu")
                        pointer.clickAt(monitor, panel.x + TARGET_DP / 2, panel.y + TARGET_DP / 2)

                        val delivered = shell.pumpOrFail(PUMP_TIMEOUT_MILLIS) { clicks.get() == 1 }
                        wayland.requireAlive().getOrElse { error ->
                            fail("wayland protocol error after the menu was torn down: $error")
                        }
                        assertTrue(delivered, "a click never reached the panel that outlived the menu")
                        assertEquals(1, clicks.get(), "one press and release must be one click")
                        assertNull(
                            menuSurface.crash,
                            "the panel's click also reached the closed menu's own, already-torn-down scene",
                        )
                    } finally {
                        // Unconditional, so an assertion failing above still can't leave the cursor on a
                        // target and deny the next test's own move here the enter it depends on.
                        moveTo(shell, pointer, monitor, monitor.logicalWidth / 2, monitor.logicalHeight - 1)
                    }
                }
            }
        }
    }

    /** Moves the pointer and lets the compositor deliver the enter and leave the move produces. */
    private fun moveTo(shell: KortexShell, pointer: VirtualPointer, monitor: HyprMonitor, x: Int, y: Int) {
        pointer.moveTo(monitor, x, y)
        shell.pumpOrFail(SETTLE_MILLIS)
    }

    /**
     * A clickable panel per monitor, and an [AppMenu] closing itself once [closeRequested] flips.
     *
     * The menu is an [AppMenu], not an [Osd], so the surface that goes owns a `wl_keyboard` as well as a
     * `wl_pointer` and both teardown paths are exercised.
     */
    private fun content(
        clicks: AtomicInteger,
        closeRequested: MutableState<Boolean>,
        hovers: AtomicInteger,
    ): @Composable KortexApplicationScope.() -> Unit = {
        val monitors by rememberMonitors()
        for (monitor in monitors) {
            key(monitor) {
                Show(
                    object : Panel<Nothing>(
                        monitor = monitor,
                        edge = Edge.Top,
                        thickness = PANEL_HEIGHT.dp,
                        namespace = "$PANEL_NAMESPACE-${monitor.name}",
                    ) {
                        @Composable
                        override fun invoke() {
                            Box(Modifier.size(TARGET_DP.dp).clickable { clicks.incrementAndGet() })
                        }
                    },
                )
            }
        }
        Show(
            object : AppMenu<Nothing>(width = MENU_SIZE.dp, height = MENU_SIZE.dp, namespace = MENU_NAMESPACE) {
                @Composable
                override fun invoke() {
                    val requested = closeRequested.value
                    LaunchedEffect(requested) { if (requested) close() }
                    Box(
                        Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        if (event.type == PointerEventType.Enter) hovers.incrementAndGet()
                                    }
                                }
                            },
                    )
                }
            },
        )
    }

    /** Pumps [shell] until the panel reaches `hyprctl layers`, and returns the namespace it was filed under. */
    private fun awaitPanel(shell: KortexShell): String {
        val appeared = shell.pumpOrFail(PUMP_TIMEOUT_MILLIS) { panelNamespaces().isNotEmpty() }
        assertTrue(appeared, "hyprctl layers never reported a $PANEL_NAMESPACE- namespace")
        return panelNamespaces().first()
    }

    /** Pumps [shell] until the menu reaches `hyprctl layers`, and returns where it landed. */
    private fun awaitMenu(shell: KortexShell): LayerGeometry {
        val appeared = shell.pumpOrFail(PUMP_TIMEOUT_MILLIS) { Screen.geometry(MENU_NAMESPACE) != null }
        assertTrue(appeared, "hyprctl layers never reported $MENU_NAMESPACE")
        return assertNotNull(Screen.geometry(MENU_NAMESPACE))
    }

    private fun panelNamespaces(): Set<String> =
        Hyprctl.namespaces().filterTo(mutableSetOf()) { it.startsWith("$PANEL_NAMESPACE-") }

    private companion object {
        const val PANEL_NAMESPACE = "kortex-teardown-panel"
        const val MENU_NAMESPACE = "kortex-teardown-menu"

        const val PANEL_HEIGHT = 32
        const val MENU_SIZE = 160
        const val TARGET_DP = 16

        const val PUMP_TIMEOUT_MILLIS = 4000L
        const val SETTLE_MILLIS = 300L
    }
}
