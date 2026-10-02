package com.fromwau.kortex.hyprland

import kotlin.jvm.JvmInline

/** What Hyprland numbers a workspace by: negative for special and for named workspaces alike. */
@JvmInline
public value class WorkspaceId(public val value: Int)

/** What Hyprland calls a window, which is another one as soon as the window is made again. */
@JvmInline
public value class WindowAddress(public val value: String)

/** One workspace, which Hyprland only keeps while it has a window on it or a monitor showing it. */
public data class Workspace(
    public val id: WorkspaceId,
    /** The id written out, unless the workspace was named or renamed: `special:magic` for a special one. */
    public val name: String,
    public val windows: Int,
    /**
     * One of its windows asked for attention and has not had focus since.
     *
     * Only what happened while somebody was collecting: Hyprland answers no query with it, so a window that asked
     * before then is not known to have.
     */
    public val urgent: Boolean,
) {
    /** A scratchpad workspace, shown over another one rather than in its place. */
    public val special: Boolean get() = id.value in SPECIAL_IDS
}

/** How Hyprland's workspace selector names this workspace: its number, or its name where it has no number. */
internal fun Workspace.selector(): String = when {
    special -> name
    id.value < 0 -> "name:$name"
    else -> "${id.value}"
}

/** [text] as a Lua string literal, so a workspace name with a quote in it stays one string. */
internal fun luaString(text: String): String = buildString {
    append('"')
    text.forEach { char ->
        when (char) {
            '\\', '"' -> append('\\').append(char)
            '\n' -> append("\\n")
            else -> append(char)
        }
    }
    append('"')
}

// Hyprland's own CWorkspaceQueryCore::isSpecial. Named workspaces are negative too, from -1337 down, so a
// sign test would call every `name:` workspace special.
private val SPECIAL_IDS = -99..-2

/** The window with keyboard focus. */
public data class ActiveWindow(
    public val address: WindowAddress,
    /** What Hyprland calls the window's class, which for a Wayland window is its `xdg_toplevel` app id. */
    public val appId: String,
    public val title: String,
    public val workspace: WorkspaceId,
)
