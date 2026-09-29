package com.fromwau.kortex.tray

import com.fromwau.kortex.dbus.DBusValue
import com.fromwau.kortex.dbus.unwrapped

/** Where an item lives: the connection that exports it, and the object on that connection. */
public data class ItemAddress(public val service: String, public val path: String) {
    /** As the watcher spells it back, which is also how a caller identifies an item to a command. */
    override fun toString(): String = "$service$path"

    public companion object {
        /** What an item exports itself at when the watcher named only a connection. */
        public const val DEFAULT_PATH: String = "/StatusNotifierItem"

        /**
         * One entry of the watcher's `RegisteredStatusNotifierItems`.
         *
         * The specification has `RegisterStatusNotifierItem` take a bus name, and watchers disagree about
         * what they store: some keep the name alone, some append the object path, and an application that
         * registered a path rather than a name leaves the watcher holding a path. [sender] is the
         * connection the registration came from, which is the only thing that resolves the third case.
         */
        public fun parse(entry: String, sender: String? = null): ItemAddress? {
            val trimmed = entry.trim()
            if (trimmed.isEmpty()) return null

            // A leading slash means the whole entry is an object path, so only the sender names the peer.
            if (trimmed.startsWith("/")) return sender?.let { ItemAddress(it, trimmed) }

            val slash = trimmed.indexOf('/')
            if (slash < 0) return ItemAddress(trimmed, DEFAULT_PATH)
            return ItemAddress(trimmed.take(slash), trimmed.substring(slash))
        }
    }
}

/** What kind of thing an item stands for, which a caller may sort or group by. */
public enum class TrayCategory {
    ApplicationStatus,
    Communications,
    SystemServices,
    Hardware,
    ;

    internal companion object {
        /** The specification's own default for an item that does not say. */
        fun fromWire(raw: String?): TrayCategory =
            entries.firstOrNull { it.name == raw } ?: ApplicationStatus
    }
}

/** How much attention an item is asking for. */
public enum class TrayStatus {
    /** Of no particular interest; a host may hide it. */
    Passive,

    /** Worth showing. */
    Active,

    /** Asking to be noticed, and the attention icon is the one to draw. */
    NeedsAttention,
    ;

    internal companion object {
        /**
         * [Active] for anything unrecognised, rather than [Passive].
         *
         * Both are guesses, and the two are not equally bad: hiding an item because its status was spelled
         * in a way this does not know is worse than showing one that asked to be left alone.
         */
        fun fromWire(raw: String?): TrayStatus = entries.firstOrNull { it.name == raw } ?: Active
    }
}

/**
 * Icon pixels as they arrive, which is `ARGB32` in **network byte order**.
 *
 * Big-endian, so a little-endian caller has to reverse each group of four bytes before handing them to
 * anything that expects native order. They stay bytes here: turning them into a bitmap needs a toolkit,
 * and choosing one is what a provider is not for.
 */
public class TrayImage(public val width: Int, public val height: Int, public val argb: ByteArray) {
    override fun equals(other: Any?): Boolean = other is TrayImage &&
        width == other.width &&
        height == other.height &&
        argb.contentEquals(other.argb)

    override fun hashCode(): Int = (width * 31 + height) * 31 + argb.contentHashCode()

    override fun toString(): String = "TrayImage(${width}x$height, ${argb.size} bytes)"
}

/**
 * The ways an item offers one icon, both of them, or neither.
 *
 * Kept as it arrived rather than resolved to one. The specification prefers [name] where it is set, but a
 * caller that cannot look up an icon theme wants [pixmaps] even then, and picking for them is a decision
 * about drawing.
 */
public data class TrayIcon(
    public val name: String? = null,
    public val themePath: String? = null,
    public val pixmaps: List<TrayImage> = emptyList(),
) {
    public val isEmpty: Boolean get() = name == null && pixmaps.isEmpty()

}

/** The hover text an item offers, which no host is obliged to draw. */
public data class TrayToolTip(
    public val icon: TrayIcon,
    public val title: String,
    /** May carry the small subset of HTML the specification allows, which a caller decides what to do with. */
    public val description: String,
)

