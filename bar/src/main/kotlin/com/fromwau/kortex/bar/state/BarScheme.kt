package com.fromwau.kortex.bar.state

import kotlinx.io.files.Path

/**
 * Which colours the bar draws with, and where they come from.
 *
 * The two [Custom] cases are the point of the set rather than an afterthought: a theme file carries both
 * a light and a dark scheme and says which it prefers, and an application is free to override that. Two
 * cases make the override a thing a reader can see rather than a mode argument.
 */
sealed interface BarScheme {
    /** material3's own light scheme, with no file involved. */
    data object Light : BarScheme

    /** material3's own dark scheme. */
    data object Dark : BarScheme

    /** Dark, with the surfaces taken to black, which is what an OLED panel wants. */
    data object Amoled : BarScheme

    /** A scheme read from [file], which a generator rewrites and the bar picks up without restarting. */
    sealed interface Custom : BarScheme {
        val file: Path

        data class Light(override val file: Path = DEFAULT) : Custom

        data class Dark(override val file: Path = DEFAULT) : Custom
    }

    /** The one a click moves to, so the bar can cycle every scheme from a single target. */
    fun next(): BarScheme = when (this) {
        Light -> Dark
        Dark -> Amoled
        Amoled -> Custom.Light()
        is Custom.Light -> Custom.Dark(file)
        is Custom.Dark -> Light
    }

    companion object {
        /**
         * Where a generator leaves a theme on this desktop.
         *
         * `XDG_CACHE_HOME` first, because a generated file belongs in a cache and somebody who moved
         * theirs did so deliberately.
         */
        val DEFAULT: Path = Path(
            System.getenv("XDG_CACHE_HOME") ?: "${System.getProperty("user.home")}/.cache",
            "matugen",
            "colors.json",
        )
    }
}
