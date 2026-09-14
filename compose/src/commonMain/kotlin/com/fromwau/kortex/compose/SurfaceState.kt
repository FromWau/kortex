package com.fromwau.kortex.compose

/** Where a surface is in its life: [Running] from the moment its content can see it, then [Closed] or [Crashed]. */
public sealed interface SurfaceState {
    /** The surface is up and its content runs. */
    public data object Running : SurfaceState

    /**
     * The surface is gone, and its content had not failed by then. Its content or its host closed it, the
     * compositor closed it, or its output went away. [KortexSurfaceHandle.close] only asks for this, so the state
     * reads [Running] until the surface has been removed.
     *
     * Content that fails after its surface is gone, from a coroutine that outlives it, moves the state on to
     * [Crashed]. Content that fails while its surface is being removed, such as an `onDispose` that throws, moves
     * it to [Crashed] without passing through Closed. So Closed is not always the last state a surface reads, and a
     * surface can go without ever reading it.
     */
    public data object Closed : SurfaceState

    /**
     * The surface's content failed and runs no more. It moves here from [Running], or from [Closed] when content
     * fails after its surface is gone, and never leaves.
     *
     * @property failure the first failure its content caused; its host is told of it as well.
     */
    public data class Crashed(public val failure: ContentFailure) : SurfaceState
}
