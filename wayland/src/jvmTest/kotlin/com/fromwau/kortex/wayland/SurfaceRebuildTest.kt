package com.fromwau.kortex.wayland

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.getOrElse
import com.fromwau.kortex.compose.ContentFailure
import com.fromwau.kortex.compose.KortexSurfaceHandle
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
 * zero size in between.
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
            val watcher = thread(name = "size-watcher", isDaemon = true) {
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

    @Test
    fun `a crash after a rebuild names the namespace its content was on`() {
        val namespace = mutableStateOf(FIRST_NAMESPACE)
        val throwing = mutableStateOf(false)
        val reports = CopyOnWriteArrayList<Result<SurfaceEnd, SurfaceError<Nothing>>>()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            TestSurface<Nothing>(namespace.value, onClose = { reports += it }) {
                Canvas(Modifier.fillMaxSize()) { if (throwing.value) error(DRAW_FAILURE) }
            }
        }

        onApplication(content) { shell ->
            awaitPlaced(shell)

            namespace.value = SECOND_NAMESPACE

            awaitNamespace(shell, SECOND_NAMESPACE)

            // Only once the rebuild is over, so the surface the content is on is beyond doubt.
            throwing.value = true

            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { reports.isNotEmpty() },
                "content that threw after the rebuild reported nothing",
            )
            val crash = crashIn(reports.single(), "content that threw after the rebuild did not report a crash")
            assertEquals(
                SECOND_NAMESPACE, crash.namespace,
                "the crash named a surface the content was no longer on",
            )
            assertEquals(DRAW_FAILURE, crash.failure.cause.message, "the crash did not carry what the draw threw")
        }
    }

    /**
     * A rebuild that cannot be placed makes no surface under the name it asked for, so content that throws as the
     * scene goes is still named after the surface it was last on.
     */
    @Test
    fun `a rebuild that cannot be placed names a crash after the surface its content was last on`() {
        val asked = Asked()
        val reports = CopyOnWriteArrayList<Result<SurfaceEnd, SurfaceError<Nothing>>>()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            TestSurface<Nothing>(
                namespace = asked.namespace.value,
                anchor = asked.anchor.value,
                width = asked.width.value.dp,
                height = asked.height.value.dp,
                onClose = { reports += it },
            ) {
                DisposableEffect(Unit) { onDispose { error(CLEANUP_FAILURE) } }
            }
        }

        onApplication(content) { shell ->
            awaitPlaced(shell)

            // Both in one recomposition: the namespace is what makes this a rebuild, the anchor what fails it.
            asked.width.value = SPAN_ANCHORED_AXIS
            asked.anchor.value = UNSPANNABLE
            asked.namespace.value = SECOND_NAMESPACE

            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { reports.isNotEmpty() },
                "a rebuild that could not be placed reported nothing",
            )
            val crash = crashIn(reports.single(), "the cleanup that threw as the scene went did not report a crash")
            assertEquals(
                FIRST_NAMESPACE, crash.namespace,
                "the crash named the surface the rebuild asked for and never made",
            )
            assertEquals(CLEANUP_FAILURE, crash.failure.cause.message, "the crash did not carry what cleanup threw")
            assertNull(
                Screen.geometry(SECOND_NAMESPACE),
                "the surface the rebuild could not place is on screen under the name it asked for",
            )
        }
    }

    /**
     * Giving the pointer back runs content, which can throw there. That happens on the surface being given up, so
     * that is the surface the crash names, and no surface takes its place.
     *
     * The content here takes the pointer through a node of its own, so its throw comes straight back out of the
     * cancel. A `pointerInput` handler is resumed on the loop instead, and throws a pass later, on the surface the
     * rebuild has by then put under it.
     */
    @Test
    fun `content that throws as the rebuild gives its pointer back ends under the namespace it was on`() {
        val namespace = mutableStateOf(FIRST_NAMESPACE)
        val slotSeen = AtomicReference<SurfaceSlot?>(null)
        val reports = CopyOnWriteArrayList<Result<SurfaceEnd, SurfaceError<Nothing>>>()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            TestSurface<Nothing>(namespace.value, onClose = { reports += it }) {
                val slot = LocalSurfaceSlot.current
                SideEffect { slotSeen.set(slot) }
                Box(Modifier.fillMaxSize().then(ThrowsOnPointerCancel))
            }
        }

        onApplication(content) { shell ->
            awaitPlaced(shell)
            shell.pumpOrFail(SETTLE_MILLIS)
            val scene = assertNotNull(slotSeen.get()?.scene, "content never saw the slot its own call runs in")
            scene.composition.sendPointerEvent(PointerEventType.Enter, PROBE_AT, ENTERED_AT_MILLIS)
            scene.composition.sendPointerEvent(PointerEventType.Press, PROBE_AT, PRESSED_AT_MILLIS)

            namespace.value = SECOND_NAMESPACE

            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { reports.isNotEmpty() },
                "content that threw as its pointer was given back reported nothing",
            )
            val crash = crashIn(reports.single(), "content that threw as its pointer was given back did not crash")
            assertEquals(
                FIRST_NAMESPACE, crash.namespace,
                "the crash named a surface its content was never on",
            )
            val failure = assertIs<ContentFailure.PointerInput>(
                crash.failure,
                "the crash did not name the pointer as what the scene was doing: ${crash.failure}",
            )
            assertEquals(POINTER_FAILURE, failure.cause.message, "the crash did not carry what the pointer threw")
            assertTrue(shell.shownSurfaces.isEmpty(), "a surface was built for content that had already crashed")
            assertNull(Screen.geometry(SECOND_NAMESPACE), "hyprctl layers reports a surface the rebuild never made")
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

    /** Content that takes the pointer and throws when the pointer is taken back off it. */
    private data object ThrowsOnPointerCancel : ModifierNodeElement<ThrowsOnPointerCancel.Node>() {
        override fun create(): Node = Node()

        override fun update(node: Node) = Unit

        override fun InspectorInfo.inspectableProperties() {
            name = "throwsOnPointerCancel"
        }

        class Node : Modifier.Node(), PointerInputModifierNode {
            // Hit testing only adds a node that takes events, and only a node it added is cancelled.
            override fun onPointerEvent(
                pointerEvent: PointerEvent,
                pass: PointerEventPass,
                bounds: IntSize,
            ) = Unit

            override fun onCancelPointerInput(): Unit = error(POINTER_FAILURE)
        }
    }

    /** The surface one call asks for, every setting a state so a test can change it while the call is placed. */
    private class Asked(
        val namespace: MutableState<String> = mutableStateOf(FIRST_NAMESPACE),
        val anchor: MutableState<Set<Edge>> = mutableStateOf(BOTTOM_RIGHT_SPECK),
        val width: MutableState<Int> = mutableStateOf(SPECK),
        val height: MutableState<Int> = mutableStateOf(SPECK),
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

    /** One surface driven by [asked], whose content reports itself to [watch] from an effect a rebuild outlives. */
    @Composable
    private fun WatchedSurface(asked: Asked, watch: Watch) {
        TestSurface<Nothing>(
            namespace = asked.namespace.value,
            anchor = asked.anchor.value,
            width = asked.width.value.dp,
            height = asked.height.value.dp,
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

    private companion object {
        const val FIRST_NAMESPACE = "kortex-rebuild-first"
        const val SECOND_NAMESPACE = "kortex-rebuild-second"
        const val DRAW_FAILURE = "content threw while drawing"
        const val POINTER_FAILURE = "content threw on a pointer event"
        const val CLEANUP_FAILURE = "cleanup threw as the scene went"

        // A speck in the corner the pointer is least likely to be in, as TestSurface's own defaults place one.
        const val SPECK = 8
        val BOTTOM_RIGHT_SPECK = setOf(Edge.Bottom, Edge.Right)

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
