package com.fromwau.kortex.icons

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The lookup against the icons this machine really has, which is the only way to test it honestly.
 *
 * A fixture tree would prove the walk and nothing about the layout themes actually use: that an icon can
 * be SVG only, that a size folder may be `22x22` or `scalable`, and that an application's own directory
 * holds art no theme does are all facts about this filesystem.
 */
class IconThemeTest {
    @Test
    fun `the configured theme is read from the desktop rather than guessed`() {
        val theme = IconTheme.ofDesktop()

        assertTrue(theme.name.isNotBlank(), "no theme name at all")
        assertTrue(theme.directories.isNotEmpty(), "no icon directory on a machine that has icons")
        assertTrue(
            theme.directories.all { it.toFile().isDirectory },
            "a directory that does not exist was kept: ${theme.directories}",
        )
    }

    /** hicolor is the one theme the specification guarantees, so it is the one a test can rely on. */
    @Test
    fun `an icon only hicolor has is still found`() {
        val found = IconTheme("a-theme-no-machine-has").find("kdeconnectindicatordark", size = 22)

        assertNotNull(found, "the hicolor fallback did not find an icon that is installed")
        assertTrue(found.toString().endsWith(".svg"), "expected the SVG this icon ships as, got $found")
    }

    @Test
    fun `a name nothing installed is not found rather than guessed at`() {
        assertNull(IconTheme.ofDesktop().find("there-is-no-icon-called-this", size = 16))
    }

    /** Steam's icon is in no system theme, and Steam says where it is. */
    @Test
    fun `a directory the application nominated is searched before the themes`() {
        val steam = "${System.getProperty("user.home")}/.local/share/Steam/public"

        val found = IconTheme.ofDesktop().find("steam_tray_mono", size = 22, themePath = steam)

        assertNotNull(found, "an icon in the application's own directory was not found")
        assertTrue(found.toString().startsWith(steam), "found $found, which is not in the nominated directory")
    }

    @Test
    fun `an exact size beats one that has to be scaled`() {
        val theme = IconTheme("Papirus")

        val small = theme.find("kdeconnectindicatordark", size = 16)
        val large = theme.find("kdeconnectindicatordark", size = 64)

        assertNotNull(small)
        assertNotNull(large)
        // Papirus ships this one at several sizes, so the two asks should not land on the same file.
        assertEquals(
            setOf(small, large).size, 2,
            "both sizes resolved to $small, so the size is not being used",
        )
    }
}