/** One entry of the system tray. */
public data class TrayItem(
    public val address: ItemAddress,
    /** The item's own id, which is stable across restarts where a bus name is not. */
    public val id: String,
    public val title: String,
    public val status: TrayStatus,
    public val category: TrayCategory,
    public val icon: TrayIcon,
    public val overlayIcon: TrayIcon,
    public val attentionIcon: TrayIcon,
    public val toolTip: TrayToolTip?,
    /** The `com.canonical.dbusmenu` object this item's menu lives at, where it offers one. */
    public val menuPath: String?,
    /** The item would rather be given its menu than be activated. */
    public val isMenu: Boolean,
)

/** Builds an item out of one `GetAll`, with everything the item left out taking its default. */
internal fun trayItemFrom(address: ItemAddress, properties: Map<String, DBusValue>): TrayItem {
    val themePath = properties.text("IconThemePath")?.ifEmpty { null }
    return TrayItem(
        address = address,
        id = properties.text("Id").orEmpty(),
        title = properties.text("Title").orEmpty(),
        status = TrayStatus.fromWire(properties.text("Status")),
        category = TrayCategory.fromWire(properties.text("Category")),
        icon = properties.icon("IconName", "IconPixmap", themePath),
        overlayIcon = properties.icon("OverlayIconName", "OverlayIconPixmap", themePath),
        attentionIcon = properties.icon("AttentionIconName", "AttentionIconPixmap", themePath),
        toolTip = properties.toolTip(themePath),
        menuPath = properties.objectPath("Menu"),
        isMenu = properties.flagOr("ItemIsMenu", default = false),
    )
}

private fun Map<String, DBusValue>.icon(name: String, pixmap: String, themePath: String?): TrayIcon = TrayIcon(
    name = text(name)?.ifEmpty { null },
    themePath = themePath,
    pixmaps = images(pixmap),
)

private fun Map<String, DBusValue>.toolTip(themePath: String?): TrayToolTip? {
    val fields = (this["ToolTip"]?.unwrapped as? DBusValue.Struct)?.fields ?: return null
    if (fields.size != TOOLTIP_FIELDS) return null

    val tip = TrayToolTip(
        icon = TrayIcon(
            name = (fields[0] as? DBusValue.Text)?.value?.ifEmpty { null },
            themePath = themePath,
            pixmaps = imagesIn(fields[1]),
        ),
        title = (fields[2] as? DBusValue.Text)?.value.orEmpty(),
        description = (fields[3] as? DBusValue.Text)?.value.orEmpty(),
    )
    // An item with nothing to say sends four empty fields rather than leaving the property out.
    return tip.takeUnless { it.icon.isEmpty && it.title.isEmpty() && it.description.isEmpty() }
}

/**
 * A value as a string, looking through a variant on the way.
 *
 * Every accessor here does, because the same property reaches this both already unwrapped, out of a
 * `GetAll`, and still boxed, out of a `PropertiesChanged`. Unwrapping in one place rather than at each
 * caller is what stops a boxed value reading as a property the item never sent.
 */
internal val DBusValue.text: String? get() = (unwrapped as? DBusValue.Text)?.value

internal fun Map<String, DBusValue>.text(key: String): String? = this[key]?.text

internal fun Map<String, DBusValue>.objectPath(key: String): String? =
    (this[key]?.unwrapped as? DBusValue.ObjectPath)?.value?.takeUnless { it == "/" }

/**
 * A flag, or [default] where the sender left it out.
 *
 * The default is a parameter because it is the thing that differs: a tray item's `ItemIsMenu` is false
 * when absent, and a menu entry's `enabled` and `visible` are true.
 */
internal fun Map<String, DBusValue>.flagOr(key: String, default: Boolean): Boolean =
    (this[key]?.unwrapped as? DBusValue.Bool)?.value ?: default

private fun Map<String, DBusValue>.images(key: String): List<TrayImage> = imagesIn(this[key])

/** An `a(iiay)`, where each struct is a width, a height and the pixels. */
private fun imagesIn(value: DBusValue?): List<TrayImage> = (value?.unwrapped as? DBusValue.Sequence)
    ?.values
    ?.mapNotNull { entry ->
        val fields = (entry as? DBusValue.Struct)?.fields ?: return@mapNotNull null
        val width = (fields.getOrNull(0) as? DBusValue.I32)?.value ?: return@mapNotNull null
        val height = (fields.getOrNull(1) as? DBusValue.I32)?.value ?: return@mapNotNull null
        val pixels = (fields.getOrNull(2) as? DBusValue.Bytes)?.value ?: return@mapNotNull null
        TrayImage(width, height, pixels)
    }
    .orEmpty()

private const val TOOLTIP_FIELDS = 4
