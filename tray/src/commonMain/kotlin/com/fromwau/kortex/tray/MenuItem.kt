package com.fromwau.kortex.tray

import com.fromwau.kortex.dbus.DBusValue
import com.fromwau.kortex.dbus.asBytes
import com.fromwau.kortex.dbus.asDictionary
import com.fromwau.kortex.dbus.asFields
import com.fromwau.kortex.dbus.asInt32
import com.fromwau.kortex.dbus.asItems
import com.fromwau.kortex.dbus.asText
import com.fromwau.kortex.dbus.flag
import com.fromwau.kortex.dbus.text
import java.util.Base64

/** What a toggling entry draws beside itself. */
public enum class MenuToggleKind { Checkmark, Radio }

/** Whether a toggling entry is on, off, or neither, which a radio group uses while nothing is chosen. */
public enum class MenuToggleState { Off, On, Indeterminate }

public data class MenuToggle(public val kind: MenuToggleKind, public val state: MenuToggleState)

/** How loudly an entry is meant to read; the caller decides what each looks like. */
public enum class MenuDisposition { Normal, Informative, Warning, Alert }

/** What a caller tells the application the user did, under the names the interface gives them. */
public enum class MenuEvent(internal val wireName: String) {
    Clicked("clicked"),

    /**
     * The pointer entered the entry.
     *
     * The interface documents it and libdbusmenu defines no constant for it, unlike the other three, so an
     * application that ignores it is within its rights.
     */
    Hovered("hovered"),

    /** A submenu was shown; only meaningful for an entry that has one. */
    Opened("opened"),

    Closed("closed"),
}

/**
 * An icon on a menu entry.
 *
 * Unlike a tray icon, [data] is an **encoded image file** rather than raw pixels: libdbusmenu's own helper
 * writes a PNG and base64-encodes it into a string property, and other implementations send the same bytes
 * as an array. Both arrive here as the bytes of the file.
 */
public data class MenuIcon(public val name: String? = null, public val data: ByteArray? = null) {
    public val isEmpty: Boolean get() = name == null && data == null

    override fun equals(other: Any?): Boolean = other is MenuIcon &&
        name == other.name &&
        (data?.contentEquals(other.data) ?: (other.data == null))

    override fun hashCode(): Int = 31 * name.hashCode() + (data?.contentHashCode() ?: 0)

    override fun toString(): String = "MenuIcon(name=$name, data=${data?.size ?: 0} bytes)"
}

/**
 * One entry of an application's menu, and the entries under it.
 *
 * [hasSubmenu] and [children] are separate because an application may say an entry opens a submenu without
 * having sent its contents: those arrive after [Menu.aboutToShow], which is the interface's way of letting
 * an application build a menu only when somebody looks at it.
 */
public data class MenuItem(
    public val id: Int,
    /** A rule drawn across the menu rather than an entry; it has no label and cannot be clicked. */
    public val isSeparator: Boolean,
    /** An underscore marks the character an application suggests as a mnemonic. */
    public val label: String,
    public val enabled: Boolean,
    public val visible: Boolean,
    public val icon: MenuIcon,
    /** Null where the entry does not toggle at all, which is most of them. */
    public val toggle: MenuToggle?,
    /** Each entry is one key combination, such as `["Control", "S"]`, modifiers first. */
    public val shortcuts: List<List<String>>,
    public val disposition: MenuDisposition,
    public val hasSubmenu: Boolean,
    public val children: List<MenuItem>,
)

/**
 * A `(ia{sv}av)` node and everything under it.
 *
 * [depth] is spent on the way down and stops at 0. It is not a dial a caller can raise, because the failure
 * it prevents is not one that could be handed back: an application is free to send a menu nested deeper
 * than this stack, and the result would be a crash rather than a value. Real menus are two or three deep.
 */
