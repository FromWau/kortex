package com.fromwau.kortex.wayland

import com.fromwau.kern.result.getOrElse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A real click, from a virtual pointer through the compositor, reaches a click handler that throws: it
 * ends only its own surface, and the JVM it happens in lives. [CrashedPointerProbe] runs in a child JVM,
 * the way [ContentFailureTest] runs [CrashedSurfaceProbe], since a throw escaping a real `wl_pointer`
 * callback would otherwise end the JVM running the tests. [VirtualPointerClickTest] covers the same
 * click path into content that does not throw.
 */
class VirtualPointerCrashTest {
    @Test
    fun `a click delivered through the compositor to a throwing handler ends only its own surface`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use {
            val manager = VirtualPointerManager.bind(it)
                .getOrElse { error -> fail("virtual pointer manager bind failed: $error") }
            val monitor = assertNotNull(Hyprctl.monitors().firstOrNull(), "hyprctl monitors reported no usable monitor")

            val probe = runProbe(POINTER_PROBE_MAIN_CLASS) {
                manager.createVirtualPointer().use { pointer ->
                    // Off the probe's corner first: the compositor re-evaluates pointer focus on motion,
                    // so a cursor already parked where the probe lands would never enter its surface.
                    pointer.moveTo(monitor, monitor.logicalWidth / 2, monitor.logicalHeight - 1)
                    it.roundtrip()

                    val geometry = assertNotNull(
                        Screen.awaitGeometry(POINTER_PROBE_NAMESPACE),
                        "hyprctl layers never reported $POINTER_PROBE_NAMESPACE",
                    )
                    // hyprctl lists a layer before it has a buffer, so a click here could still land on
                    // the desktop beneath; wait for the probe's own colour the way a screenshot test does.
                    assertEquals(
                        POINTER_PROBE_PIXEL,
                        Screen.pixelReaching(geometry, POINTER_PROBE_PIXEL),
                        "the probe never drew its own colour",
                    )
                    pointer.clickAt(
                        monitor, geometry.x + geometry.logicalWidth / 2, geometry.y + geometry.logicalHeight / 2,
                    )
                    it.roundtrip()

                    // Off it again: a cursor left on a target would deny the next test's own move here an
                    // enter, the same hazard the first move above avoids.
                    pointer.moveTo(monitor, monitor.logicalWidth / 2, monitor.logicalHeight - 1)
                    it.roundtrip()
                }
            }
            val raw = probe.output.joinToString("\n")

            val protocolError = it.protocolError()
            if (protocolError != null) {
                fail("wayland protocol error while driving the virtual pointer: $protocolError")
            }
            assertEquals(0, probe.exitCode, "the probe did not exit cleanly; output:\n$raw")
            val crashLine = "$PROBE_MARKER crashed=$POINTER_PROBE_NAMESPACE failure=PointerInput " +
                "cause=$POINTER_PROBE_FAILURE"
            assertTrue(
                crashLine in probe.output,
                "the click did not end the surface in the click handler's crash; output:\n$raw",
            )
            val hookLine = "$PROBE_MARKER hook crashed=$POINTER_PROBE_NAMESPACE cause=$POINTER_PROBE_FAILURE"
            assertEquals(
                1,
                probe.output.count { it == hookLine },
                "the click handler's crash must reach onClose exactly once; output:\n$raw",
            )
        }
    }

    private companion object {
        const val POINTER_PROBE_MAIN_CLASS = "com.fromwau.kortex.wayland.CrashedPointerProbe"
    }
}
