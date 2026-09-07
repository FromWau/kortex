package com.fromwau.kortex.wayland

import com.fromwau.kern.result.getOrElse
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

class LayerSurfaceTest {
    @Test
    fun `the compositor places a layer surface and configures it`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use {
            val bar = LayerSurface.create(it, namespace = NAMESPACE, height = BAR_HEIGHT)
                .getOrElse { error -> fail("layer surface creation failed: $error") }

            bar.use {
                assertTrue(bar.waitForConfigure(), "compositor never configured the layer surface")
                assertTrue(!bar.closed, "compositor closed the layer surface")

                println("CONFIGURE ${bar.logicalWidth}x${bar.logicalHeight}")
                assertTrue(bar.logicalWidth > 0, "configure carried a zero width")
                assertTrue(bar.logicalHeight > 0, "configure carried a zero height")

                val layers = ProcessBuilder("hyprctl", "layers").redirectErrorStream(true)
                    .start().inputStream.bufferedReader().readText()
                println("HYPRCTL-MATCH " + layers.lines().count { line -> NAMESPACE in line })
                assertTrue(NAMESPACE in layers, "hyprctl layers does not list $NAMESPACE")
            }
        }
    }

    private companion object {
        const val NAMESPACE = "kortex"
        const val BAR_HEIGHT = 32
    }
}
