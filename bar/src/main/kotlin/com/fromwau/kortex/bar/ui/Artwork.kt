package com.fromwau.kortex.bar.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Dp

/**
 * One provider's artwork, with a letter where nothing could be drawn.
 *
 * `:icons` resolves and decodes, and answers null for an icon no theme carries. The fallback is this
 * bar's own choice rather than that module's: a letter says which application is sitting there, where
 * blank space says only that something is wrong. Holding the square either way is what stops the tray
 * reflowing as icons arrive.
 */
@Composable
internal fun Artwork(painter: Painter?, label: String, side: Dp) {
    when (painter) {
        null -> Monogram(label.firstOrNull()?.uppercaseChar() ?: '?', side)
        else -> Image(painter = painter, contentDescription = label, modifier = Modifier.size(side))
    }
}

/** What an icon nobody could find looks like: the first letter of whatever the item calls itself. */
@Composable
private fun Monogram(initial: Char, side: Dp) {
    Box(modifier = Modifier.size(side), contentAlignment = Alignment.Center) {
        Text(
            text = initial.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
