package com.fromwau.kortex.wayland

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.getOrElse
import com.fromwau.kortex.compose.LocalKortexSurface
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Tears one surface down while a second stays live and keeps taking pointer input, on one connection.
 *
 * The seat devices the closed surface bound are the hazard: each surface binds its own `wl_seat`, so
 * leaving its `wl_pointer` and `wl_keyboard` alive lets libwayland keep dispatching through listeners
 * that forward into an already-closed `KortexScene`. That throws inside an FFM upcall, which takes the
 * JVM down rather than failing an assertion, so a regression here shows up as a dead test worker.
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
            val shell = KortexShell.create(wayland, panelSpec(clicks), menuSpec(closeRequested, menuHovers))
                .getOrElse { error -> fail("shell creation failed: $error") }

            shell.use {
                val panelNamespace = awaitPanel(shell)
                val menu = awaitMenu(shell)
                val before = shell.activeSurfaces.size

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
                        val entered = shell.pump(PUMP_TIMEOUT_MILLIS) { menuHovers.get() > 0 }
                        assertTrue(entered, "the menu never received the pointer enter this test's premise needs")

                        // Nothing on the test thread calls close(): only this flag can drop the menu, and it
                        // does so from the composition's own thread.
                        closeRequested.value = true
                        val dropped = shell.pump(PUMP_TIMEOUT_MILLIS) { shell.activeSurfaces.size == before - 1 }
                        assertTrue(dropped, "the shell never dropped the menu after its content called close()")

                        val panel =
                            assertNotNull(Screen.geometry(panelNamespace), "the panel went away with the menu")
                        moveTo(shell, pointer, monitor, panel.x + TARGET_DP / 2, panel.y + TARGET_DP / 2)

                        pointer.button(BTN_LEFT, pressed = true)
                        pointer.frame()
                        shell.pump(SETTLE_MILLIS)
                        pointer.button(BTN_LEFT, pressed = false)
                        pointer.frame()

                        val delivered = shell.pump(PUMP_TIMEOUT_MILLIS) { clicks.get() == 1 }
                        val protocolError = wayland.protocolError()
                        if (protocolError != null) {
                            fail("wayland protocol error after the menu was torn down: $protocolError")
                        }
                        assertTrue(delivered, "a click never reached the panel that outlived the menu")
                        assertEquals(1, clicks.get(), "one press and release must be one click")
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
    private fun moveTo(shell: KortexShell, pointer: VirtualPointer, monitor: Monitor, x: Int, y: Int) {
        // Screen.geometry and the virtual pointer's absolute space agree only because this suite runs
        // against a single output pinned at the compositor's origin.
        pointer.motionAbsolute(x, y, monitor.logicalWidth, monitor.logicalHeight)
        pointer.frame()
        shell.pump(SETTLE_MILLIS)
    }

    private fun panelSpec(clicks: AtomicInteger): SurfaceSpec =
        SurfaceSpec(PANEL_CONFIG) { Box(Modifier.size(TARGET_DP.dp).clickable { clicks.incrementAndGet() }) }

    /**
     * Its content closes the surface itself once [closeRequested] flips, on the composition's own thread.
     *
     * An [SurfaceConfig.appMenu], not an OSD, so the surface that goes owns a `wl_keyboard` as well as a
     * `wl_pointer` and both teardown paths are exercised.
     */
    private fun menuSpec(closeRequested: MutableState<Boolean>, hovers: AtomicInteger): SurfaceSpec =
        SurfaceSpec(MENU_CONFIG, OutputTarget.CompositorChoice) {
            val surface = LocalKortexSurface.current
            val requested = closeRequested.value
            LaunchedEffect(requested) { if (requested) surface.close() }
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

    /** Pumps [shell] until the panel reaches `hyprctl layers`, and returns the namespace it was filed under. */
    private fun awaitPanel(shell: KortexShell): String {
        val appeared = shell.pump(PUMP_TIMEOUT_MILLIS) { panelNamespaces().isNotEmpty() }
        assertTrue(appeared, "hyprctl layers never reported a $PANEL_NAMESPACE- namespace")
        return panelNamespaces().first()
    }

    /** Pumps [shell] until the menu reaches `hyprctl layers`, and returns where it landed. */
    private fun awaitMenu(shell: KortexShell): LayerGeometry {
        val appeared = shell.pump(PUMP_TIMEOUT_MILLIS) { Screen.geometry(MENU_NAMESPACE) != null }
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

        // linux/input-event-codes.h
        const val BTN_LEFT = 0x110

        val PANEL_CONFIG = SurfaceConfig.panel(Edge.Top, PANEL_HEIGHT.dp).copy(namespace = PANEL_NAMESPACE)
        val MENU_CONFIG = SurfaceConfig.appMenu(MENU_SIZE.dp, MENU_SIZE.dp).copy(namespace = MENU_NAMESPACE)
    }
}
