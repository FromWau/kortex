package com.fromwau.kortex.wayland

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.getOrElse
import com.fromwau.kortex.compose.KortexScene
import kotlinx.coroutines.asCoroutineDispatcher
import java.lang.foreign.MemorySegment
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Pins the version every global is bound at, and that its listener survives that version.
 *
 * libwayland dispatches an event by indexing the listener struct with the event's opcode, so a bind
 * version raised past what its listener implements crashes the JVM inside native code, with no Kotlin
 * stack trace. Asserting the version cannot catch that; only running events through it can, which is
 * what the live legs below do.
 */
class ProtocolVersionTest {
    @Test
    fun `every global binds at the lower of what kortex asks for and the compositor offers`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use { wayland ->
            BINDINGS.forEach { (interfaceName, iface, asked) ->
                val advertised = assertNotNull(
                    wayland.global(interfaceName)?.version,
                    "the compositor did not advertise $interfaceName",
                )
                val proxy = wayland.require(interfaceName, iface, asked)
                    .getOrElse { error -> fail("$interfaceName bind failed: $error") }
                val bound = LibWayland.proxyGetVersion(proxy)
                println("BOUND $interfaceName v$bound (kortex asks v$asked, compositor offers v$advertised)")
                assertEquals(
                    minOf(advertised, asked), bound,
                    "$interfaceName must bind at the lower of v$asked and the advertised v$advertised",
                )
            }
        }
    }

    /**
     * The leg above compares against what the compositor advertises, which never exceeds the ceiling
     * libwayland's own tables declare — so it cannot see a constant raised past that ceiling, which is
     * the case `wl_proxy_marshal_flags` answers by aborting inside native code.
     */
    @Test
    fun `every version kortex asks for is one its own wl_interface declares`() {
        BINDINGS.forEach { (interfaceName, iface, asked) ->
            val declared = LibWayland.interfaceVersion(iface)
            assertTrue(
                asked <= declared,
                "$interfaceName asks for v$asked, past the v$declared its wl_interface declares",
            )
        }
    }

    @Test
    fun `the pointer and keyboard inherit the version their seat negotiated`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use { wayland ->
            val advertised = assertNotNull(
                wayland.global("wl_seat")?.version,
                "the compositor did not advertise wl_seat",
            )
            val negotiated = minOf(advertised, WlVersion.SEAT)
            val seat = Seat.bind(wayland).getOrElse { error -> fail("seat bind failed: $error") }

            withScene { scene ->
                val pointer = assertNotNull(seat.attachPointer(scene, scale = 1f), "the seat announced no pointer")
                val keyboard = assertNotNull(seat.attachKeyboard(scene), "the seat announced no keyboard")
                // The keymap and, from v4 on, repeat_info land here; a listener short a slot dies on this line.
                wayland.roundtrip()

                assertEquals(
                    negotiated, LibWayland.proxyGetVersion(pointer.pointerProxy),
                    "wl_pointer must be created at the seat's own version",
                )
                assertEquals(
                    negotiated, LibWayland.proxyGetVersion(keyboard.keyboardProxy),
                    "wl_keyboard must be created at the seat's own version",
                )
                assertNull(wayland.protocolError(), "the seat's devices did not survive a roundtrip")
            }
        }
    }

    @Test
    fun `a bar pumps clean with every listener slot filled`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use { wayland ->
            KortexSurface.create(wayland, CONFIG)
                .getOrElse { error -> fail("bar creation failed: $error") }
                .use { bar ->
                    bar.setContent { Box(Modifier.fillMaxSize().background(Color.DarkGray)) }
                    bar.pump(timeoutMillis = PUMP_TIMEOUT_MILLIS)

                    assertNull(wayland.protocolError(), "the connection reported a protocol error")
                    assertTrue(bar.renders > 0, "the bar never rendered a frame")
                }
        }
    }

    /**
     * From v5 a scroll is a source plus an axis inside a frame group, and from v8 an `axis_value120`
     * rides alongside the axis. kortex reads only `axis`, so this drives a real wheel through the
     * compositor and asserts the newer grouping did not swallow it on the way to the composition.
     */
    @Test
    fun `a wheel scroll reaches the composition at the negotiated pointer version`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use { wayland ->
            val manager = VirtualPointerManager.bind(wayland)
                .getOrElse { error -> fail("virtual pointer manager bind failed: $error") }
            val monitor = assertNotNull(Hyprctl.monitors().firstOrNull(), "hyprctl monitors reported no monitor")
            val offBarX = monitor.logicalWidth / 2
            val offBarY = monitor.logicalHeight - 1
            val scrolled = AtomicReference(Offset.Zero)

            KortexSurface.create(wayland, CONFIG)
                .getOrElse { error -> fail("bar creation failed: $error") }
                .use { bar ->
                    bar.setContent {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(Color.DarkGray)
                                .pointerInput(Unit) {
                                    awaitPointerEventScope {
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            if (event.type != PointerEventType.Scroll) continue
                                            scrolled.set(event.changes.first().scrollDelta)
                                        }
                                    }
                                },
                        )
                    }
                    wayland.roundtrip()

                    val geometry =
                        assertNotNull(Screen.geometry(NAMESPACE), "hyprctl layers did not report $NAMESPACE")

                    manager.createVirtualPointer().use { wheel ->
                        fun moveTo(x: Int, y: Int) {
                            wheel.moveTo(monitor, x, y)
                            wayland.roundtrip()
                            bar.pump(timeoutMillis = SETTLE_MILLIS)
                        }

                        // Off the bar first: the compositor re-evaluates pointer focus on motion, so a
                        // cursor already parked on these coordinates would never enter the new surface.
                        moveTo(offBarX, offBarY)
                        moveTo(geometry.x + geometry.logicalWidth / 2, geometry.y + geometry.logicalHeight / 2)

                        wheel.axisSource(AXIS_SOURCE_WHEEL)
                        wheel.axis(AXIS_VERTICAL, SCROLL_FIXED)
                        wheel.frame()
                        wayland.roundtrip()
                        // Off it again: a cursor left on a target would deny the next test's own move
                        // here an enter, the same hazard the first move above avoids.
                        moveTo(offBarX, offBarY)
                    }

                    val delivered = bar.pump(timeoutMillis = PUMP_TIMEOUT_MILLIS) { scrolled.get() != Offset.Zero }

                    assertNull(wayland.protocolError(), "the connection reported a protocol error")
                    assertTrue(delivered, "a wheel scroll over the bar never reached the composition")
                    assertTrue(scrolled.get().y != 0f, "the scroll arrived on the wrong axis: ${scrolled.get()}")
                }
        }
    }

    /** No surface behind it: the seat leg only needs somewhere for its devices to deliver into. */
    private fun withScene(block: (KortexScene) -> Unit) {
        val dispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "kortex-version-test").apply { isDaemon = true }
        }.asCoroutineDispatcher()
        dispatcher.use {
            KortexScene(
                size = IntSize(SIDE, SIDE),
                density = Density(1f),
                layoutDirection = LayoutDirection.Ltr,
                frameContext = dispatcher,
                onInvalidate = {},
            ).use(block)
        }
    }

    /** One global kortex binds, with the version it asks for. */
    private data class Binding(val interfaceName: String, val iface: MemorySegment, val asked: Int)

    private companion object {
        val BINDINGS = listOf(
            Binding("wl_compositor", LibWayland.compositorInterface, WlVersion.COMPOSITOR),
            Binding("wl_shm", LibWayland.shmInterface, WlVersion.SHM),
            Binding("wl_seat", LibWayland.seatInterface, WlVersion.SEAT),
            Binding("wl_output", LibWayland.outputInterface, WlVersion.OUTPUT),
            Binding("zwlr_layer_shell_v1", LayerShellProtocol.layerShellInterface, WlVersion.LAYER_SHELL),
            Binding(
                "zwlr_virtual_pointer_manager_v1",
                VirtualPointerProtocol.virtualPointerManagerInterface,
                WlVersion.VIRTUAL_POINTER,
            ),
        )

        const val NAMESPACE = "kortex"
        const val BAR_HEIGHT = 32
        const val SIDE = 64
        const val SETTLE_MILLIS = 500L
        const val PUMP_TIMEOUT_MILLIS = 3000L

        const val AXIS_VERTICAL = 0
        const val AXIS_SOURCE_WHEEL = 0

        /** How far to scroll, as wl_fixed_t; only that it is non-zero matters. */
        const val SCROLL_FIXED = 15 * 256

        val CONFIG = SurfaceConfig.panel(edge = Edge.Top, thickness = BAR_HEIGHT.dp).copy(namespace = NAMESPACE)
    }
}
