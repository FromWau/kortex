package com.fromwau.kortex.wayland

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableIntStateOf
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
import kotlin.test.assertTrue
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
            onBareSurface(display, CONFIG) { bar, scene ->
                scene.setContent { Box(Modifier.fillMaxSize().background(colour.value)) }
                display.roundtrip()

                val geometry = assertNotNull(Screen.geometry(NAMESPACE), "hyprctl did not report $NAMESPACE")
                assertNotEquals(AFTER, Screen.pixelReaching(geometry, BEFORE), "the bar already showed the target colour")

                colour.value = Color.Blue
                // Pumps the connection only; nothing here renders, so the frame has to be driven by the
                // composition asking for one.
                bar.pumpOrFail(timeoutMillis = PUMP_MILLIS)

                assertEquals(AFTER, Screen.pixelReaching(geometry, AFTER), "the state change never reached the screen")
            }
        }
    }

    @Test
    fun `a counter read only while drawing is drawn again each time it changes`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }
        val counter = mutableIntStateOf(0)
        val drawn = mutableListOf<Int>()

        display.use {
            onBareSurface(display, CONFIG) { bar, scene ->
                scene.setContent { Canvas(Modifier.fillMaxSize()) { drawn += counter.intValue } }

                repeat(BUMPS) {
                    val value = ++counter.intValue
                    val redrawn = bar.pumpOrFail(timeoutMillis = PUMP_MILLIS) { value in drawn }
                    assertTrue(redrawn, "the bar never drew the counter at $value; it drew $drawn")
                }
            }
        }
    }

    private companion object {
        const val NAMESPACE = "kortex"
        const val BAR_HEIGHT = 32
        const val PUMP_MILLIS = 1500L
        const val BUMPS = 5
        val BEFORE = Color.Red.toArgb()
        val AFTER = Color.Blue.toArgb()

        val CONFIG = SurfaceConfig.panel(edge = Edge.Top, thickness = BAR_HEIGHT.dp).copy(namespace = NAMESPACE)
    }
}
