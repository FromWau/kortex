package com.fromwau.kortex.wayland

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.getOrElse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.fail

/**
 * State that changes outside a render has to reach the screen on its own.
 *
 * Pointer delivery, key delivery and Compose can all work while the bar still looks frozen, because a
 * composition that invalidates but never receives a frame never redraws.
 */
class InvalidationRenderTest {
    @Test
    fun `a state change redraws the bar without an explicit render`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }
        val colour = mutableStateOf(Color.Red)

        display.use {
            val bar = KortexBar.create(display, namespace = NAMESPACE, height = BAR_HEIGHT.dp)
                .getOrElse { error -> fail("bar creation failed: $error") }

            bar.use {
                bar.setContent { Box(Modifier.fillMaxSize().background(colour.value)) }
                display.roundtrip()

                val geometry = assertNotNull(Screen.geometry(NAMESPACE), "hyprctl did not report $NAMESPACE")
                assertNotEquals(AFTER, Screen.settledPixel(geometry), "the bar already showed the target colour")

                colour.value = Color.Blue
                // Pumps the connection only; nothing here renders, so the frame has to be driven by the
                // composition asking for one.
                bar.pump(timeoutMillis = PUMP_MILLIS)

                assertEquals(AFTER, Screen.settledPixel(geometry), "the state change never reached the screen")
            }
        }
    }

    private companion object {
        const val NAMESPACE = "kortex"
        const val BAR_HEIGHT = 32
        const val PUMP_MILLIS = 1500L
        val AFTER = Color.Blue.toArgb()
    }
}
