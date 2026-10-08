package com.fromwau.kortex.bar.desktop

import com.fromwau.kortex.bar.state.TrayEntry
import com.fromwau.kortex.tray.TrayItem
import com.fromwau.kortex.tray.TrayStatus
import com.fromwau.kortex.tray.label

/**
 * [this] as the bar draws it, carrying the icon's description rather than its pixels.
 *
 * An item that asked to be noticed gets its attention icon, which is the one case where the icon to draw
 * is not the ordinary one. Everything else about an icon, the theme search and the decode, belongs to
 * `:icons` at the point of drawing.
 */
internal fun TrayItem.asEntry(): TrayEntry {
    val attention = status == TrayStatus.NeedsAttention

    return TrayEntry(
        address = address,
        id = id,
        label = label,
        icon = if (attention) attentionIcon else icon,
        needsAttention = attention,
        hasMenu = menuPath != null,
        isMenu = isMenu,
    )
}
