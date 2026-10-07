package com.fromwau.kortex.bar.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

/** How a menu label's access-key marks come out. */
class AccessKeysTest {
    @Test
    fun `an underscore marking an access key is taken out`() {
        assertEquals("Open Discord", withoutAccessKeys("_Open Discord"))
        assertEquals("Quit", withoutAccessKeys("Q_uit"))
    }

    @Test
    fun `two underscores are a real one`() {
        assertEquals("snake_case", withoutAccessKeys("snake__case"))
    }
}
