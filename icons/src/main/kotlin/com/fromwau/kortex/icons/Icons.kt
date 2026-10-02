package com.fromwau.kortex.icons

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Dp
import com.fromwau.kortex.notification.NotificationImage
import com.fromwau.kortex.tray.MenuIcon
import com.fromwau.kortex.tray.TrayIcon

/**
 * Draws [icon], whatever form the application sent it in, and draws nothing where it sent nothing usable.
 *
 * ```kotlin
 * Row {
 *     Text(item.label)
 *     Icon(item.icon, null)
 * }
 * ```
 *
 * Space is taken either way, so a tray does not reflow as icons resolve or fail to.
 *
 * Not material3's `Icon`: that one tints with the content colour, which is right for a glyph and wrong for
 * an application's own artwork, so this draws the art as it is. Pass [tint] to get the other behaviour.
 *
 * @param size the square to draw in, which is also the size asked of the theme, so a 16 dp panel icon gets
 *   the theme's 16-pixel art rather than its 48-pixel art shrunk.
 */
@Composable
public fun Icon(
    icon: TrayIcon,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = DefaultIconSize,
    tint: Color? = null,
) {
    Drawn(rememberIconPainter(icon, size), contentDescription, modifier, size, tint)
}

/** Draws a menu entry's icon, as [Icon] does a tray item's. */
@Composable
public fun Icon(
    icon: MenuIcon,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = DefaultIconSize,
    tint: Color? = null,
) {
    Drawn(rememberIconPainter(icon, size), contentDescription, modifier, size, tint)
}

/** Draws a notification's inline image, as [Icon] does a tray item's icon. */
@Composable
public fun Icon(
    image: NotificationImage,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = DefaultIconSize,
    tint: Color? = null,
) {
    Drawn(rememberIconPainter(image), contentDescription, modifier, size, tint)
}

@Composable
private fun Drawn(
    painter: Painter?,
    contentDescription: String?,
    modifier: Modifier,
    size: Dp,
    tint: Color?,
) {
    if (painter == null) {
        // The space is held rather than collapsed: an icon that resolves a moment later should not move
        // every icon beside it, and one that never resolves should not change the layout either.
        Spacer(modifier.size(size))
        return
    }
    Image(
        painter = painter,
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        colorFilter = tint?.let(ColorFilter::tint),
    )
}
