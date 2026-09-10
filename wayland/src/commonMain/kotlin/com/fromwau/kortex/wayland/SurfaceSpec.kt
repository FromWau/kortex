package com.fromwau.kortex.wayland

import androidx.compose.runtime.Composable

/** Which outputs a surface goes on. */
public sealed interface OutputTarget {
    /** One surface per connected `wl_output`, following outputs as they come and go. */
    public data object EveryOutput : OutputTarget

    /**
     * A single surface that names no output, leaving the compositor to place it.
     *
     * A spec passed to [runSurfaces] or [KortexShell.create] is placed again once the compositor takes
     * its surface away.
     * Content dismissing its own surface stays gone instead, and so does one placed through
     * [KortexHost.open]: neither counts as the compositor taking it away.
     *
     * Two limitations follow. With no output at all connected at the moment the compositor takes the
     * surface, there is nowhere to place the replacement and it stays gone until asked for again.
     * Should placing the replacement itself fail, the run drops it and carries on rather than taking
     * the host down with it: that failure comes from a connection which may already be going down. If
     * the dropped surface was the last one, the run then ends the way an empty screen always ends it.
     */
    public data object CompositorChoice : OutputTarget

    /**
     * A single surface on the output that `wl_output.name` calls [name], e.g. "DP-1", the same string
     * `hyprctl monitors` prints.
     *
     * Placed once that output is connected, and dropped when it goes away like any per-output surface.
     * Should the named output not be connected when this is placed, nothing is placed and nothing
     * fails: the output going away between the click that named it and the placement is a lost race,
     * not a programming error. A standing spec is placed later if the output arrives; a one-shot
     * [KortexHost.open] for an output that never arrives simply never shows.
     */
    public data class NamedOutput(public val name: String) : OutputTarget
}

/**
 * One surface a host asks for: what kind it is, where it goes, and what it draws.
 *
 * @property config what kind of surface to put on screen.
 * @property target which outputs to put it on.
 * @property content the composition drawn on it, run once per surface [target] produces.
 */
public data class SurfaceSpec(
    public val config: SurfaceConfig,
    public val target: OutputTarget = OutputTarget.EveryOutput,
    public val content: @Composable () -> Unit,
)
