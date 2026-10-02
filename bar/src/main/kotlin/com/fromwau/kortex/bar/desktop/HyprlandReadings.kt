package com.fromwau.kortex.bar.desktop

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kortex.bar.BarError
import com.fromwau.kortex.bar.state.FocusedWindow
import com.fromwau.kortex.bar.state.Reading
import com.fromwau.kortex.bar.state.SlotShown
import com.fromwau.kortex.bar.state.WorkspaceSlot
import com.fromwau.kortex.hyprland.ActiveWindow
import com.fromwau.kortex.hyprland.HyprlandError
import com.fromwau.kortex.hyprland.Workspaces

/**
 * Every numbered workspace from 1 to the highest one in use, gaps included, and one empty slot past it.
 *
 * In use means holding a window or shown on a monitor. A gap is drawn because it is where a person counts
 * to, and the slot past the end is the fresh workspace to go to next. Special and named workspaces are
 * left out: a special one is shown over another rather than in a place of its own, and a named one has no
 * number to stand in the sequence at.
 */
internal fun Workspaces.strip(): List<WorkspaceSlot> {
    val numbered = all.filter { it.id.value > 0 }.associateBy { it.id.value }
    val shownOn = active.entries.associate { (monitor, id) -> id.value to monitor }
    val inUse = numbered.values.filter { it.windows > 0 }.map { it.id.value } + shownOn.keys.filter { it > 0 }
    val last = (inUse.maxOrNull() ?: 0) + 1

    return (1..last).map { id ->
        WorkspaceSlot(
            id = id,
            windows = numbered[id]?.windows ?: 0,
            shown = when (shownOn[id]) {
                null -> SlotShown.Hidden
                focusedMonitor -> SlotShown.Focused
                else -> SlotShown.Visible
            },
        )
    }
}

internal fun Result<Workspaces, HyprlandError>.asStrip(): Reading<List<WorkspaceSlot>> = asReading { it.strip() }

internal fun Result<ActiveWindow?, HyprlandError>.asFocused(): Reading<FocusedWindow?> =
    asReading { window -> window?.let { FocusedWindow(appId = it.appId, title = it.title) } }

/** `NotConnected` is Pending, as its own KDoc says: nobody is watching yet or the first answer is on its way. */
private inline fun <T, R> Result<T, HyprlandError>.asReading(transform: (T) -> R): Reading<R> = when (this) {
    is Ok -> Reading.Value(transform(value))
    is Err -> when (error) {
        HyprlandError.NotConnected -> Reading.Pending
        else -> Reading.Unavailable(BarError.NoHyprland(error))
    }
}
