package com.fromwau.kortex.wayland

import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.errorOrNull
import com.fromwau.kern.result.getOrElse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

class LayerShellSurfaceTest {
    @Test
    fun `the compositor places a layer surface and configures it`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use {
            val bar = LayerShellSurface
                .create(it, topBar(ExclusiveZone.Reserve(BAR_HEIGHT.dp)))
                .getOrElse { error -> fail("layer surface creation failed: $error") }

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
            val overlay = LayerShellSurface
                .create(it, topBar(ExclusiveZone.Yield).copy(layer = layer))
                .getOrElse { error -> fail("layer surface creation failed: $error") }

            overlay.use {
                overlay.waitForConfigure()
                    .getOrElse { error -> fail("compositor never configured the layer surface: $error") }

                val geometry = assertNotNull(Screen.geometry(NAMESPACE), "hyprctl layers did not report $NAMESPACE")
                assertEquals(layer, geometry.layer, "$NAMESPACE landed at the wrong layer level")
            }
        }
    }

    // Can race: it failed once in a full run as ConnectionError(errno=104) instead. killConnection waits for the
    // hang-up to close the one window found for that, and 700 stress runs since have not failed it.
    @Test
    fun `waitForConfigure on a connection that died before any configure returns the connection's error`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use {
            killConnection(it)
            // create only sends, so it succeeds before the connection's death has been read.
            val bar = LayerShellSurface
                .create(it, topBar(ExclusiveZone.Yield))
                .getOrElse { error -> fail("layer surface creation failed: $error") }

            bar.use {
                val configured = bar.waitForConfigure()
                val violation = assertIs<KortexError.ProtocolViolation>(
                    configured.errorOrNull(),
                    "a surface on a connection that died did not fail to configure with its error: $configured",
                )
                assertEquals("wl_registry", violation.interfaceName, "the protocol error named another object")
            }
        }
    }

    /** A bar across the top of the output, spanning its width. */
    private fun topBar(exclusiveZone: ExclusiveZone) = SurfaceConfig(
        namespace = NAMESPACE,
        anchor = setOf(Edge.Top, Edge.Left, Edge.Right),
        width = Length.WholeAxis,
        height = Length.Of(BAR_HEIGHT.dp),
        exclusiveZone = exclusiveZone,
    )

    private companion object {
        const val NAMESPACE = "kortex"
        const val BAR_HEIGHT = 32
    }
}
