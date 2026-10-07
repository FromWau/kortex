package com.fromwau.kortex.bar.state

import com.fromwau.kortex.theme.GENERATED_THEME
import kotlinx.io.files.Path

/**
 * Which colours the bar draws with, and where they come from.
 *
 * The two [Custom] cases are the point of the set rather than an afterthought: a theme file carries both
 * a light and a dark scheme and says which it prefers, and an application is free to override that. Two
 * cases make the override a thing a reader can see rather than a mode argument.
 */
sealed interface BarScheme {
    /** Dark, with the surfaces taken to black, which is what an OLED panel wants. */
    data object Amoled : BarScheme

    /** Black on grey with no hue at all, whatever the wallpaper is doing. */
    data object Monochrome : BarScheme

    /** A scheme read from [file], which a generator rewrites and the bar picks up without restarting. */
    sealed interface Custom : BarScheme {
        val file: Path

        data class Light(override val file: Path = GENERATED_THEME) : Custom

        data class Dark(override val file: Path = GENERATED_THEME) : Custom
    }

    /** The one a click moves to, so the bar can cycle every scheme from a single target. */
    fun next(): BarScheme = when (this) {
        Amoled -> Monochrome
        Monochrome -> Custom.Light()
        is Custom.Light -> Custom.Dark(file)
        is Custom.Dark -> Amoled
    }

    companion object {
        /**
         * What the bar draws before anybody has clicked, in the one place both the state and its holder
         * read it from, so the first frame and the first settled state cannot disagree.
         *
         * The generated theme, since following the wallpaper is what the bar is for.
         */
        val Starting: BarScheme = Custom.Dark()
    }
}
