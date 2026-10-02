package com.fromwau.kortex.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import com.fromwau.kortex.notification.NotificationImage
import com.fromwau.kortex.tray.TrayImage
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two raw-pixel layouts, read back pixel by pixel, because their failure is not an exception.
 *
 * Reading one provider's bytes with the other's unpacker yields a real picture in rotated colours: an
 * opaque blue comes out an opaque red, at full alpha, with nothing to catch. Only asking what colour came
 * out tells them apart, which is why this module owns the unpacking instead of every host doing it.
 */
class PixelsTest {
    @Test
    fun `a tray pixmap is ARGB in network order`() {
        // A, R, G, B per pixel: opaque red, then opaque blue.
        val image = TrayImage(
            width = 2,
            height = 1,
            argb = byteArrayOf(
                0xFF.toByte(), 0xFF.toByte(), 0x00, 0x00,
                0xFF.toByte(), 0x00, 0x00, 0xFF.toByte(),
            ),
        )

        val pixels = assertNotNull(image.bitmapOrNull()).toPixelMap()

        assertColour(Color.Red, pixels[0, 0], "the first pixel")
        assertColour(Color.Blue, pixels[1, 0], "the second pixel")
    }

    @Test
    fun `a tray pixmap whose bytes do not match its size is refused`() {
        assertNull(TrayImage(width = 2, height = 2, argb = ByteArray(4)).bitmapOrNull())
        assertNull(TrayImage(width = 0, height = 1, argb = ByteArray(0)).bitmapOrNull())
    }

    @Test
    fun `a notification image keeps the alpha it says it has, unpremultiplied`() {
        val image = notification(
            width = 1, height = 1, rowStride = 4, channels = 4, hasAlpha = true,
            // R, G, B, A: green at half alpha.
            pixels = byteArrayOf(0x00, 0xFF.toByte(), 0x00, 0x80.toByte()),
        )

        val pixel = assertNotNull(image.bitmap()).toPixelMap()[0, 0]

        assertTrue(abs(pixel.alpha - HALF) < TOLERANCE, "alpha was ${pixel.alpha}, not about $HALF")
        assertTrue(pixel.green > 1f - TOLERANCE, "green was ${pixel.green}, so the bytes were premultiplied")
    }

    @Test
    fun `a fourth channel that is not alpha is read as opaque`() {
        val image = notification(
            width = 1, height = 1, rowStride = 4, channels = 4, hasAlpha = false,
            pixels = byteArrayOf(0x00, 0xFF.toByte(), 0x00, 0x00),
        )

        assertColour(Color.Green, assertNotNull(image.bitmap()).toPixelMap()[0, 0], "a padding byte of 0")
    }

    /** The field that exists because rows are padded, and the one a hand-rolled unpacker drops. */
    @Test
    fun `a row stride wider than the row is skipped, not drawn`() {
        val image = notification(
            width = 1, height = 2, rowStride = 6, channels = 3, hasAlpha = false,
            pixels = byteArrayOf(
                0xFF.toByte(), 0x00, 0x00, 0x11, 0x22, 0x33,
                0xFF.toByte(), 0x00, 0x00, 0x44, 0x55, 0x66,
            ),
        )

        val pixels = assertNotNull(image.bitmap()).toPixelMap()

        assertColour(Color.Red, pixels[0, 0], "the first row")
        assertColour(Color.Red, pixels[0, 1], "the second row, which the padding would have shifted")
    }

    /**
     * A stride narrower than a row reads inside the array and draws the wrong bytes.
     *
     * The dangerous one of the malformed shapes: every other bad layout runs off the end and is caught by
     * the length check, while this one overlaps the rows and renders a picture made of neighbours.
     */
    @Test
    fun `a row stride narrower than one row is refused, not drawn from overlapping rows`() {
        val narrow = notification(
            width = 2,
            height = 2,
            rowStride = 3,
            channels = 4,
            hasAlpha = true,
            pixels = ByteArray(16),
        )

        assertNull(narrow.bitmap())
    }

    /** Every sample the wire carries is a byte, and a bitmap built from anything else is not this image. */
    @Test
    fun `a sample that is not eight bits is refused`() {
        val wide = notification(
            width = 2,
            height = 2,
            rowStride = 8,
            channels = 4,
            hasAlpha = true,
            pixels = ByteArray(16),
            bitsPerSample = 16,
        )

        assertNull(wide.bitmap())
    }

    @Test
    fun `a channel count the wire does not have is refused`() {
        val image = notification(
            width = 1, height = 1, rowStride = 2, channels = 2, hasAlpha = false, pixels = ByteArray(2),
        )

        assertNull(image.bitmap())
    }

    @Test
    fun `the pixmap nearest the asked size is the one chosen`() {
        val small = TrayImage(16, 16, ByteArray(16 * 16 * 4))
        val large = TrayImage(48, 48, ByteArray(48 * 48 * 4))

        assertEquals(small, listOf(large, small).bestFor(22))
        assertEquals(large, listOf(large, small).bestFor(40))
    }

    private fun notification(
        width: Int,
        height: Int,
        rowStride: Int,
        channels: Int,
        hasAlpha: Boolean,
        pixels: ByteArray,
        bitsPerSample: Int = 8,
    ) = NotificationImage(
        width = width,
        height = height,
        rowStride = rowStride,
        hasAlpha = hasAlpha,
        bitsPerSample = bitsPerSample,
        channels = channels,
        pixels = pixels,
    )

    private fun assertColour(expected: Color, actual: Color, what: String) {
        val close = abs(expected.red - actual.red) < TOLERANCE &&
            abs(expected.green - actual.green) < TOLERANCE &&
            abs(expected.blue - actual.blue) < TOLERANCE &&
            abs(expected.alpha - actual.alpha) < TOLERANCE
        assertTrue(close, "$what is $actual, not $expected")
    }

    private companion object {
        const val TOLERANCE = 0.01f
        const val HALF = 0.5f
    }
}
