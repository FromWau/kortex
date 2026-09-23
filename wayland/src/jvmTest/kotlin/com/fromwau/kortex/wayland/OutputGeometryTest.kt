package com.fromwau.kortex.wayland

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.unit.IntSize
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
 * Verifies `OutputListener` reads `wl_output`'s geometry, current mode, name and scale and its
 * `zxdg_output_v1`'s logical position and size, and publishes them only once `done` arrives.
 *
 * The first leg cross-checks every real monitor against `hyprctl monitors -j`. Against an unrotated
 * monitor at the origin with an integer scale, its `x`, `y`, `transform` and `scale` assertions expect
 * exactly [OutputListener]'s own defaults, so on this machine only `width`, `height` and `name` can
 * tell a wired field from an unwired one. Its logical size cannot tell a protocol size from one derived
 * from the mode either, because at scale 1 the two are the same number; what it does pin is that the
 * `zxdg_output_v1` listener's five slots are in the order libwayland dispatches them in. It cannot reach
 * the current-mode-flag filter at all, because Hyprland (`src/protocols/core/Output.cpp` in v0.56.2,
 * installed here) sends one `mode` event and always flags it current. The later legs drive both
 * listeners with fabricated sequences in which no value can be mistaken for a default or for a
 * derivation, and are what pin every field's wiring.
 */
class OutputGeometryTest {
    @Test
    fun `kortex publishes the name, position, mode, scale and logical size hyprctl reports for every monitor`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use { wayland ->
            val manager = wayland.global(XDG_OUTPUT_MANAGER)
                ?.let { wayland.bind(it, XdgOutputProtocol.xdgOutputManagerInterface, WlVersion.XDG_OUTPUT) }
                ?: fail("the compositor advertises no $XDG_OUTPUT_MANAGER, so no output has a logical size")
            val bound = wayland.globals.filter { it.interfaceName == WL_OUTPUT }.map { global ->
                val proxy = wayland.bind(global, LibWayland.outputInterface, WlVersion.OUTPUT)
                val listener = OutputListener()
                listener.install(proxy)
                // Before the roundtrip below, which is what waits for the done these details arrive under.
                XdgOutput.take(manager, proxy, listener.xdgOutput)
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
                // hyprctl reports a transform by the number wl_output.transform gives it.
                assertEquals(
                    expected.transform,
                    TRANSFORM_WIRE_VALUES[geometry.transform],
                    "${geometry.name}: transform mismatch, kortex published ${geometry.transform}",
                )
                // wl_output.scale is an integer and Hyprland ceil-rounds a fractional monitor scale.
                assertEquals(ceil(expected.scale).toInt(), geometry.scale, "${geometry.name}: scale mismatch")
                // hyprctl publishes no logical size, so the expectation has to divide the mode itself; a
                // logical size is already turned, where the mode it divides is not.
                val expectedLogical = if (geometry.transform.isQuarterTurn) {
                    IntSize(expected.logicalHeight, expected.logicalWidth)
                } else {
                    IntSize(expected.logicalWidth, expected.logicalHeight)
                }
                assertEquals(
                    expectedLogical, IntSize(geometry.logicalWidth, geometry.logicalHeight),
                    "${geometry.name}: logical size mismatch",
                )
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
            NONE,
            NONE,
            X,
            Y,
            0,
            0,
            0,
            strings.allocateFrom("make"),
            strings.allocateFrom("model"),
            TRANSFORM_WIRE_VALUES.getValue(TRANSFORM),
        )
        listener.onMode(NONE, NONE, flags = NOT_CURRENT, width = 640, height = 480, refresh = 0)
        listener.onMode(NONE, NONE, flags = CURRENT, width = MODE.width, height = MODE.height, refresh = 60_000)
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
        assertEquals(MODE.width, geometry.width, "took a mode other than the one flagged current")
        assertEquals(MODE.height, geometry.height, "took a mode other than the one flagged current")
    }

    /**
     * Fabricates the fractional scale this desktop cannot show: a 1920x1080 mode a compositor scales by 1.5,
     * which `wl_output.scale` can only ceil-round to 2 and `zxdg_output_v1` reports exactly as 1280x720.
     * Dividing the mode by that scale gives 960x540 instead, so only a logical size taken from the protocol
     * passes here. Nothing sends `zxdg_output_v1.done`, which version 3 does not require: `wl_output.done`
     * alone has to publish, or a compositor that sends only that one would publish nothing at all.
     */
    @Test
    fun `the logical position and size are the ones zxdg_output_v1 sent, published on wl_output's own done`() {
        val listener = OutputListener()

        listener.onGeometry(NONE, NONE, X, Y, 0, 0, 0, NONE, NONE, NO_TRANSFORM)
        listener.onMode(NONE, NONE, flags = CURRENT, width = MODE.width, height = MODE.height, refresh = 60_000)
        listener.onScale(NONE, NONE, factor = SCALE)
        listener.xdgOutput.onLogicalPosition(NONE, NONE, LOGICAL_X, LOGICAL_Y)
        listener.xdgOutput.onLogicalSize(NONE, NONE, FRACTIONAL_LOGICAL.width, FRACTIONAL_LOGICAL.height)
        listener.onDone(NONE, NONE)

        val geometry = assertNotNull(listener.geometry, "wl_output's own done must publish the geometry")
        assertEquals(
            FRACTIONAL_LOGICAL, IntSize(geometry.logicalWidth, geometry.logicalHeight),
            "the logical size was derived from the mode and the integer scale, not taken from zxdg_output_v1",
        )
        assertEquals(LOGICAL_X, geometry.x, "the logical position was not taken from zxdg_output_v1")
        assertEquals(LOGICAL_Y, geometry.y, "the logical position was not taken from zxdg_output_v1")
        assertEquals(MODE.width, geometry.width, "the logical size overwrote the mode")
        assertEquals(MODE.height, geometry.height, "the logical size overwrote the mode")
    }

