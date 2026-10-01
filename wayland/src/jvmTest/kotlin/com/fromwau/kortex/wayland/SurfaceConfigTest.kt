package com.fromwau.kortex.wayland

import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.getOrElse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.fail

/**
 * Pins that a [SurfaceConfig] reaches the compositor whole: its layer, its anchor and its exclusive
 * zone all decide where the surface lands, rather than being dropped or replaced by a default on the
 * way through [KortexSurface].
 *
 * The layer is checked against the level `hyprctl layers` files the surface under, not against
 * [Layer] mapped back, so a wrong wire value cannot cancel itself out across the two directions.
 */
class SurfaceConfigTest {
    @Test
    fun `a config's layer and anchor land the surface at the overlay level along the bottom edge`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use { wayland ->
            val monitor = bindFirstOutput(wayland)

            val config = SurfaceConfig(
                namespace = DOCK_NAMESPACE,
                layer = Layer.Overlay,
                anchor = setOf(Edge.Bottom, Edge.Left, Edge.Right),
                width = Length.WholeAxis,
                height = Length.Of(DOCK_HEIGHT.dp),
                exclusiveZone = ExclusiveZone.Reserve(DOCK_HEIGHT.dp),
            )
            val dock = KortexSurface.createOnLayer(wayland, config, output = monitor.proxy)
                .getOrElse { error -> fail("dock creation failed: $error") }

            dock.use {
                wayland.roundtrip()

                assertEquals(
                    OVERLAY_LEVEL, levelOf(DOCK_NAMESPACE),
                    "Layer.Overlay did not reach set_layer as the value the protocol gives it",
                )

                val geometry = assertNotNull(
                    Screen.geometry(DOCK_NAMESPACE), "hyprctl layers does not report $DOCK_NAMESPACE",
                )
                val monitorLogicalWidth = monitor.geometry.width / monitor.geometry.scale
                val monitorLogicalHeight = monitor.geometry.height / monitor.geometry.scale

                assertEquals(
                    monitor.geometry.y + monitorLogicalHeight - DOCK_HEIGHT, geometry.y,
                    "the Bottom anchor never reached the compositor",
                )
                assertEquals(monitor.geometry.x, geometry.x, "the Left anchor never reached the compositor")
                assertEquals(
                    monitorLogicalWidth, geometry.logicalWidth,
                    "anchoring Left and Right did not span the output",
                )
            }
        }
    }

    @Test
    fun `a config anchored to every edge with Overlap covers the output a panel reserves from`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use { wayland ->
            val monitor = bindFirstOutput(wayland)

            val panel = LayerShellSurface.create(
                wayland,
                SurfaceConfig.panel(Edge.Top, PANEL_HEIGHT.dp).copy(namespace = PANEL_NAMESPACE),
                output = monitor.proxy,
            ).getOrElse { error -> fail("panel creation failed: $error") }

            panel.use {
                panel.waitForConfigure().getOrElse { error -> fail("panel never configured: $error") }
                wayland.roundtrip()

                val config = SurfaceConfig(
                    namespace = BACKGROUND_NAMESPACE,
                    layer = Layer.Background,
                    anchor = setOf(Edge.Top, Edge.Bottom, Edge.Left, Edge.Right),
                    width = Length.WholeAxis,
                    height = Length.WholeAxis,
                    exclusiveZone = ExclusiveZone.Overlap,
                )
                val background = KortexSurface.createOnLayer(wayland, config, output = monitor.proxy)
                    .getOrElse { error -> fail("background creation failed: $error") }

                background.use {
                    wayland.roundtrip()

                    val geometry = assertNotNull(
                        Screen.geometry(BACKGROUND_NAMESPACE), "hyprctl layers does not report $BACKGROUND_NAMESPACE",
                    )
                    val monitorLogicalWidth = monitor.geometry.width / monitor.geometry.scale
                    val monitorLogicalHeight = monitor.geometry.height / monitor.geometry.scale

                    assertEquals(monitor.geometry.y, geometry.y, "Overlap was pushed below the panel")
                    assertEquals(
                        monitorLogicalHeight, geometry.logicalHeight,
                        "Overlap yielded the panel's reserved space instead of covering it",
                    )
                    assertEquals(
                        monitorLogicalWidth, geometry.logicalWidth,
                        "anchoring all four edges did not span the output",
                    )
                }
            }
        }
    }

    /** The level `hyprctl layers` files [namespace] under, which is the compositor's own view of set_layer. */
    private fun levelOf(namespace: String): String? = Hyprctl.layers()
        .values
        .firstNotNullOfOrNull { monitorLayers ->
            monitorLayers.levels
                .entries
                .firstOrNull { (_, entries) -> entries.any { it.namespace == namespace } }
                ?.key
        }

    private companion object {
        const val DOCK_NAMESPACE = "kortex-surface-config-dock"
        const val PANEL_NAMESPACE = "kortex-surface-config-panel"
        const val BACKGROUND_NAMESPACE = "kortex-surface-config-background"

        /** `zwlr_layer_shell_v1.layer`'s value for overlay, spelled out rather than read off [Layer]. */
        const val OVERLAY_LEVEL = "3"

        // Distinct from each other and from the heights other tests use, so a surface landing at the
        // wrong size shows up as a wrong number rather than an accidental match.
        const val DOCK_HEIGHT = 47
        const val PANEL_HEIGHT = 59
    }
}
