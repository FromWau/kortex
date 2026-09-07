package com.fromwau.kortex.wayland

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.getOrElse
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The compositor keeps a committed buffer until it sends `wl_buffer.release`. Drawing into one before
 * then corrupts the frame being scanned out, and shows as tearing rather than as any error.
 */
class BufferReleaseTest {
    @Test
    fun `the compositor hands buffers back and frames rotate`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }
        val colour = mutableStateOf(Color.Red)

        display.use {
            val bar = KortexBar.create(it, namespace = NAMESPACE, height = BAR_HEIGHT.dp)
                .getOrElse { error -> fail("bar creation failed: $error") }

            bar.use {
                bar.setContent { Box(Modifier.fillMaxSize().background(colour.value)) }

                // Drive real frames: each state change asks for one, and each committed frame is a
                // buffer the compositor has to give back.
                repeat(FRAMES) { frame ->
                    colour.value = if (frame % 2 == 0) Color.Blue else Color.Red
                    bar.pump(timeoutMillis = PUMP_MILLIS)
                }

                assertTrue(
                    bar.releases > 0,
                    "no buffer was ever released, so nothing tracks when one is safe to draw into",
                )
            }
        }
    }

    private companion object {
        const val NAMESPACE = "kortex"
        const val BAR_HEIGHT = 32
        const val FRAMES = 6
        const val PUMP_MILLIS = 250L
    }
}
