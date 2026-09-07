package com.fromwau.kortex.wayland

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.fromwau.kern.result.getOrElse
import com.fromwau.kortex.compose.KortexScene
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
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
            val scene = scene()
            scene.use {
                if (!seat.hasPointer) assertNull(seat.attachPointer(it, scale = 1f))
                if (!seat.hasKeyboard) assertNull(seat.attachKeyboard(it))
                // Whatever this machine announces, the guard has to agree with it.
                assertTrue((seat.attachPointer(it, 1f) != null) == seat.hasPointer)
                assertTrue((seat.attachKeyboard(it) != null) == seat.hasKeyboard)
            }
        }
    }

    /** No surface behind it: this test only exercises which devices the seat announces. */
    private fun scene(): KortexScene {
        val dispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "kortex-seat-test").apply { isDaemon = true }
        }.asCoroutineDispatcher()
        return KortexScene(
            size = IntSize(SIDE, SIDE),
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            frameContext = dispatcher,
            onInvalidate = {},
        )
    }

    private companion object {
        const val SIDE = 64
    }
}
