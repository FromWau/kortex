package com.fromwau.kortex.bar.desktop

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kortex.bar.BarError
import com.fromwau.kortex.bar.state.FocusedWindow
import com.fromwau.kortex.bar.state.Reading
import com.fromwau.kortex.bar.state.SlotShown
import com.fromwau.kortex.bar.state.WorkspaceSlot
import com.fromwau.kortex.bar.state.WorkspaceStrip
import com.fromwau.kortex.hyprland.ActiveWindow
import com.fromwau.kortex.hyprland.HyprlandError
import com.fromwau.kortex.hyprland.KeyboardLayout
import com.fromwau.kortex.hyprland.Monitor

/**
 * The strip a bar on the monitor connected at [connector] draws.
 *
 * Its own numbered workspaces from 1 to the highest one in use, the gaps between them that no other monitor's
 * workspace fills, and the next number no monitor uses, which is the fresh workspace to go to next. In use means
 * holding a window or shown. Special and named workspaces are left out of the numbers: a special one is shown
 * over another, which [WorkspaceStrip.special] says, and a named one has no number to stand at.
 */
internal fun List<Monitor>.strip(connector: String): WorkspaceStrip {
    val mine = firstOrNull { it.name == connector }
    val numbered = mine?.workspaces.orEmpty().filter { it.id.value > 0 }.associateBy { it.id.value }
    val elsewhere = filter { it !== mine }.flatMap { it.workspaces }.map { it.id.value }.toSet()
    val inUse = numbered.values.filter { it.windows > 0 || it.id == mine?.active }.map { it.id.value }
    val last = inUse.maxOrNull() ?: 0
    val next = generateSequence(last + 1) { it + 1 }.first { it !in elsewhere && it !in numbered }

    val slots = ((1..last).filter { it !in elsewhere } + next).map { id ->
        val workspace = numbered[id]
        WorkspaceSlot(
            id = id,
            windows = workspace?.windows ?: 0,
            shown = when {
                mine == null || workspace?.id != mine.active -> SlotShown.Hidden
                mine.focused -> SlotShown.Focused
                else -> SlotShown.Visible
            },
            urgent = workspace?.urgent ?: false,
        )
    }
    val special = mine?.special?.let { id -> mine.workspaces.firstOrNull { it.id == id } }

    return WorkspaceStrip(slots = slots, special = special?.name?.removePrefix(SPECIAL_PREFIX))
}

internal fun Result<List<Monitor>, HyprlandError>.asStrip(connector: String): Reading<WorkspaceStrip> =
    asReading { it.strip(connector) }

internal fun Result<ActiveWindow?, HyprlandError>.asFocused(): Reading<FocusedWindow?> =
    asReading { window -> window?.let { FocusedWindow(appId = it.appId, title = it.title) } }

internal fun Result<String?, HyprlandError>.asSubmap(): Reading<String?> = asReading { it }

/** The layout as the bar names it: its short code where Hyprland gives one, and its full name where not. */
internal fun Result<KeyboardLayout?, HyprlandError>.asLayout(): Reading<String?> =
    asReading { layout -> layout?.let { it.code ?: it.keymap } }

/** `NotConnected` is Pending, as its own KDoc says: nobody is watching yet or the first answer is on its way. */
private inline fun <T, R> Result<T, HyprlandError>.asReading(transform: (T) -> R): Reading<R> = when (this) {
    is Ok -> Reading.Value(transform(value))
    is Err -> when (error) {
        HyprlandError.NotConnected -> Reading.Pending
        else -> Reading.Unavailable(BarError.NoHyprland(error))
    }
}

private const val SPECIAL_PREFIX = "special:"
