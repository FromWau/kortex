package com.fromwau.kortex.bar.state

import kotlin.time.Duration
import com.fromwau.kortex.notification.Expiry
import com.fromwau.kortex.notification.NotificationImage
import com.fromwau.kortex.notification.Urgency
import com.fromwau.kortex.tray.ItemAddress
import com.fromwau.kortex.tray.TrayIcon

/** One entry of the system tray, as the bar draws it. */
data class TrayEntry(
    val address: ItemAddress,
    /** The item's own id, which is what tells two items of one application apart. */
    val id: String,
    /** What to show on hover: the tooltip where the item offers one, and its title where it does not. */
    val hover: String,
    /**
     * The icon as the item described it, resolved where it is drawn rather than here.
     *
     * A name and a theme, usually, since almost nothing sends pixels. Carrying the description instead of
     * resolved artwork keeps file reading out of the state holder and lets `:icons` do the looking up.
     */
    val icon: TrayIcon,
    /** The item asked to be noticed, so its attention icon is the one to draw and it is worth marking. */
    val needsAttention: Boolean,
)

/**
 * How long [this] stays on screen before it expires, or null where it stays until somebody closes it.
 *
 * A critical notification stays, as the notification specification asks of one. Otherwise the application's
 * own [Expiry] decides, and [default] is the time for one that left it to the server.
 */
fun Posted.showsFor(default: Duration): Duration? = when {
    urgency == Urgency.Critical -> null
    else -> when (val asked = expiry) {
        Expiry.ServerDefault -> default
        Expiry.Never -> null
        is Expiry.After -> asked.duration
    }
}

/** One posted notification, as the popup draws it. */
data class Posted(
    val id: UInt,
    /** Which posting of [id] this is; a replacement reuses the id, so this is what makes two of them differ. */
    val revision: UInt,
    val appName: String,
    val summary: String,
    val body: String,
    val urgency: Urgency,
    /** How long the application asked for it to stay up. */
    val expiry: Expiry,
    /** The image the application sent inline, or null where it sent none. */
    val image: NotificationImage?,
    /** The icon name the application named instead, which a theme may carry. */
    val iconName: String?,
)

/** The workspaces one bar's monitor holds, as pills, and the special workspace open over them. */
data class WorkspaceStrip(
    val slots: List<WorkspaceSlot>,
    /** The open special workspace's name without its `special:` prefix, or null while none is open. */
    val special: String?,
)

/** One pill of the workspace strip. */
data class WorkspaceSlot(
    val id: Int,
    /** Zero for a slot in a gap or past the end, which is a workspace Hyprland would make on arrival. */
    val windows: Int,
    val shown: SlotShown,
    /** One of its windows asked for attention and has not had focus since. */
    val urgent: Boolean,
)

/** Whether this bar's monitor is showing a slot's workspace, and whether that monitor has focus. */
enum class SlotShown {
    /** Shown on a monitor with focus, which is the one workspace a person is in. */
    Focused,

    /** Shown on a monitor while another one has focus. */
    Visible,

    Hidden,
}

/** The window with keyboard focus, as the bar names it. */
data class FocusedWindow(
    val appId: String,
    val title: String,
)

/** What a media player is playing, as the bar names it. */
data class NowPlaying(
    /** The bus name a click is sent to. */
    val player: String,
    /** The player as it names itself, which is what tells a browser tab from a music player. */
    val app: String,
    val title: String,
    val artists: List<String>,
    val playing: Boolean,
    val position: kotlin.time.Duration?,
    val length: kotlin.time.Duration?,
)
