package com.fromwau.kortex.wayland

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The running shell, as a surface's own content can reach it: the output that surface landed on, the
 * one way content can put another surface on screen, and the desktop's clipboard.
 */
public interface KortexHost {
    /**
     * The output this composition's own surface is on, backed by snapshot state: content that reads it
     * recomposes when that geometry changes, which may happen at any time, since a re-sent scale or
     * mode replaces the whole geometry.
     *
     * Null for an [OutputTarget.CompositorChoice] surface, since the shell never learns which output the
     * compositor chose, and null until that output's first geometry arrives.
     */
    public val output: OutputGeometry?

    /**
     * Puts [spec] on screen the next time the shell applies pending work. Placed once: nothing
     * later replays [spec], so an output arriving afterward does not put it there too.
     *
     * Returns immediately, before the surface exists. By the time the shell places it there is no
     * caller left to hand a failure back to, so a [spec] the protocol will not accept, such as one
     * whose config omits an axis it has no anchor to span, takes the host down instead.
     */
    public fun open(spec: SurfaceSpec)

    /**
     * The desktop's clipboard, shared by every surface of the shell. Call it rather than Compose's
     * `LocalClipboard` when content needs to know why a copy or paste failed.
     */
    public val clipboard: KortexClipboard
}

/** Provided by the shell around each surface's own content; absent means content outside a running shell. */
public val LocalKortexHost: ProvidableCompositionLocal<KortexHost> =
    staticCompositionLocalOf { error("LocalKortexHost is not provided outside a running shell") }
