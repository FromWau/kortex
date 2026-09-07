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
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A busy loop paints the same pixels as a correctly paced one and looks identical on screen, so
 * idleness is asserted as an absence of committed frames.
 */
class IdleFrameTest {
    @Test
    fun `renders nothing while idle, then resumes once state changes`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }
        val colour = mutableStateOf(Color.Red)

        display.use {
            val bar = KortexBar.create(it, namespace = NAMESPACE, height = BAR_HEIGHT.dp)
                .getOrElse { error -> fail("bar creation failed: $error") }

            bar.use {
                bar.setContent { Box(Modifier.fillMaxSize().background(colour.value)) }
                // Lets any transient renders from the initial configure/rescale settle before baselining.
                bar.pump(timeoutMillis = SETTLE_MILLIS)

                val baseline = bar.renders
                bar.pump(timeoutMillis = IDLE_WINDOW_MILLIS)
                assertEquals(baseline, bar.renders, "a static composition must not render any further frames")

                colour.value = Color.Blue
                bar.pump(timeoutMillis = SETTLE_MILLIS) { bar.renders > baseline }
                assertTrue(bar.renders > baseline, "a state change never produced a rendered frame")
            }
        }
    }

    private companion object {
        const val NAMESPACE = "kortex"
        const val BAR_HEIGHT = 32
        const val SETTLE_MILLIS = 500L

        // wl_surface.frame fires at output refresh rate (119.88 Hz on this machine), so a busy loop would
        // commit well over a hundred frames in this window; a truly idle bar commits exactly zero.
        const val IDLE_WINDOW_MILLIS = 1000L
    }
}
