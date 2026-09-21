package com.fromwau.kortex.wayland

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.getOrElse
import com.fromwau.kortex.compose.KortexSurfaceHandle
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.delay

/**
 * `get_layer_surface` fixes a surface's monitor and namespace, so a call that changes either gets new Wayland
 * objects. The Compose scene is not one of them: content keeps its state, keeps its effects running, never reads a
 * zero size in between, and a surface off screen comes back off screen.
 *
 * Only the namespace is changed here. The desktop this runs on has one monitor, and a changed monitor takes the
 * identical path.
 */
class SurfaceRebuildTest {
    @Test
    fun `a changed namespace puts the call on a layer surface of its own`() {
        val asked = Asked()
        val watch = Watch()

        onWatchedSurface(asked, watch) { shell, placed ->
            val before = geometryOf(FIRST_NAMESPACE)

            asked.namespace.value = SECOND_NAMESPACE

            awaitNamespace(shell, SECOND_NAMESPACE)
            assertNull(
                Screen.geometry(FIRST_NAMESPACE),
                "hyprctl layers still reports $FIRST_NAMESPACE after the call asked for $SECOND_NAMESPACE",
            )
            assertNotEquals(
                before.address, geometryOf(SECOND_NAMESPACE).address,
                "the compositor kept the same layer surface, so the namespace never reached get_layer_surface",
            )
            assertNotSame(
                placed, shell.shownSurfaces.single(),
                "the call kept the surface it was placed on, which cannot carry another namespace",
            )
            assertEquals(emptyList(), watch.reports.toList(), "the rebuild ended the surface instead of remaking it")
        }
    }

