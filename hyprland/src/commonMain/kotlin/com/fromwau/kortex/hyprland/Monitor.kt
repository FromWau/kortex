package com.fromwau.kortex.hyprland

/**
 * One monitor, as Hyprland arranges workspaces on it.
 *
 * Only what Hyprland itself decides, each changed by an event it sends. Its size, scale and position are left to
 * `:wayland`'s own monitor, which follows them from the protocol: Hyprland sends no event when they change.
 */
public data class Monitor(
    public val id: Int,
    /** The connector, `HDMI-A-2` or `DP-1`, which is also what `:wayland` calls the same monitor. */
    public val name: String,
    /** Make, model and serial, as the monitor reports them. */
    public val description: String,
    /** The monitor with focus, which is one at a time. */
    public val focused: Boolean,
    /** Every workspace on it, special ones included, ordered by id. */
    public val workspaces: List<Workspace>,
    /** The workspace it shows, one of [workspaces]. */
    public val active: WorkspaceId,
    /** The special workspace open over [active], one of [workspaces], or null while none is. */
    public val special: WorkspaceId?,
)

/** The layout the main keyboard types in. */
public data class KeyboardLayout(
    /** The keyboard Hyprland calls main, which is the one a layout switch applies to. */
    public val keyboard: String,
    /** The layout's full name, `English (US)`. */
    public val keymap: String,
    /** The layout's short code, `us`, or null where Hyprland does not say which of the keyboard's layouts is on. */
    public val code: String?,
)
