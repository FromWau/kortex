package com.fromwau.kortex.wayland

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.getOrElse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The wait every surface role's `waitForConfigure` runs on, driven against conditions that never come true.
 *
 * This is the only cover for the deadline itself. A layer surface used to bound this wait by a count of
 * dispatches, but `wl_display_dispatch` returns only once an event arrives, so a compositor that mapped the
 * surface and then went quiet left the counter where it was and the thread inside the call. That thread
 * pumps the connection and draws every surface, so the whole application stopped, past any `exitApplication`.
 *
 * No surface is made here: the connection is real so that dispatching is, and nothing is ever asked of it.
 */
class AwaitConfigureTest {
    @Test
    fun `a configure that never comes gives up on a deadline rather than waiting forever`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use { wayland ->
            val startedAt = System.nanoTime()
            val result = awaitConfigure(wayland, configured = { false }, closed = { false })
            val waitedMillis = (System.nanoTime() - startedAt) / NANOS_PER_MILLI

            assertEquals(
                Err(KortexError.SurfaceNotConfigured), result,
                "a wait that timed out must say the surface was never configured",
            )
            // The point of the test: it returned at all. The bound is generous because a loaded machine
            // dispatches slowly, and the failure this guards against is unbounded, not merely slow.
            assertTrue(
                waitedMillis < GAVE_UP_WITHIN_MILLIS,
                "the wait took ${waitedMillis}ms, which is past any deadline it should hold to",
            )
            assertEquals(Ok(Unit), wayland.requireAlive(), "the wait left the connection broken")
        }
    }

    @Test
    fun `a surface the compositor closes before configuring stops waiting at once`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use { wayland ->
            val startedAt = System.nanoTime()
            val result = awaitConfigure(wayland, configured = { false }, closed = { true })
            val waitedMillis = (System.nanoTime() - startedAt) / NANOS_PER_MILLI

            assertEquals(
                Err(KortexError.SurfaceNotConfigured), result,
                "a surface closed before its configure must say it was never configured",
            )
            // Nowhere near the deadline: a closed surface is answered by the loop's own condition, not by
            // running out of time, so this fails if the closed flag ever stops being checked.
            assertTrue(
                waitedMillis < CLOSED_WITHIN_MILLIS,
                "a closed surface waited ${waitedMillis}ms instead of giving up at once",
            )
        }
    }

    @Test
    fun `a configure already in hand returns without dispatching`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use { wayland ->
            val startedAt = System.nanoTime()
            val result = awaitConfigure(wayland, configured = { true }, closed = { false })
            val waitedMillis = (System.nanoTime() - startedAt) / NANOS_PER_MILLI

            assertEquals(Ok(Unit), result, "a configure already in hand must be taken as one")
            assertTrue(
                waitedMillis < CLOSED_WITHIN_MILLIS,
                "a surface already configured waited ${waitedMillis}ms before saying so",
            )
        }
    }

    private companion object {
        // The wait's own budget is four seconds; this leaves room for a slow dispatch and still fails an
        // unbounded one, which is what the defect was.
        const val GAVE_UP_WITHIN_MILLIS = 8_000L

        // A condition the loop reads itself, so it is answered on the first check rather than on a timer.
        const val CLOSED_WITHIN_MILLIS = 2_000L

        const val NANOS_PER_MILLI = 1_000_000L
    }
}
