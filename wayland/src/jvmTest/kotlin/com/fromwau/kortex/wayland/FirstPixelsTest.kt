package com.fromwau.kortex.wayland

import com.fromwau.kern.result.getOrElse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

class FirstPixelsTest {
    @Test
    fun `a filled buffer reaches the screen unchanged`() {
        onScreen { capture ->
            // Mid grey survives this display pipeline byte for byte, so it is the one value that can
            // assert the shm path end to end without a tolerance.
            assertEquals(MID_GREY, capture(MID_GREY), "the buffer did not reach the screen intact")
        }
    }

    @Test
    fun `ARGB8888 channels are not swapped`() {
        // A wrong buffer format shows up as swapped red and blue, never as an error. This display
        // compresses saturated values, so assert which channel dominates rather than an exact value.
        onScreen { capture ->
            val red = capture(RED)
            assertTrue(red.red > DOMINANT, "red did not land in the red channel: ${hex(red)}")
            assertTrue(red.green < RESIDUAL && red.blue < RESIDUAL, "red bled into other channels: ${hex(red)}")

            val blue = capture(BLUE)
            assertTrue(blue.blue > DOMINANT, "blue did not land in the blue channel: ${hex(blue)}")
            assertTrue(blue.red < RESIDUAL && blue.green < RESIDUAL, "blue bled into other channels: ${hex(blue)}")
        }
    }

    /** Puts a bar on screen and hands the body a function that fills it and captures the result. */
    private fun onScreen(body: (capture: (Int) -> Int) -> Unit) {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }
        display.use {
            val shm = Shm.bind(display).getOrElse { error -> fail("shm bind failed: $error") }
            val bar = LayerSurface.create(display, namespace = NAMESPACE, height = BAR_HEIGHT)
                .getOrElse { error -> fail("layer surface creation failed: $error") }

            bar.use {
                assertTrue(bar.waitForConfigure(), "compositor never configured the layer surface")
                val geometry = assertNotNull(Screen.geometry(NAMESPACE), "hyprctl did not report $NAMESPACE")

                body { argb ->
                    val buffer = shm.createBuffer(bar.logicalWidth, bar.logicalHeight)
                        .getOrElse { error -> fail("shm buffer allocation failed: $error") }

                    buffer.use {
                        buffer.fill(argb)
                        bar.attach(buffer)
                        bar.commit()
                        display.roundtrip()
                        Screen.settledPixel(geometry)
                    }
                }
            }
        }
    }

    private val Int.red get() = (this shr 16) and 0xFF
    private val Int.green get() = (this shr 8) and 0xFF
    private val Int.blue get() = this and 0xFF
    private fun hex(argb: Int) = "%08X".format(argb)

    private companion object {
        const val NAMESPACE = "kortex"
        const val BAR_HEIGHT = 32
        const val MID_GREY = 0xFF808080.toInt()
        const val RED = 0xFFFF0000.toInt()
        const val BLUE = 0xFF0000FF.toInt()
        const val DOMINANT = 200
        const val RESIDUAL = 20
    }
}
