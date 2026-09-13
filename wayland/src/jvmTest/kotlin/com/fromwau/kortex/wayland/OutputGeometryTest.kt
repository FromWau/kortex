package com.fromwau.kortex.wayland

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.getOrElse
import java.lang.foreign.Arena
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
 * The first leg cross-checks every real monitor against `hyprctl monitors -j`. Against an unrotated
 * monitor at the origin with an integer scale, its `x`, `y`, `transform` and `scale` assertions expect
 * exactly [OutputListener]'s own defaults, so on this machine only `width`, `height` and `name` can
 * tell a wired field from an unwired one. It cannot reach the current-mode-flag filter at all, because
 * Hyprland (`src/protocols/core/Output.cpp` in v0.56.2, installed here) sends one `mode` event and
 * always flags it current. The second leg drives the listener with a fabricated sequence in which no
 * value can be mistaken for a default, and is what pins every field's wiring.
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
                    Hyprctl.monitors().firstOrNull { it.name == geometry.name },
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
    fun `a surface's published geometry is reachable through the shell that owns it`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use { wayland ->
            val shell = KortexShell.create(wayland, SurfaceSpec(CONFIG) { })
                .getOrElse { error -> fail("shell creation failed: $error") }

            shell.useOrFail {
                val active = shell.activeSurfaces
                assertTrue(active.isNotEmpty(), "shell has no surfaces to read geometry through")

                active.forEach { entry ->
                    val published = assertNotNull(
                        entry.geometry,
                        "a live surface's output geometry never reached the shell",
                    )
                    val expected = assertNotNull(
                        Hyprctl.monitors().firstOrNull { it.name == published.name },
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
        val strings = Arena.ofAuto()

        listener.onGeometry(
            NONE, NONE, X, Y, 0, 0, 0, strings.allocateFrom("make"), strings.allocateFrom("model"), TRANSFORM,
        )
        listener.onMode(NONE, NONE, flags = NOT_CURRENT, width = 640, height = 480, refresh = 0)
        listener.onMode(NONE, NONE, flags = CURRENT, width = 1920, height = 1080, refresh = 60_000)
        listener.onMode(NONE, NONE, flags = NOT_CURRENT, width = 111, height = 222, refresh = 0)
        listener.onScale(NONE, NONE, factor = SCALE)
        listener.onName(NONE, NONE, strings.allocateFrom(NAME))
        listener.onDescription(NONE, NONE, strings.allocateFrom(DESCRIPTION))

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

    /**
     * A re-sent scale or mode replaces the published geometry whole, at any time, so content reading it
     * through [KortexHost] has to recompose; that needs both the read and the write to reach the
     * snapshot system rather than a plain field.
     */
    @Test
    fun `reading and republishing geometry reaches the snapshot system`() {
        val listener = OutputListener()
        listener.onScale(NONE, NONE, factor = SCALE)
        listener.onDone(NONE, NONE)

        val reads = mutableListOf<Any>()
        val published = Snapshot.observe(readObserver = reads::add) { listener.geometry }
        assertEquals(SCALE, assertNotNull(published).scale)
        assertTrue(reads.isNotEmpty(), "reading geometry recorded no snapshot read, so nothing can recompose on it")

        val writes = mutableListOf<Any>()
        Snapshot.observe(writeObserver = writes::add) {
            listener.onScale(NONE, NONE, factor = RESCALED)
            listener.onDone(NONE, NONE)
        }
        assertTrue(writes.isNotEmpty(), "republishing geometry recorded no snapshot write")
        assertEquals(
            RESCALED, assertNotNull(listener.geometry).scale,
            "the re-sent scale did not replace the published one",
        )
    }

    private companion object {
        const val WL_OUTPUT = "wl_output"
        const val CURRENT = 0x1
        const val NOT_CURRENT = 0
        const val X = 7
        const val Y = 13
        const val SCALE = 2
        const val RESCALED = 3
        const val TRANSFORM = 3
        const val NAME = "SYNTH-1"
        const val DESCRIPTION = "Synthetic output for the current-mode-flag test"
        const val SHELL_NAMESPACE = "kortex-geometry-test"
        const val SURFACE_HEIGHT = 32
        val NONE: MemorySegment = MemorySegment.NULL

        val CONFIG = SurfaceConfig
            .panel(edge = Edge.Top, thickness = SURFACE_HEIGHT.dp)
            .copy(namespace = SHELL_NAMESPACE)
    }
}
