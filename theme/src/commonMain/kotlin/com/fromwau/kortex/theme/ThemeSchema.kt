package com.fromwau.kortex.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


@Immutable
@Serializable
internal data class FileColorTheme(
    val mode: ThemeMode,
    val light: FileColorScheme,
    val dark: FileColorScheme,
    val wallpaper: String? = null, // optional wallpaper path
) {
    val isDark: Boolean
        get() = mode == ThemeMode.DARK

    val colorScheme: FileColorScheme
        get() = when (mode) {
            ThemeMode.LIGHT -> light
            ThemeMode.DARK -> dark
        }

    /** Which of the two schemes a file says to draw with, under the names a file writes. */
    enum class ThemeMode {
        @SerialName("light")
        LIGHT,

        @SerialName("dark")
        DARK,
    }

    @Immutable
    @Serializable
    data class FileColorScheme(
        @Serializable(with = ColorSerializer::class) val primary: Color,
        @Serializable(with = ColorSerializer::class) val onPrimary: Color,
        @Serializable(with = ColorSerializer::class) val primaryContainer: Color,
        @Serializable(with = ColorSerializer::class) val onPrimaryContainer: Color,
        @Serializable(with = ColorSerializer::class) val inversePrimary: Color,
        @Serializable(with = ColorSerializer::class) val secondary: Color,
        @Serializable(with = ColorSerializer::class) val onSecondary: Color,
        @Serializable(with = ColorSerializer::class) val secondaryContainer: Color,
        @Serializable(with = ColorSerializer::class) val onSecondaryContainer: Color,
        @Serializable(with = ColorSerializer::class) val tertiary: Color,
        @Serializable(with = ColorSerializer::class) val onTertiary: Color,
        @Serializable(with = ColorSerializer::class) val tertiaryContainer: Color,
        @Serializable(with = ColorSerializer::class) val onTertiaryContainer: Color,
        @Serializable(with = ColorSerializer::class) val background: Color,
        @Serializable(with = ColorSerializer::class) val onBackground: Color,
        @Serializable(with = ColorSerializer::class) val surface: Color,
        @Serializable(with = ColorSerializer::class) val onSurface: Color,
        @Serializable(with = ColorSerializer::class) val surfaceVariant: Color,
        @Serializable(with = ColorSerializer::class) val onSurfaceVariant: Color,
        @Serializable(with = ColorSerializer::class) val surfaceTint: Color,
        @Serializable(with = ColorSerializer::class) val inverseSurface: Color,
        @Serializable(with = ColorSerializer::class) val inverseOnSurface: Color,
        @Serializable(with = ColorSerializer::class) val error: Color,
        @Serializable(with = ColorSerializer::class) val onError: Color,
        @Serializable(with = ColorSerializer::class) val errorContainer: Color,
        @Serializable(with = ColorSerializer::class) val onErrorContainer: Color,
        @Serializable(with = ColorSerializer::class) val outline: Color,
        @Serializable(with = ColorSerializer::class) val outlineVariant: Color,
        @Serializable(with = ColorSerializer::class) val scrim: Color,
        @Serializable(with = ColorSerializer::class) val surfaceBright: Color,
        @Serializable(with = ColorSerializer::class) val surfaceDim: Color,
        @Serializable(with = ColorSerializer::class) val surfaceContainer: Color,
        @Serializable(with = ColorSerializer::class) val surfaceContainerHigh: Color,
        @Serializable(with = ColorSerializer::class) val surfaceContainerHighest: Color,
        @Serializable(with = ColorSerializer::class) val surfaceContainerLow: Color,
        @Serializable(with = ColorSerializer::class) val surfaceContainerLowest: Color,
        @Serializable(with = ColorSerializer::class) val primaryFixed: Color,
        @Serializable(with = ColorSerializer::class) val primaryFixedDim: Color,
        @Serializable(with = ColorSerializer::class) val onPrimaryFixed: Color,
        @Serializable(with = ColorSerializer::class) val onPrimaryFixedVariant: Color,
        @Serializable(with = ColorSerializer::class) val secondaryFixed: Color,
        @Serializable(with = ColorSerializer::class) val secondaryFixedDim: Color,
        @Serializable(with = ColorSerializer::class) val onSecondaryFixed: Color,
        @Serializable(with = ColorSerializer::class) val onSecondaryFixedVariant: Color,
        @Serializable(with = ColorSerializer::class) val tertiaryFixed: Color,
        @Serializable(with = ColorSerializer::class) val tertiaryFixedDim: Color,
        @Serializable(with = ColorSerializer::class) val onTertiaryFixed: Color,
        @Serializable(with = ColorSerializer::class) val onTertiaryFixedVariant: Color,
    ) {
        internal fun toMaterialColorScheme(): ColorScheme = ColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            inversePrimary = inversePrimary,
            secondary = secondary,
            onSecondary = onSecondary,
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = onSecondaryContainer,
            tertiary = tertiary,
            onTertiary = onTertiary,
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = onTertiaryContainer,
            background = background,
            onBackground = onBackground,
            surface = surface,
            onSurface = onSurface,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurfaceVariant,
            surfaceTint = surfaceTint,
            inverseSurface = inverseSurface,
            inverseOnSurface = inverseOnSurface,
            error = error,
            onError = onError,
            errorContainer = errorContainer,
            onErrorContainer = onErrorContainer,
            outline = outline,
            outlineVariant = outlineVariant,
            scrim = scrim,
            surfaceBright = surfaceBright,
            surfaceDim = surfaceDim,
            surfaceContainer = surfaceContainer,
            surfaceContainerHigh = surfaceContainerHigh,
            surfaceContainerHighest = surfaceContainerHighest,
            surfaceContainerLow = surfaceContainerLow,
            surfaceContainerLowest = surfaceContainerLowest,
            primaryFixed = primaryFixed,
            primaryFixedDim = primaryFixedDim,
            onPrimaryFixed = onPrimaryFixed,
            onPrimaryFixedVariant = onPrimaryFixedVariant,
            secondaryFixed = secondaryFixed,
            secondaryFixedDim = secondaryFixedDim,
            onSecondaryFixed = onSecondaryFixed,
            onSecondaryFixedVariant = onSecondaryFixedVariant,
            tertiaryFixed = tertiaryFixed,
            tertiaryFixedDim = tertiaryFixedDim,
            onTertiaryFixed = onTertiaryFixed,
            onTertiaryFixedVariant = onTertiaryFixedVariant,
        )
    }
}

/** [this] as the [Theme] a caller draws with, both of its schemes converted. */
internal fun FileColorTheme.toTheme(): Theme = ReadTheme(
    isDark = isDark,
    light = light.toMaterialColorScheme(),
    dark = dark.toMaterialColorScheme(),
)

/** A [Theme] whose schemes are already in hand, which is what reading one out of a file leaves. */
@Immutable
private data class ReadTheme(
    override val isDark: Boolean,
    override val light: ColorScheme,
    override val dark: ColorScheme,
) : Theme
