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

class KortexBarTest {
    @Test
    fun `compose renders onto a layer surface`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use {
            val bar = KortexBar.create(display, namespace = NAMESPACE, height = BAR_HEIGHT.dp)
                .getOrElse { error -> fail("bar creation failed: $error") }

            bar.use {
                bar.setContent { Box(Modifier.fillMaxSize().background(Color(GREY, GREY, GREY))) }
                display.roundtrip()

                val geometry = assertNotNull(Screen.geometry(NAMESPACE), "hyprctl did not report $NAMESPACE")
                println("BAR ${bar.bufferSize.width}x${bar.bufferSize.height} at $geometry")

                val captured = Screen.settledPixel(geometry)
                println("CAPTURED %08X".format(captured))
                assertEquals(
                    MID_GREY, captured,
                    "Compose's output did not reach the screen; a swapped channel order would show here",
                )
            }
        }
    }

    private companion object {
        const val NAMESPACE = "kortex"
        const val BAR_HEIGHT = 32
        const val GREY = 128
        const val MID_GREY = 0xFF808080.toInt()
    }
}
