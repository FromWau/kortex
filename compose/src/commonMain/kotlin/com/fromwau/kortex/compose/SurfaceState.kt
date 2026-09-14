package com.fromwau.kortex.compose

/**
 * Where a surface is in its life: [Running] from the moment its content can see it, then [Closed] or [Crashed].
 *
 * It is Compose state. Content that reads it in composition recomposes when it changes, and a `snapshotFlow` over
 * it, collected anywhere, sees each change.
 */
public sealed interface SurfaceState {
    /** The surface is up and its content runs. */
    public data object Running : SurfaceState

    /**
     * The surface has been torn down, and its content never failed. Asking it to close does not move it here: the
     * teardown that follows does.
     */
    public data object Closed : SurfaceState

    /**
     * The surface's content failed and runs no more. It moves here from [Running], or from [Closed] when content
     * fails after the teardown, and never leaves.
     *
     * @property failure the first failure its content caused, the same one its host is told of.
     */
    public data class Crashed(public val failure: ContentFailure) : SurfaceState
}
