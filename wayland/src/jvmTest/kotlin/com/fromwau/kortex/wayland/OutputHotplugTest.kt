package com.fromwau.kortex.wayland

import com.fromwau.kern.result.getOrElse
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/** Hotplugs an output to prove `wl_registry.global`/`global_remove` reach [WaylandDisplay]'s globals. */
class OutputHotplugTest {
    @Test
    fun `a hotplugged output updates globals and fires add-remove notifications`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use { wayland ->
            val added = mutableListOf<String>()
            val removed = mutableListOf<String>()
            wayland.onGlobalAdded = { global -> added += global.interfaceName }
            wayland.onGlobalRemoved = { global -> removed += global.interfaceName }

            val initialCount = wayland.outputCount()
            var pending: String? = null
            try {
                val outputName = Hyprctl.createHeadlessOutput()
                pending = outputName

                val grew = wayland.pumpUntil(PUMP_TIMEOUT_MILLIS) { wayland.outputCount() > initialCount }
                assertTrue(grew, "wl_output count never grew after hyprctl output create headless")
                assertTrue("wl_output" in added, "no add notification fired for wl_output")

                Hyprctl.removeHeadlessOutput(outputName)
                pending = null

                val shrank = wayland.pumpUntil(PUMP_TIMEOUT_MILLIS) { wayland.outputCount() == initialCount }
                assertTrue(shrank, "wl_output count never dropped back after hyprctl output remove")
                assertTrue("wl_output" in removed, "no remove notification fired for wl_output")
            } finally {
                // Guarantees the virtual output never survives a failed assertion above.
                pending?.let(Hyprctl::removeHeadlessOutput)
            }
        }
    }

    private fun WaylandDisplay.outputCount(): Int = globals.count { it.interfaceName == "wl_output" }

    /** Pumps real dispatch, not an artificial roundtrip, so this observes events as they actually arrive. */
    private fun WaylandDisplay.pumpUntil(timeoutMillis: Long, predicate: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLI
        while (System.nanoTime() < deadline) {
            if (predicate()) return true
            dispatch(PUMP_INTERVAL_MILLIS)
        }
        return predicate()
    }

    private companion object {
        const val PUMP_TIMEOUT_MILLIS = 4000L
        const val PUMP_INTERVAL_MILLIS = 100L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
