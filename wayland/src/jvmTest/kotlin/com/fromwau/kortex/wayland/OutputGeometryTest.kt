package com.fromwau.kortex.wayland

import com.fromwau.kern.result.getOrElse
import java.lang.foreign.MemorySegment
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Verifies `OutputListener` reads `wl_output`'s geometry, current mode, name and scale, and publishes
 * them only once `done` arrives.
 *
 * The first leg checks every real monitor against `hyprctl monitors -j`. It cannot exercise the
 * current-mode-flag filter though: Hyprland (`src/protocols/core/Output.cpp` in v0.56.2, the version
 * installed here) always sends exactly one `mode` event, always flagged current, so no monitor on this
 * machine can ever emit a non-current one to filter out. The second leg drives the listener directly
 * with a fabricated event sequence to cover that branch.
 */
class OutputGeometryTest {
    @Test
    fun `kortex publishes the same name, position, mode size and scale hyprctl reports for every monitor`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use { wayland ->
            val bound = wayland.globals.filter { it.interfaceName == WL_OUTPUT }.map { global ->
                val proxy = wayland.bind(global, LibWayland.outputInterface, WlVersion.OUTPUT)
                val listener = OutputListener()
                listener.install(proxy)
                global to listener
            }

            bound.forEach { (global, listener) ->
                assertNull(listener.geometry, "output ${global.name} published geometry before its first roundtrip")
            }

            wayland.roundtrip()

            bound.forEach { (global, listener) ->
                val geometry = assertNotNull(
                    listener.geometry,
                    "output ${global.name} never published geometry after done",
                )
                val expected = assertNotNull(
                    monitorInfo(geometry.name),
                    "hyprctl monitors -j reported nothing named ${geometry.name}",
                )
                assertEquals(expected.x, geometry.x, "${geometry.name}: x position mismatch")
                assertEquals(expected.y, geometry.y, "${geometry.name}: y position mismatch")
                assertEquals(expected.width, geometry.width, "${geometry.name}: mode width mismatch")
                assertEquals(expected.height, geometry.height, "${geometry.name}: mode height mismatch")
                assertEquals(expected.transform, geometry.transform, "${geometry.name}: transform mismatch")
                // wl_output.scale is an integer and Hyprland ceil-rounds a fractional monitor scale.
                assertEquals(ceil(expected.scale).toInt(), geometry.scale, "${geometry.name}: scale mismatch")
            }
        }
    }

    @Test
    fun `a bar's published geometry is reachable through the shell that owns it`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use { wayland ->
            val shell = KortexShell.create(wayland, namespace = SHELL_NAMESPACE) { }
                .getOrElse { error -> fail("shell creation failed: $error") }

            shell.use {
                val geometries = shell.activeGeometries
                assertTrue(geometries.isNotEmpty(), "shell has no bars to read geometry through")

                geometries.forEach { geometry ->
                    val published = assertNotNull(geometry, "a live bar's output geometry never reached the shell")
                    val expected = assertNotNull(
                        monitorInfo(published.name),
                        "hyprctl monitors -j reported nothing named ${published.name}",
                    )
                    assertEquals(expected.width, published.width, "${published.name}: mode width mismatch")
                    assertEquals(expected.height, published.height, "${published.name}: mode height mismatch")
                }
            }
        }
    }

    /**
     * Fabricates a stale mode before the current one and another stale mode after it, so neither
     * "first received" nor "last received" would pass this by accident.
     */
    @Test
    fun `only the mode flagged current survives, and nothing publishes before done`() {
        val listener = OutputListener()

        listener.onGeometry(
            NONE, NONE, X, Y, 0, 0, 0, LibWayland.cString("make"), LibWayland.cString("model"), TRANSFORM,
        )
        listener.onMode(NONE, NONE, flags = NOT_CURRENT, width = 640, height = 480, refresh = 0)
        listener.onMode(NONE, NONE, flags = CURRENT, width = 1920, height = 1080, refresh = 60_000)
        listener.onMode(NONE, NONE, flags = NOT_CURRENT, width = 111, height = 222, refresh = 0)
        listener.onScale(NONE, NONE, factor = SCALE)
        listener.onName(NONE, NONE, LibWayland.cString(NAME))
        listener.onDescription(NONE, NONE, LibWayland.cString(DESCRIPTION))

        assertNull(listener.geometry, "geometry must not be visible before done, even with every other event in")

        listener.onDone(NONE, NONE)

        val geometry = assertNotNull(listener.geometry, "done must publish the accumulated geometry")
        assertEquals(NAME, geometry.name)
        assertEquals(DESCRIPTION, geometry.description)
        assertEquals(X, geometry.x)
        assertEquals(Y, geometry.y)
        assertEquals(TRANSFORM, geometry.transform)
        assertEquals(SCALE, geometry.scale)
        assertEquals(1920, geometry.width, "took a mode other than the one flagged current")
        assertEquals(1080, geometry.height, "took a mode other than the one flagged current")
    }

    private data class MonitorInfo(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val scale: Float,
        val transform: Int,
    )

    /** [name] is `wl_output.name`, the same string hyprctl's own "name" field reports for a monitor. */
    private fun monitorInfo(name: String): MonitorInfo? {
        val json = Hyprctl.run("monitors", "-j")
        val at = json.indexOf("\"name\": \"$name\"")
        if (at < 0) return null
        fun intField(key: String) = Regex("\"$key\": (-?\\d+)").find(json, at)?.groupValues?.get(1)?.toIntOrNull()
        val x = intField("x") ?: return null
        val y = intField("y") ?: return null
        val width = intField("width") ?: return null
        val height = intField("height") ?: return null
        val transform = intField("transform") ?: return null
        val scale = Regex("\"scale\": ([0-9.]+)").find(json, at)?.groupValues?.get(1)?.toFloatOrNull() ?: return null
        return MonitorInfo(x, y, width, height, scale, transform)
    }

    private companion object {
        const val WL_OUTPUT = "wl_output"
        const val CURRENT = 0x1
        const val NOT_CURRENT = 0
        const val X = 7
        const val Y = 13
        const val SCALE = 2
        const val TRANSFORM = 3
        const val NAME = "SYNTH-1"
        const val DESCRIPTION = "Synthetic output for the current-mode-flag test"
        const val SHELL_NAMESPACE = "kortex-geometry-test"
        val NONE: MemorySegment = MemorySegment.NULL
    }
}
