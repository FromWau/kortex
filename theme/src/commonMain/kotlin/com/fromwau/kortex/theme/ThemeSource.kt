package com.fromwau.kortex.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.fromwau.kern.dirs.FileError
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.map
import com.fromwau.kortex.watch.WatchError
import com.fromwau.kortex.watch.watchText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.io.files.Path
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Where a generator leaves the theme on this desktop: `matugen/colors.json` in the cache directory, which is
 * `XDG_CACHE_HOME` where that is set and `~/.cache` where it is not.
 */
public val GENERATED_THEME: Path = Path(
    System.getenv("XDG_CACHE_HOME") ?: "${System.getProperty("user.home")}/.cache",
    "matugen",
    "colors.json",
)

private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
}

/**
 * The theme in [file], and again whenever the file changes.
 *
 * Cold, so each collection watches the file for itself. A shell drawing several surfaces off one theme
 * should therefore collect this once, onto a scope that outlives any one surface, and share what comes
 * out, rather than collect it per surface:
 *
 * ```kotlin
 * val theme = themeIn(file).stateIn(shellScope, SharingStarted.WhileSubscribed(), themeUnread(file))
 * ```
 *
 * Reading and decoding both run off the collector's thread, so this is safe to collect from the one that
 * draws. [rememberFileTheme] is that collection for a caller with a single surface.
 *
 * @param file the theme to read. A tool that regenerates it writes over this path, and the new colours
 *   arrive without the shell restarting.
 */
public fun themeIn(file: Path): Flow<Result<Theme, ColorSchemeError>> =
    file.watchText().map { read ->
        when (read) {
            is Err -> Err(ColorSchemeError.Unreadable(read.error))
            is Ok -> decode(read.value).map(FileColorTheme::toTheme)
        }
    }

/**
 * What stands in for a theme before any read has happened: the failure a missing file gives.
 *
 * The initial value for sharing [themeIn] through `stateIn`, where a collector can arrive before the
 * first read has. A caller falls back to its own colours for this exactly as it would for a file that is
 * genuinely not there, which is why the two are the same answer rather than two to handle.
 */
public fun themeUnread(file: Path): Result<Theme, ColorSchemeError> =
    Err(ColorSchemeError.Unreadable(WatchError.Unreadable(file, FileError.NotFound(file))))

/**
 * The theme in [file] for as long as this composition lives, and again whenever the file changes.
 *
 * One watcher per call site, so a shell with more than one surface wants [themeIn] shared on a scope of
 * its own instead.
 *
 * ```kotlin
 * val theme = rememberFileTheme(file).getOrNull() ?: Amoled
 *
 * MaterialTheme(colorScheme = theme.active) { BarContent() }
 * ```
 */
@Composable
public fun rememberFileTheme(file: Path): Result<Theme, ColorSchemeError> {
    // collectAsState keys its collector on the flow instance and themeIn() allocates a new one per call,
    // so without remembering it the watch is torn down and re-registered on every recomposition.
    val changes = remember(file) { themeIn(file) }

    val result by changes.collectAsState(initial = themeUnread(file))

    return result
}

/**
 * [text] as the theme it holds, or why it holds none.
 *
 * `decodeFromString` reports every malformed input by throwing, which is the one place this module has to
 * catch rather than match: the throw is caught here, at the edge, and carried on as a value from then on.
 */
internal fun decode(text: String): Result<FileColorTheme, ColorSchemeError> =
    try {
        Ok(json.decodeFromString(FileColorTheme.serializer(), text))
    } catch (failure: SerializationException) {
        Err(ColorSchemeError.Unparseable(failure.message ?: "the file does not hold a theme"))
    }
