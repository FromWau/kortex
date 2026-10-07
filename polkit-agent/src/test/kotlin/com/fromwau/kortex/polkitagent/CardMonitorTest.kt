package com.fromwau.kortex.polkitagent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CardMonitorTest {
    private val monitors = listOf("HDMI-A-2", "DP-1")

    @Test
    fun `the card opens on the focused monitor`() {
        assertEquals("DP-1", cardMonitor(monitors, "DP-1") { it })
    }

    @Test
    fun `a focused monitor that is gone, or not known, falls back to the first`() {
        assertEquals("HDMI-A-2", cardMonitor(monitors, "DP-9") { it })
        assertEquals("HDMI-A-2", cardMonitor(monitors, null) { it })
    }

    @Test
    fun `with no monitor there is nowhere to open it`() {
        assertNull(cardMonitor(emptyList<String>(), "DP-1") { it })
    }
}
