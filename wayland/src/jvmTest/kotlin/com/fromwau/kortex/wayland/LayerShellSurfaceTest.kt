package com.fromwau.kortex.wayland

import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.getOrElse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

class LayerShellSurfaceTest {
    @Test
    fun `the compositor places a layer surface and configures it`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use {
            val bar = LayerShellSurface.create(
                it, namespace = NAMESPACE, height = BAR_HEIGHT,
                exclusiveZone = ExclusiveZone.Reserve(BAR_HEIGHT.dp),
            ).getOrElse { error -> fail("layer surface creation failed: $error") }

            bar.use {
                bar.waitForConfigure()
                    .getOrElse { error -> fail("compositor never configured the layer surface: $error") }
                assertTrue(!bar.closed, "compositor closed the layer surface")

                assertTrue(bar.logicalWidth > 0, "configure carried a zero width")
                assertTrue(bar.logicalHeight > 0, "configure carried a zero height")

                assertNotNull(Screen.geometry(NAMESPACE), "hyprctl layers does not report $NAMESPACE")
            }
        }
    }

    @Test
    fun `a layer surface created at a given Layer is reported at that layer's level`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use {
            val layer = Layer.Overlay
            val overlay = LayerShellSurface.create(
                it,
                namespace = NAMESPACE,
                height = BAR_HEIGHT,
                exclusiveZone = ExclusiveZone.Yield,
                layer = layer,
            ).getOrElse { error -> fail("layer surface creation failed: $error") }

            overlay.use {
                overlay.waitForConfigure()
                    .getOrElse { error -> fail("compositor never configured the layer surface: $error") }

                val geometry = assertNotNull(Screen.geometry(NAMESPACE), "hyprctl layers did not report $NAMESPACE")
                assertEquals(layer, geometry.layer, "$NAMESPACE landed at the wrong layer level")
            }
        }
    }

    private companion object {
        const val NAMESPACE = "kortex"
        const val BAR_HEIGHT = 32
    }
}