    @Test
    fun `content keeps what it holds and keeps its effect running across a rebuild`() {
        val asked = Asked()
        val watch = Watch()

        onWatchedSurface(asked, watch) { shell, _ ->
            asked.namespace.value = SECOND_NAMESPACE

            awaitNamespace(shell, SECOND_NAMESPACE)
            val ticks = watch.ticks.get()

            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { watch.ticks.get() >= ticks + TICKS },
                "the effect running in the content stopped when its surface was rebuilt",
            )
            assertEquals(1, watch.compositions.get(), "the content was composed again from scratch")
            assertEquals(1, watch.effects.get(), "the content's effect was started a second time")
            assertEquals(1, watch.held.get(), "the content lost what it held behind remember")
        }
    }

    @Test
    fun `content never reads a zero size while its surface is rebuilt`() {
        val asked = Asked()
        val watch = Watch()

        onWatchedSurface(asked, watch) { shell, _ ->
            val handle = assertNotNull(watch.handle.get(), "content never saw its own surface")
            val placedAt = handle.size
            assertNotEquals(IntSize.Zero, placedAt, "content read no size at all before the rebuild")

            // The rebuild runs to its end inside one pass of the loop, so only another thread can watch it happen.
            val sizes = CopyOnWriteArrayList<IntSize>()
            val watching = AtomicBoolean(true)
            val watcher = thread(name = "size-watcher") {
                while (watching.get()) sizes.addIfAbsent(handle.size)
            }
            try {
                asked.namespace.value = SECOND_NAMESPACE
                awaitNamespace(shell, SECOND_NAMESPACE)
            } finally {
                watching.set(false)
                watcher.join(WATCHER_JOIN_MILLIS)
            }

            assertEquals(
                listOf(placedAt), sizes.toList(),
                "content read a size other than the one it was placed at while its surface was rebuilt",
            )
        }
    }

    @Test
    fun `a surface rebuilt while it is off screen stays off screen, and draws once it is shown`() {
        val asked = Asked(anchor = mutableStateOf(BOTTOM_PANEL), width = mutableStateOf(SPAN_ANCHORED_AXIS))
        val watch = Watch()
        val before = Hyprctl.monitors().associateBy(HyprMonitor::name)

        onWatchedSurface(asked, watch) { shell, _ ->
            val monitor = assertNotNull(
                before[geometryOf(FIRST_NAMESPACE).monitor],
                "hyprctl did not report the monitor the panel landed on before the run",
            )
            assertReservesMore(shell, monitor, Edge.Bottom, THICKNESS)

            asked.visible.value = false

            awaitOffScreen(shell)

            asked.namespace.value = SECOND_NAMESPACE

            awaitNamespace(shell, SECOND_NAMESPACE)
            val rebuilt = shell.shownSurfaces.single()
            assertTrue(rebuilt.hidden, "the panel came back on screen although its call still asks for it off")
            assertEquals(0, rebuilt.renders, "a panel rebuilt off screen drew a frame")
            assertReservesMore(shell, monitor, Edge.Bottom, RESERVES_NOTHING)

            asked.visible.value = true

            awaitOnScreen(shell)
            assertReservesMore(shell, monitor, Edge.Bottom, THICKNESS)
            assertTrue(rebuilt.renders > 0, "the panel drew nothing once it was shown again")
        }
    }

    @Test
    fun `a rebuild the new settings cannot be placed with ends the surface, and the run goes on`() {
        val asked = Asked()
        val watch = Watch()

        onWatchedSurface(asked, watch) { shell, _ ->
            // Both in one recomposition: the namespace is what makes this a rebuild, the anchor what fails it.
            asked.width.value = SPAN_ANCHORED_AXIS
            asked.anchor.value = UNSPANNABLE
            asked.namespace.value = SECOND_NAMESPACE

            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { watch.reports.isNotEmpty() },
                "a rebuild onto settings that cannot be placed reported nothing",
            )
            assertEquals(
                listOf(Err(SurfaceError.Failed(KortexError.UnspannableAxis(Axis.Horizontal, UNSPANNABLE)))),
                watch.reports.toList(),
                "the rebuild did not end the surface with the reason its settings were rejected for",
            )
            assertTrue(shell.shownSurfaces.isEmpty(), "the ended surface is still held by the shell")
            assertNull(Screen.geometry(FIRST_NAMESPACE), "hyprctl layers still reports the surface that ended")

            // The scene a failed rebuild leaves behind is one nothing would ever close again.
            val ticks = watch.ticks.get()
            shell.pumpOrFail(IDLE_WINDOW_MILLIS)
            assertEquals(ticks, watch.ticks.get(), "the content of the ended surface kept running after it reported")
        }
    }

    /**
     * A rebuild hands the scene from one surface's input devices to another's, and the new surface's first enter
     * would never clear an interaction the old one left open.
     */
    @Test
    fun `detaching a surface ends the pointer interaction its content was in`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }
        val seen = CopyOnWriteArrayList<PointerEventType>()

        display.use {
            onBareSurface(display, SPECK_CONFIG) { surface, scene ->
                scene.setContent {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Red)
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) seen += awaitPointerEvent().type
                                }
                            },
                    )
                }
                surface.pumpOrFail(SETTLE_MILLIS)

                scene.composition.sendPointerEvent(PointerEventType.Enter, PROBE_AT, ENTERED_AT_MILLIS)
                scene.composition.sendPointerEvent(PointerEventType.Press, PROBE_AT, PRESSED_AT_MILLIS)
                surface.pumpOrFail(SETTLE_MILLIS)
                assertEquals(
                    listOf(PointerEventType.Enter, PointerEventType.Press), seen.toList(),
                    "content never took the pointer interaction this ends",
                )

                surface.detach()

                assertEquals(
                    listOf(PointerEventType.Enter, PointerEventType.Press, PointerEventType.Release), seen.toList(),
                    "the interaction outlived the surface it was made on",
                )
            }
        }
    }

    /** The surface one call asks for, every setting a state so a test can change it while the call is placed. */
    private class Asked(
        val namespace: MutableState<String> = mutableStateOf(FIRST_NAMESPACE),
        val anchor: MutableState<Set<Edge>> = mutableStateOf(BOTTOM_RIGHT_SPECK),
        val width: MutableState<Int> = mutableStateOf(SPECK),
        val height: MutableState<Int> = mutableStateOf(SPECK),
        val visible: MutableState<Boolean> = mutableStateOf(true),
    )

    /** What the surface's content publishes: how often it was composed, what it holds, and that its effect runs. */
    private class Watch {
        val compositions = AtomicInteger()
        val effects = AtomicInteger()
        val held = AtomicInteger()
        val ticks = AtomicInteger()
        val handle = AtomicReference<KortexSurfaceHandle?>(null)
        val reports = CopyOnWriteArrayList<Result<SurfaceEnd, SurfaceError<Nothing>>>()
    }

    /** One surface driven by [asked], whose content reports itself to [watch] from an effect that outlives a rebuild. */
    @Composable
    private fun WatchedSurface(asked: Asked, watch: Watch) {
        TestSurface<Nothing>(
            namespace = asked.namespace.value,
            anchor = asked.anchor.value,
            width = asked.width.value.dp,
            height = asked.height.value.dp,
            exclusiveZone = ExclusiveZone.Reserve(THICKNESS.dp).takeIf { asked.anchor.value == BOTTOM_PANEL }
                ?: ExclusiveZone.Yield,
            visible = asked.visible.value,
            onClose = { watch.reports += it },
        ) {
            val surface = this
            val held = remember { watch.compositions.incrementAndGet() }
            SideEffect { watch.handle.set(surface) }
            LaunchedEffect(Unit) {
                watch.effects.incrementAndGet()
                while (true) {
                    watch.held.set(held)
                    watch.ticks.incrementAndGet()
                    delay(TICK_MILLIS)
                }
            }
            Box(Modifier.fillMaxSize().background(Color.Red))
        }
    }

    /** Runs one surface of [asked] and hands [body] the shell and the surface the call was first placed on. */
    private fun onWatchedSurface(
        asked: Asked,
        watch: Watch,
        body: (KortexShell, KortexSurface) -> Unit,
    ) {
        onApplication({ WatchedSurface(asked, watch) }) { shell ->
            awaitPlaced(shell)
            body(shell, shell.shownSurfaces.single())
        }
    }

    /** What hyprctl reports of [namespace], which it lists whether or not that surface is on screen. */
    private fun geometryOf(namespace: String): LayerGeometry =
        assertNotNull(Screen.awaitGeometry(namespace), "hyprctl never reported $namespace")

    private fun awaitNamespace(shell: KortexShell, namespace: String) {
        assertTrue(
            shell.pumpOrFail(PUMP_MILLIS) { Screen.geometry(namespace) != null },
            "hyprctl layers never reported $namespace",
        )
    }

    private fun awaitOffScreen(shell: KortexShell) {
        assertTrue(
            shell.pumpOrFail(PUMP_MILLIS) { shell.shownSurfaces.singleOrNull()?.hidden == true },
            "the surface was never taken off screen",
        )
    }

    private fun awaitOnScreen(shell: KortexShell) {
        assertTrue(
            shell.pumpOrFail(PUMP_MILLIS) { shell.shownSurfaces.singleOrNull()?.hidden == false },
            "the surface was never put back on screen",
        )
    }

    private companion object {
        const val FIRST_NAMESPACE = "kortex-rebuild-first"
        const val SECOND_NAMESPACE = "kortex-rebuild-second"

        // A speck in the corner the pointer is least likely to be in, as TestSurface's own defaults place one.
        const val SPECK = 8
        val BOTTOM_RIGHT_SPECK = setOf(Edge.Bottom, Edge.Right)

        // A panel along the bottom edge, clear of the desktop's own bar at the top.
        const val THICKNESS = 18
        const val SPAN_ANCHORED_AXIS = 0
        const val RESERVES_NOTHING = 0
        val BOTTOM_PANEL = setOf(Edge.Bottom, Edge.Left, Edge.Right)

        // Anchored to one edge of the horizontal axis only, so a width of 0 leaves that axis unspannable.
        val UNSPANNABLE = setOf(Edge.Bottom, Edge.Left)

        val SPECK_CONFIG = SurfaceConfig(
            namespace = "kortex-rebuild-detach",
            layer = Layer.Overlay,
            anchor = BOTTOM_RIGHT_SPECK,
            width = SPECK.dp,
            height = SPECK.dp,
            exclusiveZone = ExclusiveZone.Yield,
        )

        val PROBE_AT = Offset(1f, 1f)
        const val ENTERED_AT_MILLIS = 1L
        const val PRESSED_AT_MILLIS = 2L

        const val PUMP_MILLIS = 4_000L
        const val SETTLE_MILLIS = 500L
        const val TICK_MILLIS = 20L

        // Long enough that content still running would tick many times in it.
        const val IDLE_WINDOW_MILLIS = 500L
        const val TICKS = 3
        const val WATCHER_JOIN_MILLIS = 2_000L
    }
}
