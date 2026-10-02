package com.fromwau.kortex.theme

import androidx.compose.material3.ColorScheme

/**
 * The colors a shell draws with: both schemes, and which of the two this theme asks for.
 *
 * Both are here rather than only the one [isDark] names, because an application may choose its own mode
 * and then needs the scheme the theme did not pick.
 *
 * Implement this for a theme of your own, an AMOLED or a monochrome one for instance, where the schemes
 * are values your code already holds. A theme read from a file comes from [themeIn], or from
 * [rememberFileTheme] where one composition is the whole lifetime.
 *
 * ```kotlin
 * val theme = rememberFileTheme(file).getOrNull() ?: Amoled
 *
 * MaterialTheme(colorScheme = if (appPrefersDark) theme.dark else theme.active) {
 *     BarContent()
 * }
 * ```
 */
public interface Theme {
    /** Which of [light] and [dark] this theme asks for which a caller is free to override. */
    public val isDark: Boolean

    public val light: ColorScheme

    public val dark: ColorScheme

    /** The scheme [isDark] names, for a caller that takes the theme's own choice of mode. */
    public val active: ColorScheme get() = if (isDark) dark else light
}
