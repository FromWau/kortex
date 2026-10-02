package com.fromwau.kortex.icons

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readLines

/**
 * Where an icon named by an application is looked for.
 *
 * An application that puts an icon in the tray usually sends a *name*, not pixels, and the name means
 * nothing without somewhere to look it up. This is that somewhere: the theme to prefer and the directories
 * to search, both of which a caller can replace.
 *
 * @property name the theme to prefer, such as `Papirus`. `hicolor` is always searched after it, because the
 *   specification makes it the fallback every theme ends at.
 * @property directories the icon roots, in order. Each holds theme folders, and may itself hold loose icons
 *   the way `/usr/share/pixmaps` does.
 */
public data class IconTheme(
    public val name: String,
    public val directories: List<Path> = iconDirectories(),
) {
    /**
     * The file [icon] names at about [size] logical pixels, or null where nothing in the search path has it.
     *
     * Scalable art wins over raster of the wrong size, and raster of the right size wins over scalable,
     * which is what makes a 16-pixel panel icon crisp where one exists and smooth where it does not.
     *
     * @param themePath a directory the application nominated itself, searched before anything else. Steam
     *   sends one of these and its icon is in no system theme, so an application that bothers to say where
     *   its art is has to be believed first.
     */
    public fun find(icon: String, size: Int, themePath: String? = null): Path? {
        val roots = buildList {
            themePath?.takeIf { it.isNotBlank() }?.let { add(Path.of(it)) }
            addAll(directories)
        }
        return roots.firstNotNullOfOrNull { root -> root.findIcon(icon, size, name) }
    }

    public companion object {
        /**
         * The theme the desktop is configured with, or [FALLBACK_THEME] where nothing says.
         *
         * Read from GTK's own settings file, which is where every desktop this runs on records it, rather
         * than by running `gsettings`: a file read cannot fail for want of a binary, and the answer is the
         * same one.
         */
        public fun ofDesktop(): IconTheme = IconTheme(configuredThemeName() ?: FALLBACK_THEME)

        /** What the specification makes every theme's last resort, and the only theme guaranteed present. */
        public const val FALLBACK_THEME: String = "hicolor"
    }
}

/**
 * The icon roots this machine has, in the order the specification searches them.
 *
 * `$XDG_DATA_HOME` and `$XDG_DATA_DIRS` decide it, with the documented defaults where they are unset, plus
 * `~/.icons`, which predates the variables and is still where some applications install.
 */
public fun iconDirectories(): List<Path> {
    val home = System.getProperty("user.home").orEmpty()
    val dataHome = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.local/share"
    val dataDirs = System.getenv("XDG_DATA_DIRS")?.takeIf { it.isNotBlank() } ?: "/usr/local/share:/usr/share"

    return buildList {
        add(Path.of(home, ".icons"))
        add(Path.of(dataHome, "icons"))
        dataDirs.split(':').filter { it.isNotBlank() }.forEach { add(Path.of(it, "icons")) }
        // Loose icons, no theme and no size folder, which is where a lot of older software still installs.
        dataDirs.split(':').filter { it.isNotBlank() }.forEach { add(Path.of(it, "pixmaps")) }
    }.filter { it.exists() }
}

/** `gtk-icon-theme-name` from whichever GTK settings file has it, newest first. */
private fun configuredThemeName(): String? {
    val home = System.getProperty("user.home") ?: return null
    val config = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.config"
    return listOf("gtk-4.0", "gtk-3.0")
        .map { Path.of(config, it, "settings.ini") }
        .filter { it.exists() }
        .firstNotNullOfOrNull { settings ->
            settings.readLines()
                .firstOrNull { it.trimStart().startsWith(SETTING) }
                ?.substringAfter('=')
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }
}

/**
 * The best file for [icon] under this root, preferring [theme] and falling back to `hicolor` and then to
 * the root itself, which is how a loose directory such as `/usr/share/pixmaps` is searched.
 */
private fun Path.findIcon(icon: String, size: Int, theme: String): Path? {
    val themes = listOf(theme, IconTheme.FALLBACK_THEME).distinct()
    val candidates = themes.flatMap { resolve(it).iconFiles(icon) } + iconFiles(icon)
    return candidates.minByOrNull { it.distanceFrom(size) }
}

/** Every file in this directory tree whose name is [icon] with an extension this can decode. */
private fun Path.iconFiles(icon: String): List<Path> {
    if (!isDirectory()) return emptyList()
    val wanted = DECODABLE.map { "$icon.$it" }
    return Files.walk(this, DEPTH).use { paths ->
        paths.filter { it.name in wanted }.toList()
    }
}

/**
 * How far this file is from the wanted size: 0 for an exact match, then scalable, then the nearest size.
 *
 * Read off whichever folder in the path names a size, because the layout varies: `16x16/panel/x.svg`,
 * `scalable/apps/x.svg` and a loose `pixmaps/x.png` all occur, and the folder is the only size information
 * a theme carries without parsing its `index.theme`, which this deliberately does not do.
 *
 * Being an SVG does not make a file size-agnostic, which was the first thing this got wrong: Papirus ships
 * one icon as three separate SVGs under `16x16`, `22x22` and `24x24`, so trusting the extension made every
 * candidate score the same and the first one win whatever was asked for.
 */
private fun Path.distanceFrom(size: Int): Int {
    val folders = (0 until nameCount - 1).map { getName(it).toString() }.asReversed()
    val sized = folders.firstNotNullOfOrNull { folder ->
        SIZE_FOLDER.matchEntire(folder)?.groupValues?.get(1)?.toIntOrNull()
    }
    return when {
        sized == size -> 0
        SCALABLE in folders -> SCALABLE_DISTANCE
        sized != null -> SCALABLE_DISTANCE + kotlin.math.abs(sized - size)
        else -> UNKNOWN_DISTANCE
    }
}

private const val SETTING = "gtk-icon-theme-name"
private const val DEPTH = 4
private const val SCALABLE_DISTANCE = 1
private const val UNKNOWN_DISTANCE = 10_000
private val DECODABLE = listOf("png", "svg")
private const val SCALABLE = "scalable"
private val SIZE_FOLDER = Regex("""(\d+)x\d+""")
