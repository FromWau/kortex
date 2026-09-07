package com.fromwau.kortex.wayland

import kotlin.test.Test
import kotlin.test.assertEquals

class PointerConversionTest {
    @Test
    fun `wl_fixed is 24 dot 8 fixed point`() {
        assertEquals(0f, PointerInput.fixedToFloat(0))
        assertEquals(1f, PointerInput.fixedToFloat(256))
        assertEquals(0.5f, PointerInput.fixedToFloat(128))
        assertEquals(10.5f, PointerInput.fixedToFloat(2688))
        // Signed: a pointer can leave a surface to the left or above it.
        assertEquals(-1f, PointerInput.fixedToFloat(-256))
        assertEquals(-0.25f, PointerInput.fixedToFloat(-64))
    }

    @Test
    fun `surface-local pixels multiply by the output scale for the scene`() {
        // 512 in wl_fixed is 2 surface-local (logical) px; KortexScene draws into buffer pixels, so on
        // a 2x output the scene must be handed 4. Getting this wrong puts every click at half or double
        // its real position.
        val logical = PointerInput.fixedToFloat(512)
        assertEquals(2f, logical)
        assertEquals(4f, logical * 2f)
    }
}
