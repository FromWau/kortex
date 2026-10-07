package com.fromwau.kortex.bar.desktop

import com.fromwau.kortex.bar.BarError
import com.fromwau.kortex.bar.state.Reading
import com.fromwau.kortex.bar.state.TrayMenuEntry
import com.fromwau.kortex.tray.MenuItem
import com.fromwau.kortex.tray.MenuToggleState
import com.fromwau.kortex.tray.TrayError

/** The entries under [this], the menu's root, as the bar draws them: hidden ones left out. */
internal fun MenuItem.entries(): List<TrayMenuEntry> = children
    .filter { it.visible }
    .map { item ->
        TrayMenuEntry(
            id = item.id,
            label = withoutAccessKeys(item.label),
            enabled = item.enabled,
            isSeparator = item.isSeparator,
            checked = item.toggle?.let { it.state == MenuToggleState.On },
            children = item.entries(),
        )
    }

/** Why there is no menu to draw: nothing yet while it is first read, anything else said. */
internal fun TrayError.menuReading(): Reading<List<TrayMenuEntry>> = when (this) {
    TrayError.NotConnected -> Reading.Pending
    else -> Reading.Unavailable(BarError.NoTray(this))
}

/**
 * [label] without the underscores that mark an access key. Two underscores are how a label spells a real
 * one, so they come out as one.
 */
internal fun withoutAccessKeys(label: String): String = buildString {
    var index = 0
    while (index < label.length) {
        val char = label[index]
        if (char == '_' && label.getOrNull(index + 1) == '_') {
            append('_')
            index += 2
        } else {
            if (char != '_') append(char)
            index += 1
        }
    }
}
