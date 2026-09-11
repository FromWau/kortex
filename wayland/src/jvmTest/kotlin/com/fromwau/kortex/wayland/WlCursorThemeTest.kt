package com.fromwau.kortex.wayland

import com.fromwau.kern.result.getOrElse
import com.fromwau.kortex.compose.KortexCursor
import kotlin.test.Test
import kotlin.test.assertNotEquals
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

    @Test
    fun `a resize, move or wait shape resolves to its own image, not the left_ptr fallback`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        // Every candidate list but Default's falls back to "left_ptr" only when the theme lacks the shape, so this
        // needs the default XCursor theme to ship all ten: libwayland-cursor's built-in fallback has no move shape.
        val shapesWithTheirOwnImage = listOf(
            KortexCursor.Move,
            KortexCursor.Wait,
            KortexCursor.ResizeNorth,
            KortexCursor.ResizeNorthEast,
            KortexCursor.ResizeEast,
            KortexCursor.ResizeSouthEast,
            KortexCursor.ResizeSouth,
            KortexCursor.ResizeSouthWest,
            KortexCursor.ResizeWest,
            KortexCursor.ResizeNorthWest,
        )

        display.use { wayland ->
            WlCursorTheme.load(wayland, scale = 1)
                .getOrElse { error -> fail("cursor theme load failed: $error") }
                .use { theme ->
                    val fallback = assertNotNull(theme.imageFor(KortexCursor.Default), "left_ptr resolved to no image")

                    shapesWithTheirOwnImage.forEach { cursor ->
                        val image = assertNotNull(theme.imageFor(cursor), "$cursor resolved to no image")
                        // Same address means the theme's wl_cursor_theme_get_cursor returned left_ptr's own
                        // cached wl_cursor, i.e. the fallback fired instead of resolving a dedicated image.
                        assertNotEquals(
                            fallback.buffer.address(),
                            image.buffer.address(),
                            "$cursor fell back to left_ptr's image instead of resolving its own",
                        )
                    }
                }
        }
    }
}
