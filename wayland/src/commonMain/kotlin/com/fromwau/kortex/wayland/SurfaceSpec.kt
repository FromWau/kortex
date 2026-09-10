package com.fromwau.kortex.wayland

import androidx.compose.runtime.Composable

/** Which outputs a surface goes on. */
public sealed interface OutputTarget {
    /** One surface per connected `wl_output`, following outputs as they come and go. */
    public data object EveryOutput : OutputTarget

    /**
     * A single surface that names no output, leaving the compositor to place it.
     *
     * It is created once and never replaced: should the output the compositor put it on go away, the
     * surface goes with it and nothing brings it back, where an [EveryOutput] surface reappears when
     * an output is plugged in again. A host that needs one on screen after a monitor change has to ask
     * for it again.
     */
    public data object CompositorChoice : OutputTarget
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
