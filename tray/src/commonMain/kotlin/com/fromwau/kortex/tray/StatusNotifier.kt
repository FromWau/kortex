package com.fromwau.kortex.tray

/**
 * The names the two halves of the tray have to agree on, which is why they live in neither.
 *
 * `:tray` is a host, and with [TrayWatcher] a watcher too. A host that looked for a path the watcher
 * never exported, or subscribed to a signal name the watcher never emits, would find nothing and have
 * nothing to report, which is the quietest kind of wrong. One definition rather than several kept in step
 * by hand.
 */
internal const val WATCHER_PATH: String = "/StatusNotifierWatcher"

internal const val REGISTER_ITEM: String = "RegisterStatusNotifierItem"
internal const val REGISTER_HOST: String = "RegisterStatusNotifierHost"

internal const val ITEM_REGISTERED: String = "StatusNotifierItemRegistered"
internal const val ITEM_UNREGISTERED: String = "StatusNotifierItemUnregistered"
internal const val HOST_REGISTERED: String = "StatusNotifierHostRegistered"

internal const val REGISTERED_ITEMS: String = "RegisteredStatusNotifierItems"
internal const val HOST_IS_REGISTERED: String = "IsStatusNotifierHostRegistered"
internal const val PROTOCOL_VERSION: String = "ProtocolVersion"

/** A watcher, under a name some desktop actually uses. */
internal data class Watcher(val service: String, val iface: String)

/**
 * KDE's names first, because they are the ones desktops actually use.
 *
 * On a Hyprland session with ags running, `org.freedesktop.StatusNotifierWatcher` does not exist at all
 * and `org.kde.StatusNotifierWatcher` does. Applications dial the KDE name, so that is the one
 * [TrayWatcher] takes, and a host searches both because a desktop that serves only the other one is
 * still a desktop with a tray.
 */
internal val WATCHERS: List<Watcher> = listOf(
    Watcher("org.kde.StatusNotifierWatcher", "org.kde.StatusNotifierWatcher"),
    Watcher("org.freedesktop.StatusNotifierWatcher", "org.freedesktop.StatusNotifierWatcher"),
)

/** The one a watcher of our own takes, since it is the one applications call. */
internal val KDE_WATCHER: Watcher = WATCHERS.first()
