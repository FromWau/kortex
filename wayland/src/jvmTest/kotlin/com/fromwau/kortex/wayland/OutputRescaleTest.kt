package com.fromwau.kortex.wayland

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.getOrElse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A client cannot make a real compositor send `wl_output.scale`, so this drives [KortexBar.scaleOverride]
 * instead. It proves the reaction is right — rebuilt buffers, `scene.size`, `scene.density` — but not
 * that a real event reaches that seam.
 */
class OutputRescaleTest {
    @Test
    fun `an observed scale change resizes the buffers and updates the density`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use {
            val bar = KortexBar.create(display, namespace = NAMESPACE, height = BAR_HEIGHT.dp)
                .getOrElse { error -> fail("bar creation failed: $error") }

            bar.use {
                bar.setContent { Box(Modifier.fillMaxSize().background(Color.Red)) }
                bar.pump(timeoutMillis = PUMP_MILLIS)

                val initialScale = bar.currentBufferScale
                val logicalWidth = bar.bufferSize.width / initialScale
                val logicalHeight = bar.bufferSize.height / initialScale
                val newScale = if (initialScale == 1) 2 else 1

                bar.scaleOverride = newScale
                val rescaled = bar.pump(timeoutMillis = PUMP_MILLIS) { bar.currentBufferScale == newScale }
                assertTrue(rescaled, "the observed scale change never reached bufferScale")

                assertEquals(
                    0, bar.bufferSize.width % newScale, "buffer width is not an exact multiple of the new scale",
                )
                assertEquals(
                    0, bar.bufferSize.height % newScale, "buffer height is not an exact multiple of the new scale",
                )
                assertEquals(
                    logicalWidth * newScale, bar.bufferSize.width,
                    "buffer width did not follow logicalWidth * newScale",
                )
                assertEquals(
                    logicalHeight * newScale, bar.bufferSize.height,
                    "buffer height did not follow logicalHeight * newScale",
                )
                assertEquals(
                    Density(newScale.toFloat()), bar.density,
                    "the composition's density is still the one computed at startup",
                )
            }
        }
    }

    private companion object {
        const val NAMESPACE = "kortex"
        const val BAR_HEIGHT = 32
        const val PUMP_MILLIS = 1500L
    }
}
