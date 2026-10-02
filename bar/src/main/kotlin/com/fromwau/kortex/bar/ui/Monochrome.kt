package com.fromwau.kortex.bar.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.fromwau.kortex.theme.Theme

/**
 * Black on grey, with no hue at all, for a bar that should read as ink rather than as a palette.
 *
 * Three tones carry every role: the ground the bar sits on, a raised tone for anything that sits on the
 * ground, and the content drawn on either. Dark is the same three inverted, so overriding the mode keeps
 * the character rather than falling back to material3's coloured dark scheme.
 */
object Monochrome : Theme {
    override val isDark: Boolean = false

    override val light: ColorScheme = monochrome(
        ground = Color(0xFFDADADA),
        raised = Color.White,
        content = Color.Black,
    )

    override val dark: ColorScheme = monochrome(
        ground = Color(0xFF252525),
        raised = Color.Black,
        content = Color.White,
    )
}

/**
 * Every role from three tones.
 *
 * All 48, the fixed ones included: a shorter argument list compiles against a deprecated constructor that
 * fills the twelve fixed roles with `Color.Unspecified`, which draws nothing wherever they are read.
 */
private fun monochrome(ground: Color, raised: Color, content: Color): ColorScheme = ColorScheme(
    primary = content,
    onPrimary = raised,
    primaryContainer = raised,
    onPrimaryContainer = content,
    inversePrimary = raised,
    secondary = content,
    onSecondary = raised,
    secondaryContainer = raised,
    onSecondaryContainer = content,
    tertiary = content,
    onTertiary = raised,
    tertiaryContainer = raised,
    onTertiaryContainer = content,
    background = ground,
    onBackground = content,
    surface = ground,
    onSurface = content,
    surfaceVariant = raised,
    onSurfaceVariant = content,
    surfaceTint = content,
    inverseSurface = content,
    inverseOnSurface = ground,
    error = content,
    onError = raised,
    errorContainer = raised,
    onErrorContainer = content,
    outline = content,
    outlineVariant = ground,
    scrim = content,
    surfaceBright = raised,
    surfaceDim = ground,
    surfaceContainer = ground,
    surfaceContainerHigh = ground,
    surfaceContainerHighest = ground,
    surfaceContainerLow = raised,
    surfaceContainerLowest = raised,
    primaryFixed = raised,
    primaryFixedDim = ground,
    onPrimaryFixed = content,
    onPrimaryFixedVariant = content,
    secondaryFixed = raised,
    secondaryFixedDim = ground,
    onSecondaryFixed = content,
    onSecondaryFixedVariant = content,
    tertiaryFixed = raised,
    tertiaryFixedDim = ground,
    onTertiaryFixed = content,
    onTertiaryFixedVariant = content,
)
