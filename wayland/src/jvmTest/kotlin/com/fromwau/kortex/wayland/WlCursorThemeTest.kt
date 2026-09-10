package com.fromwau.kortex.wayland

import com.fromwau.kern.result.getOrElse
import com.fromwau.kortex.compose.KortexCursor
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.fail

/** Drives the theme directly, since on a surface a name that resolves to nothing only shows as a missing cursor. */
class WlCursorThemeTest {
    @Test
    fun `every cursor kortex maps resolves, and resolves again after a rescale`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use { wayland ->
            WlCursorTheme.load(wayland, scale = 1)
                .getOrElse { error -> fail("cursor theme load failed: $error") }
                .use { theme ->
                    KortexCursor.entries.forEach { cursor ->
                        assertNotNull(theme.imageFor(cursor), "$cursor resolved to no image at scale 1")
                    }

                    theme.rescale(2)
                    KortexCursor.entries.forEach { cursor ->
                        assertNotNull(theme.imageFor(cursor), "$cursor resolved to no image at scale 2")
                    }
                }
        }
    }
}