    /**
     * The one case the mode over the integer scale is still all there is: a compositor that describes none of
     * its outputs through `zxdg_output_v1`, where no logical size ever arrives.
     */
    @Test
    fun `an output no zxdg_output_v1 describes measures its mode over the integer scale`() {
        val listener = OutputListener()

        listener.onGeometry(NONE, NONE, X, Y, 0, 0, 0, NONE, NONE, NO_TRANSFORM)
        listener.onMode(NONE, NONE, flags = CURRENT, width = MODE.width, height = MODE.height, refresh = 60_000)
        listener.onScale(NONE, NONE, factor = SCALE)
        listener.onDone(NONE, NONE)

        val geometry = assertNotNull(listener.geometry, "done must publish the accumulated geometry")
        assertEquals(
            DERIVED_LOGICAL, IntSize(geometry.logicalWidth, geometry.logicalHeight),
            "an undescribed output did not fall back to its mode over its scale",
        )
        assertEquals(X, geometry.x, "an undescribed output did not fall back to wl_output.geometry's position")
        assertEquals(Y, geometry.y, "an undescribed output did not fall back to wl_output.geometry's position")
    }

    @Test
    fun `each of wl_output's eight transforms publishes as its own OutputTransform, and any other as Unrecognized`() {
        val expected = TRANSFORM_WIRE_VALUES.entries.associate { (transform, wireValue) -> wireValue to transform } +
            (UNLISTED_TRANSFORM_WIRE_VALUE to OutputTransform.Unrecognized(UNLISTED_TRANSFORM_WIRE_VALUE))

        assertEquals(
            expected,
            expected.keys.associateWith(::publishedTransform),
            "a wl_output.transform number did not publish as its own OutputTransform",
        )
    }

    @Test
    fun `isQuarterTurn is true for the four transforms that swap a monitor's width and height, false for the rest`() {
        val expected = mapOf(
            OutputTransform.Normal to false,
            OutputTransform.Rotated90 to true,
            OutputTransform.Rotated180 to false,
            OutputTransform.Rotated270 to true,
            OutputTransform.Flipped to false,
            OutputTransform.Flipped90 to true,
            OutputTransform.Flipped180 to false,
            OutputTransform.Flipped270 to true,
            OutputTransform.Unrecognized(UNLISTED_TRANSFORM_WIRE_VALUE) to false,
        )

        assertEquals(
            expected,
            expected.keys.associateWith { it.isQuarterTurn },
            "a transform did not report whether it swaps width and height",
        )
    }

    /**
     * A re-sent scale or mode replaces the published geometry whole, at any time, so content reading it
     * through a surface's own [Monitor.geometry] has to recompose; that needs both the read and the write
     * to reach the snapshot system rather than a plain field.
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

    /** The transform a listener publishes once a `geometry` carrying [wireValue], then `done`, have arrived. */
    private fun publishedTransform(wireValue: Int): OutputTransform? {
        val listener = OutputListener()
        try {
            listener.onGeometry(NONE, NONE, X, Y, 0, 0, 0, NONE, NONE, wireValue)
            listener.onDone(NONE, NONE)
            return listener.geometry?.transform
        } finally {
            listener.close()
        }
    }

    private companion object {
        const val WL_OUTPUT = "wl_output"
        const val CURRENT = 0x1
        const val NOT_CURRENT = 0
        const val X = 7
        const val Y = 13
        const val LOGICAL_X = 101
        const val LOGICAL_Y = 202

        /** Not the default, and what `wl_output.scale` carries for a scale of 1.5, which it can only round up. */
        const val SCALE = 2
        const val RESCALED = 3
        val MODE = IntSize(1920, 1080)

        /** 1920x1080 at a true scale of 1.5, which is what `zxdg_output_v1` reports. */
        val FRACTIONAL_LOGICAL = IntSize(1280, 720)

        /** The same mode over [SCALE], which is a quarter short of [FRACTIONAL_LOGICAL] on both axes. */
        val DERIVED_LOGICAL = IntSize(960, 540)
        val NO_TRANSFORM = TRANSFORM_WIRE_VALUES.getValue(OutputTransform.Normal)
        val TRANSFORM = OutputTransform.Rotated270
        const val NAME = "SYNTH-1"
        const val DESCRIPTION = "Synthetic output for the current-mode-flag test"
        val NONE: MemorySegment = MemorySegment.NULL
    }
}
