package com.fromwau.kortex.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.asCoroutineDispatcher
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class KortexSceneTest {
    @Test
    fun `composes into a canvas the host owns`() {
        withScene { scene, surface ->
            scene.setContent { Box(Modifier.fillMaxSize().background(Color.Red)) }
            scene.render(surface.canvas.asComposeCanvas(), 0L)

            assertEquals(Color.Red.toArgb(), surface.pixelAt(SIDE / 2, SIDE / 2))
        }
    }

    @Test
    fun `signals once when state changes`() {
        val signalled = CountDownLatch(1)
        val signals = AtomicInteger()
        val color = mutableStateOf(Color.Red)

        withScene(onInvalidate = { signals.incrementAndGet(); signalled.countDown() }) { scene, surface ->
            scene.setContent { Box(Modifier.fillMaxSize().background(color.value)) }
            scene.render(surface.canvas.asComposeCanvas(), 0L)
            assertEquals(0, signals.get(), "composing and rendering must not ask for a frame on its own")

            color.value = Color.Blue

            assertTrue(signalled.await(5, TimeUnit.SECONDS), "state change never asked for a frame")
            assertEquals(1, signals.get(), "one state change must ask for exactly one frame")
        }
    }

    @Test
    fun `stays silent while idle`() {
        val signals = AtomicInteger()

        withScene(onInvalidate = { signals.incrementAndGet() }) { scene, surface ->
            scene.setContent { Box(Modifier.fillMaxSize().background(Color.Red)) }
            scene.render(surface.canvas.asComposeCanvas(), 0L)

            // Asserting an absence needs a window to be absent in; a busy loop shows up here as a
            // non-zero count rather than as anything visibly wrong on screen.
            Thread.sleep(IDLE_WINDOW_MILLIS)
            scene.render(surface.canvas.asComposeCanvas(), 1L)
            Thread.sleep(IDLE_WINDOW_MILLIS)

            assertEquals(0, signals.get(), "an idle composition must never ask for a frame")
        }
    }

    @Test
    fun `resizes in place, without a new scene`() {
        withScene(size = IntSize(SIDE, SIDE), surfaceSize = IntSize(WIDE, SIDE)) { scene, surface ->
            scene.setContent { Box(Modifier.fillMaxSize().background(Color.Red)) }
            scene.render(surface.canvas.asComposeCanvas(), 0L)

            assertEquals(Color.Red.toArgb(), surface.pixelAt(SIDE / 2, SIDE / 2), "content missing before resize")
            assertNotEquals(Color.Red.toArgb(), surface.pixelAt(WIDE - 8, SIDE / 2), "content wider than the scene")

            scene.size = IntSize(WIDE, SHORT)
            surface.canvas.clear(TRANSPARENT)
            scene.render(surface.canvas.asComposeCanvas(), 1L)

            assertEquals(Color.Red.toArgb(), surface.pixelAt(WIDE - 8, SHORT / 2), "did not widen to the new size")
            assertNotEquals(Color.Red.toArgb(), surface.pixelAt(SIDE / 2, SIDE - 8), "did not shorten to the new size")
        }
    }

    @Test
    fun `density change rescales content`() {
        withScene { scene, surface ->
            scene.setContent { Box(Modifier.size(BOX_DP.dp).background(Color.Red)) }
            scene.render(surface.canvas.asComposeCanvas(), 0L)

            val probe = BOX_DP + BOX_DP / 4
            assertNotEquals(Color.Red.toArgb(), surface.pixelAt(probe, probe), "box already covers the probe at 1x")

            scene.density = Density(2f)
            surface.canvas.clear(TRANSPARENT)
            scene.render(surface.canvas.asComposeCanvas(), 1L)

            assertEquals(Color.Red.toArgb(), surface.pixelAt(probe, probe), "box did not grow with density")
        }
    }

    @Test
    fun `a pointer press and release reaches the composition`() {
        val clicked = CountDownLatch(1)
        val clicks = AtomicInteger()

        withScene { scene, surface ->
            // The target covers only the top-left BOX_DP square of a larger scene, so a press outside it
            // proves the event is hit-tested rather than merely delivered.
            scene.setContent {
                Box(Modifier.size(BOX_DP.dp).clickable { clicks.incrementAndGet(); clicked.countDown() })
            }
            scene.render(surface.canvas.asComposeCanvas(), 0L)

            scene.click(Offset(SIDE - 8f, SIDE - 8f), from = 0L)
            scene.render(surface.canvas.asComposeCanvas(), 1L)
            assertEquals(0, clicks.get(), "a press outside the target must not click it")

            scene.click(Offset(BOX_DP / 2f, BOX_DP / 2f), from = 2L)
            scene.render(surface.canvas.asComposeCanvas(), 2L)

            assertTrue(clicked.await(5, TimeUnit.SECONDS), "onClick never fired")
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

        withScene(platform = host) { scene, surface ->
            scene.setContent {
                Box(Modifier.size(BOX_DP.dp).pointerHoverIcon(PointerIcon.Text))
            }
            scene.render(surface.canvas.asComposeCanvas(), 0L)

            scene.sendPointerEvent(PointerEventType.Move, Offset(SIDE - 8f, SIDE - 8f), timeMillis = 0L)
            scene.render(surface.canvas.asComposeCanvas(), 1L)
            assertTrue(
                synchronized(cursors) { cursors.none { it == KortexCursor.Text } },
                "a text cursor outside the text region",
            )

            scene.sendPointerEvent(PointerEventType.Move, Offset(BOX_DP / 2f, BOX_DP / 2f), timeMillis = 1L)
            scene.render(surface.canvas.asComposeCanvas(), 2L)

            assertEquals(
                KortexCursor.Text,
                synchronized(cursors) { cursors.lastOrNull() },
                "hovering the text region must ask the host for a text cursor",
            )
        }
    }

    private fun KortexScene.click(position: Offset, from: Long) {
        sendPointerEvent(PointerEventType.Press, position, timeMillis = from,
            buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
        sendPointerEvent(PointerEventType.Release, position, timeMillis = from + 1,
            buttons = PointerButtons(), button = PointerButton.Primary)
    }

    private fun withScene(
        size: IntSize = IntSize(SIDE, SIDE),
        surfaceSize: IntSize = size,
        onInvalidate: () -> Unit = {},
        platform: KortexPlatform = KortexPlatform.None,
        block: (KortexScene, Surface) -> Unit,
    ) {
        // FrameRecomposer rejects a context with no ContinuationInterceptor, and Dispatchers.Unconfined
        // satisfies that check while never delivering onInvalidate at all — recomposition runs inline, so
        // the recomposer never awaits a frame. Only a real dispatcher exercises the contract.
        val dispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "kortex-test").apply { isDaemon = true }
        }.asCoroutineDispatcher()

        val surface = Surface.makeRasterN32Premul(surfaceSize.width, surfaceSize.height)

        dispatcher.use {
            KortexScene(
                size = size,
                density = Density(1f),
                layoutDirection = LayoutDirection.Ltr,
                frameContext = dispatcher,
                onInvalidate = onInvalidate,
                platform = platform,
            ).use { scene -> block(scene, surface) }
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
    }
}
