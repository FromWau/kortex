package com.fromwau.kortex.wayland

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.getOrElse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * `zwlr_layer_surface_v1.configure` can arrive again with a different size — on a scale change, a mode
 * change, or just twice during setup — and a bar that reacts only to the first keeps its old buffers.
 */
class ReconfigureResizeTest {
    @Test
    fun `a later configure with a different size resizes the buffers and the surface`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }
        val scale = WlOutput.detectScale(display)

        display.use {
            val bar = KortexBar.create(display, namespace = NAMESPACE, height = INITIAL_HEIGHT.dp)
                .getOrElse { error -> fail("bar creation failed: $error") }

            bar.use {
                bar.setContent { Box(Modifier.fillMaxSize().background(Color.Red)) }
                bar.pump(timeoutMillis = PUMP_MILLIS)
                assertEquals(
                    INITIAL_HEIGHT * scale, bar.bufferSize.height,
                    "the bar did not start at the size its own construction should have produced",
                )

                bar.requestSize(SPAN_ANCHORED_AXIS.dp, RESIZED_HEIGHT.dp)
                val resized = bar.pump(timeoutMillis = PUMP_MILLIS) { bar.bufferSize.height == RESIZED_HEIGHT * scale }
                assertTrue(
                    resized, "a later configure never resized the buffers; bar.height stayed ${bar.bufferSize.height}",
                )

                // The commit from the resize may still be in flight; force it through before reading hyprctl.
                display.roundtrip()
                val geometry = assertNotNull(Screen.geometry(NAMESPACE), "hyprctl did not report $NAMESPACE")
                assertEquals(
                    RESIZED_HEIGHT, geometry.logicalHeight,
                    "the compositor still sees the old surface size: $geometry",
                )
            }
        }
    }

    private companion object {
        const val NAMESPACE = "kortex"
        const val INITIAL_HEIGHT = 32
        const val RESIZED_HEIGHT = 64
        const val PUMP_MILLIS = 1500L
        const val SPAN_ANCHORED_AXIS = 0
    }
}
