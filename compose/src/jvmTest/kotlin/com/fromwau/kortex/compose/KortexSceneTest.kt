package com.fromwau.kortex.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropTransferAction
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.errorOrNull
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Surface
import java.awt.Cursor
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class KortexSceneTest {
    @Test
    fun `composes into a canvas the host owns`() {
        withScene {
            scene.setContent { Box(Modifier.fillMaxSize().background(Color.Red)) }
            tick(0L)

            assertEquals(Color.Red.toArgb(), surface.pixelAt(SIDE / 2, SIDE / 2))
        }
    }

    @Test
    fun `signals once when state changes`() {
        val signalled = CountDownLatch(1)
        val signals = AtomicInteger()
        val color = mutableStateOf(Color.Red)

        withScene(onInvalidate = { signals.incrementAndGet(); signalled.countDown() }) {
            scene.setContent { Box(Modifier.fillMaxSize().background(color.value)) }
            tick(0L)
            assertEquals(0, signals.get(), "composing and rendering must not ask for a frame on its own")

            color.value = Color.Blue

            assertTrue(passUntil(signalled), "state change never asked for a frame")
            assertEquals(1, signals.get(), "one state change must ask for exactly one frame")
        }
    }

    @Test
    fun `stays silent while idle`() {
        val signals = AtomicInteger()

        withScene(onInvalidate = { signals.incrementAndGet() }) {
            scene.setContent { Box(Modifier.fillMaxSize().background(Color.Red)) }
            tick(0L)

            // Asserting an absence needs a window to be absent in, and the window has to be one the loop
            // is running in: a busy loop shows up here as a non-zero count rather than as anything visibly
            // wrong on screen, and a window that ran nothing would report every composition idle.
            idleFor(IDLE_WINDOW_MILLIS)
            tick(1L)
            idleFor(IDLE_WINDOW_MILLIS)

            assertEquals(0, signals.get(), "an idle composition must never ask for a frame")
        }
    }

    @Test
    fun `a change read only while drawing asks for a frame`() {
        val radius = mutableIntStateOf(1)

        assertChangeAsksForFrame(radius) {
            Canvas(Modifier.fillMaxSize()) { drawCircle(Color.Red, radius = radius.intValue.toFloat()) }
        }
    }

    @Test
    fun `a change read only in a graphicsLayer block asks for a frame`() {
        val shift = mutableIntStateOf(0)

        assertChangeAsksForFrame(shift) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationX = shift.intValue.toFloat() }
                    .background(Color.Red),
            )
        }
    }

    @Test
    fun `a change read only while placing asks for a frame`() {
        val shift = mutableIntStateOf(0)

        assertChangeAsksForFrame(shift) {
            Box(
                Modifier
                    .offset { IntOffset(shift.intValue, 0) }
                    .size(BOX_DP.dp)
                    .background(Color.Red),
            )
        }
    }

    @Test
    fun `a clickable's default press indication asks for a frame`() {
        val signalled = CountDownLatch(1)
        val signals = AtomicInteger()
        val onInvalidate = {
            signals.incrementAndGet()
            signalled.countDown()
        }

        withScene(onInvalidate = onInvalidate) {
            scene.setContent { Box(Modifier.size(BOX_DP.dp).clickable {}) }
            tick(0L)
            assertEquals(0, signals.get(), "composing and rendering must not ask for a frame on its own")

            scene.sendPointerEvent(
                PointerEventType.Press,
                Offset(BOX_DP / 2f, BOX_DP / 2f),
                timeMillis = 0L,
                buttons = PointerButtons(isPrimaryPressed = true),
                button = PointerButton.Primary,
            )

            assertTrue(passUntil(signalled), "the press never asked for a frame")
        }
    }

    @Test
    fun `a draw that invalidates itself asks for the next frame`() {
        val signals = AtomicInteger()

        withScene(onInvalidate = { signals.incrementAndGet() }) {
            scene.setContent { Box(Modifier.fillMaxSize().then(InvalidateOnFirstDraw)) }
            tick(0L)

            assertEquals(1, signals.get(), "a draw that invalidated itself must ask for the frame that redraws it")
        }
    }

    @Test
    fun `resizes in place, without a new scene`() {
        withScene(size = IntSize(SIDE, SIDE), surfaceSize = IntSize(WIDE, SIDE)) {
            scene.setContent { Box(Modifier.fillMaxSize().background(Color.Red)) }
            tick(0L)

            assertEquals(Color.Red.toArgb(), surface.pixelAt(SIDE / 2, SIDE / 2), "content missing before resize")
            assertNotEquals(Color.Red.toArgb(), surface.pixelAt(WIDE - 8, SIDE / 2), "content wider than the scene")

            scene.size = IntSize(WIDE, SHORT)
            surface.canvas.clear(TRANSPARENT)
            tick(1L)

            assertEquals(Color.Red.toArgb(), surface.pixelAt(WIDE - 8, SHORT / 2), "did not widen to the new size")
            assertNotEquals(Color.Red.toArgb(), surface.pixelAt(SIDE / 2, SIDE - 8), "did not shorten to the new size")
        }
    }

    @Test
    fun `density change rescales content`() {
        withScene {
            scene.setContent { Box(Modifier.size(BOX_DP.dp).background(Color.Red)) }
            tick(0L)

            val probe = BOX_DP + BOX_DP / 4
            assertNotEquals(Color.Red.toArgb(), surface.pixelAt(probe, probe), "box already covers the probe at 1x")

            scene.density = Density(2f)
            surface.canvas.clear(TRANSPARENT)
            tick(1L)

            assertEquals(Color.Red.toArgb(), surface.pixelAt(probe, probe), "box did not grow with density")
        }
    }

    @Test
    fun `a pointer press and release reaches the composition`() {
        val clicked = CountDownLatch(1)
        val clicks = AtomicInteger()

        withScene {
            // The target covers only the top-left BOX_DP square of a larger scene, so a press outside it
            // proves the event is hit-tested rather than merely delivered.
            scene.setContent {
                Box(Modifier.size(BOX_DP.dp).clickable { clicks.incrementAndGet(); clicked.countDown() })
            }
            tick(0L)

            scene.click(Offset(SIDE - 8f, SIDE - 8f), from = 0L)
            tick(1L)
            assertEquals(0, clicks.get(), "a press outside the target must not click it")

            scene.click(Offset(BOX_DP / 2f, BOX_DP / 2f), from = 2L)
            tick(2L)

            assertTrue(passUntil(clicked), "onClick never fired")
            assertEquals(1, clicks.get(), "one press and release must be one click")
        }
    }

    @Test
    fun `hovering a text region asks the host for a text cursor`() {
        val cursors = mutableListOf<KortexCursor>()
        val host = object : KortexPlatform {
            override fun setCursor(cursor: KortexCursor) {
                synchronized(cursors) { cursors += cursor }
            }
        }

        withScene(platform = host) {
            scene.setContent {
                Box(Modifier.size(BOX_DP.dp).pointerHoverIcon(PointerIcon.Text))
            }
            tick(0L)

            scene.sendPointerEvent(PointerEventType.Move, Offset(SIDE - 8f, SIDE - 8f), timeMillis = 0L)
            tick(1L)
            assertTrue(
                synchronized(cursors) { cursors.none { it == KortexCursor.Text } },
                "a text cursor outside the text region",
            )

            scene.sendPointerEvent(PointerEventType.Move, Offset(BOX_DP / 2f, BOX_DP / 2f), timeMillis = 1L)
            tick(2L)

            assertEquals(
                KortexCursor.Text,
                synchronized(cursors) { cursors.lastOrNull() },
                "hovering the text region must ask the host for a text cursor",
            )
        }
    }

    @Test
    fun `hovering a region asks the host for move, wait or the matching resize cursor`() {
        val shapes = listOf(
            Cursor.MOVE_CURSOR to KortexCursor.Move,
            Cursor.WAIT_CURSOR to KortexCursor.Wait,
            Cursor.N_RESIZE_CURSOR to KortexCursor.ResizeNorth,
            Cursor.NE_RESIZE_CURSOR to KortexCursor.ResizeNorthEast,
            Cursor.E_RESIZE_CURSOR to KortexCursor.ResizeEast,
            Cursor.SE_RESIZE_CURSOR to KortexCursor.ResizeSouthEast,
            Cursor.S_RESIZE_CURSOR to KortexCursor.ResizeSouth,
            Cursor.SW_RESIZE_CURSOR to KortexCursor.ResizeSouthWest,
            Cursor.W_RESIZE_CURSOR to KortexCursor.ResizeWest,
            Cursor.NW_RESIZE_CURSOR to KortexCursor.ResizeNorthWest,
        )

        for ((awtCursorType, expected) in shapes) {
            val cursors = mutableListOf<KortexCursor>()
            val host = object : KortexPlatform {
                override fun setCursor(cursor: KortexCursor) {
                    synchronized(cursors) { cursors += cursor }
                }
            }

            withScene(platform = host) {
                scene.setContent {
                    Box(Modifier.size(BOX_DP.dp).pointerHoverIcon(PointerIcon(Cursor(awtCursorType))))
                }
                tick(0L)

                scene.sendPointerEvent(PointerEventType.Move, Offset(BOX_DP / 2f, BOX_DP / 2f), timeMillis = 0L)
                tick(1L)

                assertEquals(
                    expected,
                    synchronized(cursors) { cursors.lastOrNull() },
                    "hovering the region must ask the host for $expected",
                )
            }
        }
    }

    @Test
    fun `content that throws while drawing is a composition failure`() {
        withScene {
            scene.setContent { Canvas(Modifier.fillMaxSize()) { error(DRAW_FAILURE) } }

            val failure = tick(0L).errorOrNull()

            assertIs<ContentFailure.Composition>(failure, "a throwing draw must fail the composition")
            assertEquals(DRAW_FAILURE, failure.cause.message)
        }
    }

    @Test
    fun `content that throws while recomposing is a composition failure`() {
        val broken = mutableStateOf(false)

        withScene {
            scene.setContent { if (broken.value) error(RECOMPOSE_FAILURE) }
            tick(0L)

            var failure: ContentFailure? = null
            // Compose prints its own report of the recomposition this test throws in, kept off the results.
            val reported = capturingStderr {
                broken.value = true
                failure = awaitFailure { tick(System.nanoTime()) }
            }

            // Asserted outside the capture, and against it: what Compose printed is why an assertion here reds.
            val composition = assertIs<ContentFailure.Composition>(
                failure,
                "a throwing recomposition must fail the composition; Compose reported: $reported",
            )
            assertEquals(RECOMPOSE_FAILURE, composition.cause.message, "Compose reported: $reported")
        }
    }

    @Test
    fun `a key handler that throws is a key input failure`() {
        withScene {
            scene.setContent {
                val requester = remember { FocusRequester() }
                Box(
                    Modifier
                        .fillMaxSize()
                        .focusRequester(requester)
                        .onKeyEvent { error(KEY_FAILURE) }
                        .focusable(),
                )
                LaunchedEffect(Unit) { requester.requestFocus() }
            }
            // A few frames so the LaunchedEffect runs and focus settles.
            repeat(FOCUS_FRAMES) { frame ->
                tick(frame.toLong())
                Thread.sleep(FRAME_MILLIS)
            }

            val failure = scene.sendKey(Key.A, KeyEventType.KeyDown).errorOrNull()

            assertIs<ContentFailure.KeyInput>(failure, "a throwing key handler must fail the key")
            assertEquals(KEY_FAILURE, failure.cause.message)
        }
    }

    @Test
    fun `a pointer handler that throws is a pointer input failure`() {
        withScene {
            scene.setContent { Box(Modifier.size(BOX_DP.dp).clickable { error(POINTER_FAILURE) }) }
            tick(0L)

            scene.click(Offset(BOX_DP / 2f, BOX_DP / 2f), from = 0L)
            val failure = awaitFailure { tick(System.nanoTime()) }

            assertIs<ContentFailure.PointerInput>(failure, "a throwing click handler must fail the pointer event")
            assertEquals(POINTER_FAILURE, failure.cause.message)
        }
    }

    @Test
    fun `an effect that throws is a composition failure`() {
        withScene {
            scene.setContent {
                LaunchedEffect(Unit) {
                    delay(EFFECT_DELAY_MILLIS)
                    error(EFFECT_FAILURE)
                }
            }
            tick(0L)

            val failure = awaitFailure()

            assertIs<ContentFailure.Composition>(failure, "an effect that throws must fail the composition")
            assertEquals(EFFECT_FAILURE, failure.cause.message)
        }
    }

    @Test
    fun `a failed scene keeps its first failure and runs no more content`() {
        val draws = AtomicInteger()

        withScene {
            scene.setContent {
                Canvas(Modifier.fillMaxSize()) {
                    draws.incrementAndGet()
                    error(DRAW_FAILURE)
                }
            }

            val first = tick(0L).errorOrNull()
            val second = tick(1L).errorOrNull()

            assertSame(first, second, "a failed scene must report its first failure again")
            assertEquals(1, draws.get(), "a failed scene must not draw again")
        }
    }

    @Test
    fun `content that throws an Error is a composition failure too`() {
        withScene {
            scene.setContent { Canvas(Modifier.fillMaxSize()) { throw StackOverflowError(ERROR_FAILURE) } }

            val failure = tick(0L).errorOrNull()

            assertIs<ContentFailure.Composition>(failure, "an Error thrown by content must fail the composition")
            assertIs<StackOverflowError>(failure.cause)
        }
    }

    @Test
    fun `every failure reaches onFailure, cleanup on close included`() {
        val reported = CopyOnWriteArrayList<ContentFailure>()

        withScene(onFailure = { reported += it }) {
            scene.setContent {
                DisposableEffect(Unit) { onDispose { error(CLEANUP_FAILURE) } }
                Canvas(Modifier.fillMaxSize()) { error(DRAW_FAILURE) }
            }
            tick(0L)
        }

        // withScene has closed the scene by now, running content's cleanup.
        assertEquals(listOf(DRAW_FAILURE, CLEANUP_FAILURE), reported.map { it.cause.message })
    }

    @Test
    @OptIn(ExperimentalComposeUiApi::class)
    fun `dragging content out hands the host what content offered`() {
        val carried = AtomicReference<KortexDragSource?>(null)
        val asked = CountDownLatch(1)
        val host = object : KortexPlatform {
            override fun startDrag(dragged: KortexDragSource, onNotStarted: () -> Unit): Boolean {
                carried.set(dragged)
                asked.countDown()
                return true
            }
        }

        withScene(platform = host) {
            scene.setContent {
                Box(
                    Modifier
                        .fillMaxSize()
                        .dragAndDropSource(drawDragDecoration = {}) {
                            DragAndDropTransferData(
                                KortexDragSource.Text(DRAGGED_TEXT),
                                listOf(DragAndDropTransferAction.Copy),
                            )
                        },
                )
            }
            tick(0L)

            assertTrue(dragUntilAsked(asked), "dragging content never asked the host to carry it")
        }

        val dragged = assertIs<KortexDragSource.Text>(carried.get(), "the host was handed no text to drag")
        assertEquals(DRAGGED_TEXT, dragged.text, "the host was handed other text than content offered")
    }

    /**
     * The other end of the same call. A host takes a drag on before it knows whether it can carry it: the
     * payload is encoded off the loop thread, so a size it cannot carry is only found once the gesture has
     * already returned true. Compose keeps a channel for exactly that, and content heard nothing through it
     * until the host was given one.
     */
    @Test
    @OptIn(ExperimentalComposeUiApi::class)
    fun `a drag the host takes on and then cannot start tells content it did not complete`() {
        val completedWith = AtomicReference<DragAndDropTransferAction?>(null)
        val told = CountDownLatch(1)
        val asked = CountDownLatch(1)
        val host = object : KortexPlatform {
            override fun startDrag(dragged: KortexDragSource, onNotStarted: () -> Unit): Boolean {
                asked.countDown()
                // As the real host does: taken on here, found impossible a few loop passes later.
                onNotStarted()
                return true
            }
        }

        withScene(platform = host) {
            scene.setContent {
                Box(
                    Modifier
                        .fillMaxSize()
                        .dragAndDropSource(drawDragDecoration = {}) {
                            DragAndDropTransferData(
                                KortexDragSource.Text(DRAGGED_TEXT),
                                listOf(DragAndDropTransferAction.Copy),
                                onTransferCompleted = { action ->
                                    completedWith.set(action)
                                    told.countDown()
                                },
                            )
                        },
                )
            }
            tick(0L)

            assertTrue(dragUntilAsked(asked), "dragging content never asked the host to carry it")
            assertTrue(passUntil(told, TOLD_MILLIS), "content was never told the drag did not start")
        }

        // Null is the whole point: Compose reads it as "the gesture did not complete successfully", which is
        // what a drag the host could not start is, and an action would say the opposite.
        assertNull(completedWith.get(), "content was told the drag completed, with ${completedWith.get()}")
    }

    /**
     * Presses and drags across [scene] until [asked] counts down, giving up after [DRAG_WAIT_MILLIS].
     *
     * Repeated because the gesture that starts a drag runs on the scene's own dispatcher: a press sent before it
     * is waiting for one reaches nothing.
     */
    private fun SceneDriver.dragUntilAsked(asked: CountDownLatch): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DRAG_WAIT_MILLIS)
        var time = 0L
        while (System.nanoTime() < deadline) {
            scene.sendPointerEvent(PointerEventType.Press, DRAG_FROM, timeMillis = time,
                buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
            if (passUntil(asked, FRAME_MILLIS)) return true
            scene.sendPointerEvent(PointerEventType.Move, DRAG_TO, timeMillis = time + 1,
                buttons = PointerButtons(isPrimaryPressed = true))
            if (passUntil(asked, FRAME_MILLIS)) return true
            scene.sendPointerEvent(PointerEventType.Release, DRAG_TO, timeMillis = time + 2,
                buttons = PointerButtons(), button = PointerButton.Primary)
            time += 3
        }
        return asked.count == 0L
    }

    private fun KortexScene.click(position: Offset, from: Long) {
        sendPointerEvent(PointerEventType.Press, position, timeMillis = from,
            buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
        sendPointerEvent(PointerEventType.Release, position, timeMillis = from + 1,
            buttons = PointerButtons(), button = PointerButton.Primary)
    }

    /**
     * The scene's failure once it has one, running [step] between looks, or null after [FAILURE_WAIT_MILLIS].
     *
     * [step] runs a pass by default, because an effect that fails after a `delay` resumes on this loop and
     * on nothing else: a look that only slept would wait out the whole budget and report no failure.
     */
    private fun SceneDriver.awaitFailure(step: () -> Unit = { pass() }): ContentFailure? {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(FAILURE_WAIT_MILLIS)
        while (scene.failure == null && System.nanoTime() < deadline) {
            step()
            Thread.sleep(PASS_MILLIS)
        }
        return scene.failure
    }

    /** Renders [content], then changes [state], which [content] reads, and waits for that change to ask for a frame. */
    private fun assertChangeAsksForFrame(state: MutableIntState, content: @Composable () -> Unit) {
        val signalled = CountDownLatch(1)
        val signals = AtomicInteger()
        val onInvalidate = {
            signals.incrementAndGet()
            signalled.countDown()
        }

        withScene(onInvalidate = onInvalidate) {
            scene.setContent(content)
            tick(0L)
            assertEquals(0, signals.get(), "composing and rendering must not ask for a frame on its own")

            state.intValue++

            assertTrue(passUntil(signalled), "the change never asked for a frame")
        }
    }

    /**
     * Runs [block] on a scene of [size] and the loop driving it, everything on this thread.
     *
     * `Dispatchers.Unconfined` will not do: `FrameRecomposer` rejects a context with no
     * `ContinuationInterceptor`, and Unconfined satisfies that check while never delivering `onInvalidate`
     * at all, since recomposition runs inline and the recomposer never awaits a frame. A real dispatcher
     * does exercise the contract, but an executor's own thread is not where a host renders: `measureAndLayout`
     * and `draw` on this thread would run against a recomposition on the executor's, which is a shape no
     * host is allowed to take. A [SceneLoop] is both, a dispatcher that has to be dispatched to and one the
     * rendering thread is the only one to run.
     */
    private fun withScene(
        size: IntSize = IntSize(SIDE, SIDE),
        surfaceSize: IntSize = size,
        onInvalidate: () -> Unit = {},
        platform: KortexPlatform = KortexPlatform.None,
        onFailure: (ContentFailure) -> Unit = {},
        block: SceneDriver.() -> Unit,
    ) {
        val loop = SceneLoop()
        val surface = Surface.makeRasterN32Premul(surfaceSize.width, surfaceSize.height)

        KortexScene(
            size = size,
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            frameContext = loop,
            onInvalidate = onInvalidate,
            platform = platform,
            onFailure = onFailure,
        ).use { scene -> SceneDriver(scene, surface, loop).block() }

        // The closed scene's own cleanup is queued work like any other, and no pass follows it.
        loop.drain()
    }

    /**
     * A host loop's frame context: everything content queues waits here, and only the thread that renders
     * takes it, which is the single-threaded shape `SurfaceScene` gives a shipping composition.
     */
    private class SceneLoop : CoroutineDispatcher() {
        private val queued = ConcurrentLinkedQueue<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queued += block
        }

        /** What was queued as this began, so work that queues itself again waits for the next pass. */
        fun runPass() {
            generateSequence(queued::poll).toList().forEach(Runnable::run)
        }

        /** Rounds until one finds nothing, or until [DRAIN_BOUND_ROUNDS] have run. */
        fun drain() {
            repeat(DRAIN_BOUND_ROUNDS) {
                if (queued.isEmpty()) return
                runPass()
            }
        }

        private companion object {
            const val DRAIN_BOUND_ROUNDS = 64
        }
    }

    /** A scene, what it draws into, and the loop that runs its work: a host, as small as one gets. */
    private class SceneDriver(
        val scene: KortexScene,
        val surface: Surface,
        private val loop: SceneLoop,
    ) {
        /** The work content queued, with no frame after it, as a host runs before it services a surface. */
        fun pass() {
            loop.runPass()
        }

        /** A pass and then a frame, which is one tick of a host's loop. */
        fun tick(frameTimeNanos: Long): EmptyResult<ContentFailure> {
            pass()
            return scene.render(surface.canvas.asComposeCanvas(), frameTimeNanos)
        }

        /** Whether [latch] trips within [budgetMillis], running a pass between looks. */
        fun passUntil(latch: CountDownLatch, budgetMillis: Long = WAIT_MILLIS): Boolean =
            passUntil(budgetMillis) { latch.count == 0L }

        /**
         * Runs passes for [millis] and asserts nothing about them: the window an absence needs to be absent
         * in, which has to be a window this loop is running in or every composition reads as idle.
         */
        fun idleFor(millis: Long) {
            passUntil(millis) { false }
        }

        private fun passUntil(budgetMillis: Long, reached: () -> Boolean): Boolean {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMillis)
            while (System.nanoTime() < deadline) {
                if (reached()) return true
                pass()
                Thread.sleep(PASS_MILLIS)
            }
            return reached()
        }
    }

    private fun Surface.pixelAt(x: Int, y: Int): Int {
        // Bound outside the apply: inside it `width`/`height` would resolve to the unallocated Bitmap's.
        val pixelWidth = width
        val pixelHeight = height
        val bitmap = Bitmap().apply { allocN32Pixels(pixelWidth, pixelHeight) }
        assertTrue(readPixels(bitmap, 0, 0), "readPixels failed")
        return bitmap.getColor(x, y)
    }

    private companion object {
        const val SIDE = 64
        const val WIDE = 128
        const val SHORT = 32
        const val BOX_DP = 32
        const val TRANSPARENT = 0
        const val IDLE_WINDOW_MILLIS = 250L
        const val FOCUS_FRAMES = 6
        const val FRAME_MILLIS = 60L
        const val EFFECT_DELAY_MILLIS = 50L
        const val FAILURE_WAIT_MILLIS = 5_000L
        const val DRAG_WAIT_MILLIS = 5_000L
        const val TOLD_MILLIS = 5_000L

        /** What every wait below is given, and how long it sleeps between the passes it runs. */
        const val WAIT_MILLIS = 5_000L
        const val PASS_MILLIS = 5L

        const val DRAGGED_TEXT = "dragged"

        // Far enough apart for the drag to pass the 18dp of touch slop a gesture starts after, and both inside
        // the scene, so neither leaves the content being dragged.
        val DRAG_FROM = Offset(8f, 8f)
        val DRAG_TO = Offset(8f, 56f)

        const val DRAW_FAILURE = "content threw while drawing"
        const val RECOMPOSE_FAILURE = "content threw while recomposing"
        const val KEY_FAILURE = "a key handler threw"
        const val POINTER_FAILURE = "a click handler threw"
        const val EFFECT_FAILURE = "an effect threw"
        const val ERROR_FAILURE = "content threw an Error"
        const val CLEANUP_FAILURE = "cleanup threw as the scene closed"
    }
}

/** Asks to be drawn again from inside its first draw, as content animating from its own draw does. */
private data object InvalidateOnFirstDraw : ModifierNodeElement<InvalidateOnFirstDrawNode>() {
    override fun create(): InvalidateOnFirstDrawNode = InvalidateOnFirstDrawNode()

    override fun update(node: InvalidateOnFirstDrawNode) = Unit
}

private class InvalidateOnFirstDrawNode : Modifier.Node(), DrawModifierNode {
    private var drawn = false

    override fun ContentDrawScope.draw() {
        drawContent()
        if (drawn) return
        drawn = true
        invalidateDraw()
    }
}
