package com.fromwau.kortex.theme

import androidx.compose.ui.graphics.Color
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * What a theme file has to hold to become a scheme, and what each shape of a bad one answers.
 *
 * The schema is a contract between this module and whatever writes the file, so these tests state it in
 * the file's own vocabulary rather than through the 48 roles. Nothing here needs a compositor or a bus.
 */
class ThemeFileTest {
    @Test
    fun `a colour is read alpha first, as the file writes it`() {
        assertEquals(Color(0xFFADC6FF), colorIn("#FFADC6FF"))
    }

    /** The half-opaque case, which a signed `Int` would read as a negative number rather than a colour. */
    @Test
    fun `an alpha above half is not mistaken for a negative colour`() {
        assertEquals(Color(0x80ADC6FF), colorIn("#80ADC6FF"))
    }

    @Test
    fun `six digits are an opaque colour`() {
        assertEquals(Color(0xFFADC6FF), colorIn("#ADC6FF"))
    }

    @Test
    fun `case does not matter, and nor does the leading hash`() {
        assertEquals(Color(0xFFADC6FF), colorIn("ffadc6ff"))
    }

    @Test
    fun `a colour round trips through the string it is written as`() {
        val written = Json.encodeToString(ColorSerializer, Color(0x80ADC6FF))

        assertEquals("\"#80adc6ff\"", written)
        assertEquals(Color(0x80ADC6FF), Json.decodeFromString(ColorSerializer, written))
    }

    @Test
    fun `a string that is not a colour is refused rather than read as one`() {
        for (text in listOf("#FFF", "#ADC6F", "#GGADC6FF", "teal", "", "#FFADC6FF0")) {
            assertFailsWith<SerializationException>("'$text' was accepted as a colour") { colorIn(text) }
        }
    }

    @Test
    fun `the mode in the file picks which of the two schemes is drawn`() {
        assertEquals(Color(0xFF445E91), themeOf(mode = "light").colorScheme.primary)
        assertEquals(Color(0xFFADC6FF), themeOf(mode = "dark").colorScheme.primary)
    }

    /** The keys the generator writes that this schema does not name, which must not fail the parse. */
    @Test
    fun `fields the schema does not declare are passed over`() {
        assertIs<Ok<FileColorTheme>>(decode(themeText(mode = "dark")))
    }

    @Test
    fun `a file naming a mode that does not exist is reported`() {
        val failure = assertIs<Err<ColorSchemeError>>(decode(themeText(mode = "sepia")))

        assertIs<ColorSchemeError.Unparseable>(failure.error)
    }

    @Test
    fun `a wallpaper is optional, so a theme with no image still decodes`() {
        assertEquals(null, themeOf(mode = "dark", wallpaper = null).wallpaper)
    }

    @Test
    fun `text that is not a theme at all is reported, not thrown`() {
        val failure = assertIs<Err<ColorSchemeError>>(decode("not json"))

        assertIs<ColorSchemeError.Unparseable>(failure.error)
    }

    @Test
    fun `a theme missing one of its roles is reported, not half built`() {
        val missing = themeText(mode = "dark").replace("\"outline\":", "\"notARole\":")

        assertIs<Err<ColorSchemeError>>(decode(missing))
    }

    /**
     * The roles a short `ColorScheme` argument list leaves behind, which is the failure this pins.
     *
     * material3 keeps deprecated constructors that take fewer roles and fill the twelve fixed ones with
     * `Color.Unspecified`, so a mapping that passes only the older set compiles and then draws nothing for
     * them. These three come from different ends of the list the current constructor wants.
     */
    @Test
    fun `every role reaches the material scheme, so none is left unspecified`() {
        val scheme = themeOf(mode = "dark").colorScheme.toMaterialColorScheme()

        assertEquals(Color(0xFF000000), scheme.scrim)
        assertEquals(Color(0xFF1A2121), scheme.surfaceContainer)
        assertEquals(Color(0xFF80D4D6), scheme.primaryFixed)
    }

    @Test
    fun `a theme carries both schemes, so a caller can override the mode`() {
        val theme = themeOf(mode = "dark").toTheme()

        assertEquals(Color(0xFFADC6FF), theme.dark.primary)
        assertEquals(Color(0xFF445E91), theme.light.primary, "the mode the file did not ask for is lost")
        assertEquals(true, theme.isDark)
    }

    /** By identity, since material3's `ColorScheme` has no `equals` and `active` hands back one of the two. */
    @Test
    fun `the active scheme is the one the mode names`() {
        val dark = themeOf(mode = "dark").toTheme()
        val light = themeOf(mode = "light").toTheme()

        assertSame(dark.dark, dark.active)
        assertSame(light.light, light.active)
    }

    private fun colorIn(text: String): Color = Json.decodeFromString(ColorSerializer, "\"$text\"")

    private fun themeOf(mode: String, wallpaper: String? = "/a/wall.png"): FileColorTheme =
        assertIs<Ok<FileColorTheme>>(decode(themeText(mode, wallpaper))).value

    /**
     * A theme file as the generator writes one: both schemes, and the three fields this schema ignores.
     *
     * Every light role is one filler colour and every dark role another, so a test that cares about a
     * particular role says so in [DARK_ROLES] rather than in 96 lines of fixture.
     */
    private fun themeText(mode: String, wallpaper: String? = "/a/wall.png"): String = """
        {
          "version": 1,
          "mode": "$mode",
          "wallpaper": ${wallpaper?.let { "\"$it\"" } ?: "null"},
          "sourceColor": "#FF049A9D",
          "light": {
        ${roles(LIGHT_FILLER)}
          },
          "dark": {
        ${roles(DARK_FILLER, DARK_ROLES)}
          }
        }
    """.trimIndent()

    private companion object {
        const val LIGHT_FILLER = "#FF445E91"
        const val DARK_FILLER = "#FFADC6FF"

        /** The dark roles a test names, so the rest of the fixture can be one colour. */
        val DARK_ROLES = mapOf(
            "scrim" to "#FF000000",
            "surfaceContainer" to "#FF1A2121",
            "primaryFixed" to "#FF80D4D6",
        )
    }
}
