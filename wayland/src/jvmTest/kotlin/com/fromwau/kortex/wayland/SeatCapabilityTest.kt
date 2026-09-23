package com.fromwau.kortex.wayland

import androidx.compose.ui.unit.IntSize
import com.fromwau.kern.result.getOrElse
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Asking a seat for a device it never announced is a protocol error that drops the connection.
 *
 * This machine announces both, so the guard is asserted conditionally rather than exercised.
 */
class SeatCapabilityTest {
    @Test
    fun `the seat announces its devices before any are created`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use {
            val seat = Seat.bind(it).getOrElse { error -> fail("seat bind failed: $error") }
            println("SEAT pointer=${seat.hasPointer} keyboard=${seat.hasKeyboard}")
            assertTrue(
                seat.hasPointer || seat.hasKeyboard,
                "capabilities never arrived; binding waits for a roundtrip that must deliver them",
            )
        }
    }

    @Test
    fun `a device is not created when the seat does not announce it`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use { wayland ->
            val seat = Seat.bind(wayland).getOrElse { error -> fail("seat bind failed: $error") }
            // No surface behind the scene: this test only exercises which devices the seat announces.
            onScene(IntSize(SIDE, SIDE)) { scene, _ ->
                if (!seat.hasPointer) assertNull(seat.attachPointer(scene, scale = 1f))
                if (!seat.hasKeyboard) assertNull(seat.attachKeyboard(scene))
                // Whatever this machine announces, the guard has to agree with it.
                assertTrue((seat.attachPointer(scene, 1f) != null) == seat.hasPointer)
                assertTrue((seat.attachKeyboard(scene) != null) == seat.hasKeyboard)
            }
        }
    }

    private companion object {
        const val SIDE = 64
    }
}
