package com.fromwau.kortex.wayland

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.fromwau.kortex.compose.KortexScene
import kotlinx.coroutines.asCoroutineDispatcher
import org.jetbrains.skia.Surface
import java.lang.foreign.MemorySegment
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives the listener callbacks with exactly the arguments libwayland passes them, so the seam between
 * the protocol and Compose is covered without needing a human to move a mouse.
 */
class InputDeliveryTest {
    @Test
    fun `a wayland pointer press and release clicks a composable`() {
        val clicked = CountDownLatch(1)
        val clicks = AtomicInteger()

        withScene { scene, surface ->
            scene.setContent {
                Box(Modifier.fillMaxSize().clickable { clicks.incrementAndGet(); clicked.countDown() })
            }
            scene.render(surface.canvas.asComposeCanvas(), 0L)

            val pointer = PointerInput(scene, scale = 1f)
            val centre = fixed(SIDE / 2)
            pointer.onEnter(NULL, NULL, 1, NULL, centre, centre)
            pointer.onMotion(NULL, NULL, 10, centre, centre)
            pointer.onButton(NULL, NULL, 2, 20, BTN_LEFT, PRESSED)
            pointer.onButton(NULL, NULL, 3, 30, BTN_LEFT, RELEASED)
            scene.render(surface.canvas.asComposeCanvas(), 1L)

            assertTrue(clicked.await(5, TimeUnit.SECONDS), "a wayland click never reached the composition")
            assertEquals(1, clicks.get(), "one press and release must be one click")
        }
    }

    @Test
    fun `wl_fixed positions are hit-tested where the pointer actually is`() {
        val clicks = AtomicInteger()

        withScene { scene, surface ->
            // The target occupies the top-left corner only, so a press far from it must miss.
            scene.setContent {
                Box(Modifier.size(TARGET_DP.dp).clickable { clicks.incrementAndGet() })
            }
            scene.render(surface.canvas.asComposeCanvas(), 0L)

            val pointer = PointerInput(scene, scale = 1f)
            val far = fixed(SIDE - 4)
            pointer.onEnter(NULL, NULL, 1, NULL, far, far)
            pointer.onButton(NULL, NULL, 2, 20, BTN_LEFT, PRESSED)
            pointer.onButton(NULL, NULL, 3, 30, BTN_LEFT, RELEASED)
            scene.render(surface.canvas.asComposeCanvas(), 1L)
            Thread.sleep(SETTLE_MILLIS)

            assertEquals(0, clicks.get(), "a press outside the target must not click it")
        }
    }

    @Test
    fun `a pointer position is scaled into scene pixels, not out of them`() {
        val clicks = AtomicInteger()

        withScene(density = SCALE) { scene, surface ->
            // At density 2 the target covers scene pixels 0..2*TARGET_DP, i.e. the top-left 32x32.
            scene.setContent { Box(Modifier.size(TARGET_DP.dp).clickable { clicks.incrementAndGet() }) }
            scene.render(surface.canvas.asComposeCanvas(), 0L)

            val pointer = PointerInput(scene, scale = SCALE)

            // Surface-local 24 is scene pixel 48 once scaled up, which is outside the target. Dividing
            // instead would give 12, land inside, and click it — which is the whole bug.
            pointer.onEnter(NULL, NULL, 1, NULL, fixed(OUTSIDE_LOGICAL), fixed(OUTSIDE_LOGICAL))
            pointer.onButton(NULL, NULL, 2, 20, BTN_LEFT, PRESSED)
            pointer.onButton(NULL, NULL, 3, 30, BTN_LEFT, RELEASED)
            scene.render(surface.canvas.asComposeCanvas(), 1L)
            Thread.sleep(SETTLE_MILLIS)
            assertEquals(0, clicks.get(), "a surface-local position outside the target must not click it")

            pointer.onMotion(NULL, NULL, 40, fixed(INSIDE_LOGICAL), fixed(INSIDE_LOGICAL))
            pointer.onButton(NULL, NULL, 4, 50, BTN_LEFT, PRESSED)
            pointer.onButton(NULL, NULL, 5, 60, BTN_LEFT, RELEASED)
            scene.render(surface.canvas.asComposeCanvas(), 2L)
            Thread.sleep(SETTLE_MILLIS)
            assertEquals(1, clicks.get(), "a surface-local position inside the target must click it")
        }
    }

    private fun withScene(density: Float = 1f, block: (KortexScene, Surface) -> Unit) {
        val dispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "kortex-input-test").apply { isDaemon = true }
        }.asCoroutineDispatcher()
        val surface = Surface.makeRasterN32Premul(SIDE, SIDE)
        dispatcher.use {
            KortexScene(
                size = IntSize(SIDE, SIDE),
                density = Density(density),
                layoutDirection = LayoutDirection.Ltr,
                frameContext = dispatcher,
                onInvalidate = {},
            ).use { scene -> block(scene, surface) }
        }
    }

    private companion object {
        val NULL: MemorySegment = MemorySegment.NULL
        const val SIDE = 64
        const val BTN_LEFT = 0x110
        const val PRESSED = 1
        const val RELEASED = 0
        const val SETTLE_MILLIS = 300L
        const val TARGET_DP = 16
        const val SCALE = 2f
        const val OUTSIDE_LOGICAL = 24
        const val INSIDE_LOGICAL = 8

        /** Physical pixels as wl_fixed_t, 24.8 fixed point. */
        fun fixed(pixels: Int) = pixels * 256
    }
}
