package com.fromwau.kortex.bar.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.fromwau.kern.result.getOrNull
import com.fromwau.kortex.bar.state.BarScheme
import com.fromwau.kortex.theme.rememberFileTheme

/**
 * The colours for [scheme], reading the file where it names one.
 *
 * A theme that will not read falls back to material3's own, rather than refusing to draw: a bar whose
 * generator has not run yet, or whose file is half written during a save, is still a bar. What it does not
 * do is hide the failure forever, since the scheme a person picked is visible in the button's own label.
 */
@Composable
internal fun colorsFor(scheme: BarScheme): ColorScheme = when (scheme) {
    BarScheme.Light -> lightColorScheme()
    BarScheme.Dark -> darkColorScheme()

    // Surfaces to black rather than a whole palette of its own, which is the one thing an OLED panel is
    // actually asking for.
    BarScheme.Amoled -> darkColorScheme(
        background = Color.Black,
        surface = Color.Black,
        surfaceContainer = Color.Black,
        surfaceContainerLow = Color.Black,
        surfaceContainerLowest = Color.Black,
    )

    is BarScheme.Custom.Light -> rememberFileTheme(scheme.file).getOrNull()?.light ?: lightColorScheme()
    is BarScheme.Custom.Dark -> rememberFileTheme(scheme.file).getOrNull()?.dark ?: darkColorScheme()
}