internal fun menuItemFrom(node: DBusValue, depth: Int = MAX_MENU_DEPTH): MenuItem? {
    val fields = node.asFields?.takeIf { it.size == NODE_FIELDS } ?: return null
    val id = fields[0].asInt32 ?: return null
    val properties = fields[1].asDictionary.orEmpty()

    val children = if (depth <= 0) {
        emptyList()
    } else {
        fields[2].asItems?.mapNotNull { child -> menuItemFrom(child, depth - 1) }.orEmpty()
    }

    return MenuItem(
        id = id,
        isSeparator = properties.text(TYPE) == SEPARATOR,
        label = properties.text(LABEL).orEmpty(),
        // Absent means true for both, which is why neither can use flag()'s "absent is false".
        enabled = properties.flag(ENABLED, default = true),
        visible = properties.flag(VISIBLE, default = true),
        icon = MenuIcon(properties.text(ICON_NAME)?.ifEmpty { null }, properties.iconData()),
        toggle = properties.toggle(),
        shortcuts = properties.shortcuts(),
        disposition = properties.disposition(),
        hasSubmenu = properties.text(CHILD_DISPLAY) == SUBMENU,
        children = children,
    )
}

private fun Map<String, DBusValue>.toggle(): MenuToggle? {
    val kind = when (text(TOGGLE_TYPE)) {
        CHECKMARK -> MenuToggleKind.Checkmark
        RADIO -> MenuToggleKind.Radio
        else -> return null
    }
    val state = when (this[TOGGLE_STATE]?.asInt32) {
        TOGGLE_ON -> MenuToggleState.On
        TOGGLE_OFF -> MenuToggleState.Off
        // Anything else, the specification's -1 included, is a toggle that will not say.
        else -> MenuToggleState.Indeterminate
    }
    return MenuToggle(kind, state)
}

private fun Map<String, DBusValue>.shortcuts(): List<List<String>> =
    this[SHORTCUT]
        ?.asItems
        ?.mapNotNull { combination -> combination.asItems?.mapNotNull { key -> key.asText } }
        ?.filter { it.isNotEmpty() }
        .orEmpty()

private fun Map<String, DBusValue>.disposition(): MenuDisposition = when (text(DISPOSITION)) {
    INFORMATIVE -> MenuDisposition.Informative
    WARNING -> MenuDisposition.Warning
    ALERT -> MenuDisposition.Alert
    else -> MenuDisposition.Normal
}

/** An array of bytes, or the same bytes base64-encoded into a string, which libdbusmenu's helper writes. */
private fun Map<String, DBusValue>.iconData(): ByteArray? {
    val value = this[ICON_DATA] ?: return null
    value.asBytes?.let { return it.takeIf(ByteArray::isNotEmpty) }

    val encoded = value.asText ?: return null
    return try {
        Base64.getDecoder().decode(encoded).takeIf(ByteArray::isNotEmpty)
    } catch (_: IllegalArgumentException) {
        null
    }
}

/** As deep as a menu may nest before parsing stops; see [menuItemFrom]. */
private const val MAX_MENU_DEPTH = 32

/** An id, its properties, and its children. */
private const val NODE_FIELDS = 3

private const val TYPE = "type"
private const val LABEL = "label"
private const val ENABLED = "enabled"
private const val VISIBLE = "visible"
private const val ICON_NAME = "icon-name"
private const val ICON_DATA = "icon-data"
private const val SHORTCUT = "shortcut"
private const val TOGGLE_TYPE = "toggle-type"
private const val TOGGLE_STATE = "toggle-state"
private const val CHILD_DISPLAY = "children-display"
private const val DISPOSITION = "disposition"

private const val SEPARATOR = "separator"
private const val SUBMENU = "submenu"
private const val CHECKMARK = "checkmark"
private const val RADIO = "radio"
private const val INFORMATIVE = "informative"
private const val WARNING = "warning"
private const val ALERT = "alert"

private const val TOGGLE_OFF = 0
private const val TOGGLE_ON = 1
