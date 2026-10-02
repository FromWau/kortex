package com.fromwau.kortex.bar.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.fromwau.kortex.theme.Theme

/**
 * material3's own schemes with the surfaces taken to the extreme, which is the one thing an OLED panel asks.
 *
 * Only the surfaces change rather than a whole palette, so the accents stay material3's. Light is the same
 * move toward white, so overriding the mode keeps the character rather than the panel-saving.
 */
object Amoled : Theme {
    override val isDark: Boolean = true

    override val light: ColorScheme = lightColorScheme(
        background = Color.White,
        surface = Color.White,
        surfaceContainer = Color.White,
        surfaceContainerLow = Color.White,
        surfaceContainerLowest = Color.White,
    )

    override val dark: ColorScheme = darkColorScheme(
        background = Color.Black,
        surface = Color.Black,
        surfaceContainer = Color.Black,
        surfaceContainerLow = Color.Black,
        surfaceContainerLowest = Color.Black,
    )
}
