package com.fromwau.kortex.hyprland

import kotlin.jvm.JvmInline

/** What Hyprland numbers a workspace by: negative for special and for named workspaces alike. */
@JvmInline
public value class WorkspaceId(public val value: Int)

/** What Hyprland calls a window, which is another one as soon as the window is made again. */
@JvmInline
public value class WindowAddress(public val value: String)

/** Every workspace there is, and which one each monitor is showing. */
public data class Workspaces(
    /** Ordered by id, so a strip drawn from it keeps its order however Hyprland lists them. */
    public val all: List<Workspace>,
    /** The workspace each monitor shows, by the monitor's connector name, `HDMI-A-2` or `DP-1`. */
    public val active: Map<String, WorkspaceId>,
    /** The monitor that has focus, which is null only while Hyprland has no monitor at all. */
    public val focusedMonitor: String?,
)

/** One workspace, which Hyprland only keeps while it has a window on it or a monitor showing it. */
public data class Workspace(
    public val id: WorkspaceId,
    /** The id written out, unless the workspace was named or renamed: `special:magic` for a special one. */
    public val name: String,
    /** The connector name of the monitor it is on. */
    public val monitor: String,
    public val windows: Int,
) {
    /** A scratchpad workspace, shown over another one rather than in its place. */
    public val special: Boolean get() = id.value in SPECIAL_IDS
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
