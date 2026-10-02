package com.fromwau.kortex.theme

import androidx.compose.ui.graphics.Color
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * How often a theme file is read, which is a caller's decision and has to stay one.
 *
 * A shell drawing several surfaces off one theme can place one watch or one per surface, and the
 * difference is invisible until something counts it. The first test counts what sharing buys. The second
 * pins the property that makes sharing the caller's job: a collection reads the file, rather than being
 * handed a value some earlier collection read.
 */
class ThemeSharingTest {
    @Test
    fun `a shared theme reads the file once, however many collectors it has`() = runTest(timeout = BUDGET) {
        val file = themeFile()
        var reads = 0

        // Upstream of the share, so this counts subscriptions to the file and not collections of the share.
        val shared = themeIn(file)
            .onStart { reads++ }
            .stateIn(backgroundScope, SharingStarted.Eagerly, themeUnread(file))

        assertIs<Ok<Theme>>(shared.firstRead())
        assertIs<Ok<Theme>>(shared.firstRead())

        assertEquals(1, reads, "a shared theme read the file more than once")
    }

    /**
     * The property that makes the sharing above worth doing deliberately, and the one a cache would break.
     *
     * Stated as what a caller can observe rather than as a count, because a count taken outside [themeIn]
     * counts collections of whatever it is wrapped in rather than reads of the file.
     */
    @Test
    fun `a later collection reads the file as it stands, not what an earlier one saw`() = runTest(timeout = BUDGET) {
        val file = themeFile(primary = BLUE)
        assertEquals(Color(0xFFADC6FF), themeIn(file).firstTheme().dark.primary)

        write(file, primary = SLATE)

        assertEquals(
            Color(0xFF445E91),
            themeIn(file).firstTheme().dark.primary,
            "a second collection served colours from the first rather than reading the file",
        )
    }

    /** Skips the stand-in a share starts with, which is not a read of anything. */
    private suspend fun Flow<Result<Theme, ColorSchemeError>>.firstRead() = first { read -> read is Ok }

    private suspend fun Flow<Result<Theme, ColorSchemeError>>.firstTheme(): Theme =
        assertIs<Ok<Theme>>(firstRead()).value

    /** A theme on disk, one per test, so no test can see a file another one wrote. */
    private fun themeFile(primary: String = BLUE): Path {
        val file = File.createTempFile("kortex-theme", ".json").apply { deleteOnExit() }
        val path = Path(file.absolutePath)
        write(path, primary)

        return path
    }

    private fun write(file: Path, primary: String) {
        val scheme = roles(FILLER, mapOf("primary" to primary))

        File(file.toString()).writeText(
            """
            {
              "mode": "dark",
              "light": {
            $scheme
              },
              "dark": {
            $scheme
              }
            }
            """.trimIndent(),
        )
    }

    private companion object {
        val BUDGET = 15.seconds
        const val BLUE = "#FFADC6FF"
        const val SLATE = "#FF445E91"
        const val FILLER = "#FF101010"
    }
}
