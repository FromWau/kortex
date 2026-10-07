package com.fromwau.kortex.theme

import com.fromwau.kern.dirs.FileError
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertError
import com.fromwau.kortex.watch.WatchError
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import java.io.File
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

/**
 * The schema against a theme file a generator actually wrote, rather than one this suite made up.
 *
 * A fixture agrees with whatever the schema already believes, which is the one thing a test here has to
 * catch: the file is a contract with another program, and only that program's own output can prove the
 * two still agree. matugen writes this one, from the template in this desktop's own matugen config.
 *
 * It needs that file to exist, so a machine that has never generated a theme fails here naming the path
 * rather than passing quietly. That is the same bargain `:icons` takes for icon themes.
 */
class GeneratedThemeTest {
    @Test
    fun `the theme a generator wrote decodes into a scheme`() {
        val text = assertNotNull(
            File(GENERATED_THEME.toString()).takeIf(File::isFile)?.readText(),
            "no generated theme at $GENERATED_THEME, so nothing here proves the schema still matches",
        )

        val theme = assertIs<Ok<FileColorTheme>>(
            decode(text),
            "the generated theme did not decode, so the schema and the template have drifted",
        ).value

        // Both schemes came from their own half of the file rather than one of them twice, which a
        // template that rendered the same mode under both keys would otherwise pass.
        assertNotEquals(theme.light.surface, theme.dark.surface)
    }

    /** The reactive half: a watcher on a theme that is not there says so, and says it about the file. */
    @Test
    fun `a theme file that does not exist is reported as unreadable`() = runTest {
        val missing = Path("$GENERATED_THEME.nothing-here")

        val unreadable = themeIn(missing).first().assertError<ColorSchemeError.Unreadable>()
        assertIs<FileError.NotFound>(assertIs<WatchError.Unreadable>(unreadable.cause).cause)
    }
}
