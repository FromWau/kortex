package com.fromwau.kortex.icons

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fromwau.kortex.notification.NotificationImage
import com.fromwau.kortex.tray.MenuIcon
import com.fromwau.kortex.tray.TrayIcon
import com.fromwau.kortex.tray.TrayImage
import org.jetbrains.compose.resources.decodeToImageBitmap
import org.jetbrains.compose.resources.decodeToSvgPainter
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorInfo
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readBytes

/**
 * Where icons are looked up in this composition, read once from the desktop unless something provides it.
 *
 * Static, because changing it should rebuild what reads it rather than recompose around it, and because a
 * theme lookup walks directories and is not something to redo on a whim.
 */
public val LocalIconTheme: ProvidableCompositionLocal<IconTheme> =
    staticCompositionLocalOf { IconTheme.ofDesktop() }

/** What an icon is drawn at when nothing says otherwise, which is a panel's usual size. */
public val DefaultIconSize: Dp = 16.dp

/**
 * [icon] as something Compose can draw, or null where the item offered nothing drawable.
 *
 * Tries the name first and the pixels second, which is the order the specification asks for: a name picks
 * up the user's own theme, and an application that sends both usually means the name. Falls back to the
 * pixels when the name resolves to nothing, which is what happens for an application whose icon is not
 * installed anywhere.
 *
 * Reading and decoding happen once per icon and size, not per frame.
 */
@Composable
public fun rememberIconPainter(
    icon: TrayIcon,
    size: Dp = DefaultIconSize,
    theme: IconTheme = LocalIconTheme.current,
): Painter? {
    val density = LocalDensity.current
    val pixels = with(density) { size.roundToPx() }
    return remember(icon, pixels, theme) {
        icon.name
            ?.let { name -> theme.find(name, pixels, icon.themePath) }
            ?.let { file -> file.painter(density) }
            ?: icon.pixmaps.bestFor(pixels)?.painter()
    }
}

/**
 * A menu entry's icon, which arrives as a name or as an encoded file rather than as pixels.
 *
 * Unlike a tray icon, `icon-data` here is a PNG or similar as the application wrote it, so there is
 * nothing to unpack: it decodes the same way a file off the disk does.
 */
@Composable
public fun rememberIconPainter(
    icon: MenuIcon,
    size: Dp = DefaultIconSize,
    theme: IconTheme = LocalIconTheme.current,
): Painter? {
    val density = LocalDensity.current
    val pixels = with(density) { size.roundToPx() }
    return remember(icon, pixels, theme) {
        icon.name
            ?.let { name -> theme.find(name, pixels) }
            ?.let { file -> file.painter(density) }
            ?: icon.data?.let { encoded -> encoded.decoded(density, isSvg = false) }
    }
}

/**
 * A notification's inline image, which is raw pixels in its own layout.
 *
 * Nothing to look up: an application that sends `image-data` has sent the picture itself.
 */
@Composable
public fun rememberIconPainter(image: NotificationImage): Painter? =
    remember(image) { image.bitmap()?.let(::BitmapPainter) }

/** The pixmap nearest [pixels] across, since an application may send several sizes and usually sends one. */
internal fun List<TrayImage>.bestFor(pixels: Int): TrayImage? =
    filter { it.width > 0 && it.height > 0 }.minByOrNull { kotlin.math.abs(it.width - pixels) }

private fun Path.painter(density: Density): Painter? =
    runCatching { readBytes().decoded(density, isSvg = name.endsWith(".svg")) }.getOrNull()

private fun ByteArray.decoded(density: Density, isSvg: Boolean): Painter? = runCatching {
    if (isSvg) decodeToSvgPainter(density) else BitmapPainter(decodeToImageBitmap())
}.getOrNull()

/**
 * A tray pixmap as a bitmap: `ARGB` in network byte order, which is the bytes A, R, G, B.
 *
 * Reversing each group of four turns that into skia's `BGRA_8888`, since neither of skia's 32-bit orders
 * is `ARGB` and the bytes have to move either way. Not premultiplied, which is what the wire carries:
 * telling skia otherwise darkens every semi-transparent pixel in a way that looks like a theme choice.
 */
private fun TrayImage.painter(): Painter? = bitmapOrNull()?.let(::BitmapPainter)

internal fun TrayImage.bitmapOrNull(): ImageBitmap? {
    if (width <= 0 || height <= 0 || argb.size != width * height * BYTES_PER_PIXEL) return null

    val bgra = ByteArray(argb.size)
    for (pixel in 0 until width * height) {
        val at = pixel * BYTES_PER_PIXEL
        bgra[at] = argb[at + 3]
        bgra[at + 1] = argb[at + 2]
        bgra[at + 2] = argb[at + 1]
        bgra[at + 3] = argb[at]
    }
    return raster(width, height, ColorType.BGRA_8888, bgra)
}

/**
 * A notification image as a bitmap: `RGB` or `RGBA` in native order, [NotificationImage.rowStride] to the
 * row, which need not be the width because rows are padded.
 *
 * Repacked row by row rather than handed over with its stride, because three channels have to be widened
 * to four anyway and one pass does both.
 */
internal fun NotificationImage.bitmap(): ImageBitmap? {
    if (width <= 0 || height <= 0) return null
    if (channels != OPAQUE_CHANNELS && channels != ALPHA_CHANNELS) return null
    if (bitsPerSample != BITS_PER_SAMPLE) return null
    // A stride narrower than one row of pixels would have rows overlapping each other, which reads inside
    // the array and draws a picture made of the wrong bytes rather than failing.
    if (rowStride < width * channels) return null
    if (pixels.size < rowStride * height) return null

    val rgba = ByteArray(width * height * BYTES_PER_PIXEL)
    for (row in 0 until height) {
        for (column in 0 until width) {
            val from = row * rowStride + column * channels
            val to = (row * width + column) * BYTES_PER_PIXEL
            rgba[to] = pixels[from]
            rgba[to + 1] = pixels[from + 1]
            rgba[to + 2] = pixels[from + 2]
            rgba[to + 3] = if (channels == ALPHA_CHANNELS && hasAlpha) pixels[from + 3] else OPAQUE
        }
    }
    return raster(width, height, ColorType.RGBA_8888, rgba)
}

private fun raster(width: Int, height: Int, order: ColorType, bytes: ByteArray): ImageBitmap {
    val info = ImageInfo(ColorInfo(order, ColorAlphaType.UNPREMUL, ColorSpace.sRGB), width, height)
    val bitmap = Bitmap()
    bitmap.allocPixels(info)
    bitmap.installPixels(info, bytes, width * BYTES_PER_PIXEL)
    bitmap.setImmutable()
    return bitmap.asComposeImageBitmap()
}

private const val BYTES_PER_PIXEL = 4
private const val BITS_PER_SAMPLE = 8
private const val OPAQUE_CHANNELS = 3
private const val ALPHA_CHANNELS = 4
private const val OPAQUE = (-1).toByte()
