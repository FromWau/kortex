package com.fromwau.kortex.hyprland

import kotlin.test.Test
import kotlin.test.assertEquals

class EventLineTest {
    @Test
    fun theNameIsReadWhateverTheDataHoldsOrLacks() {
        assertEquals("renameworkspace", eventName("renameworkspace>>9,a,b"))
        assertEquals("activewindowv2", eventName("activewindowv2>>"))
        assertEquals("configreloaded", eventName("configreloaded>>"))
    }
}
