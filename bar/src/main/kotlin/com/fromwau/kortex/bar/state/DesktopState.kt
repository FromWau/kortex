package com.fromwau.kortex.bar.state

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

/** One posted notification, as the popup draws it. */
data class Posted(
    val id: UInt,
    /** Which posting of [id] this is; a replacement reuses the id, so this is what makes two of them differ. */
    val revision: UInt,
    val appName: String,
    val summary: String,
    val body: String,
    val urgency: Urgency,
    /** The image the application sent inline, or null where it sent none. */
    val image: NotificationImage?,
    /** The icon name the application named instead, which a theme may carry. */
    val iconName: String?,
)
