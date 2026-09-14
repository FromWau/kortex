package com.fromwau.kortex.compose

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.IntSize

/**
 * The surface a composition is drawn onto, and the one thing it can do about its own lifetime.
 *
 * [size] is the surface's logical (surface-local) size — the space a configure reports, not the
 * buffer/physical pixels, which are larger than this by the output scale on a HiDPI output.
 */
public interface KortexSurfaceHandle {
    /** Reading it during composition recomposes the reader on the next configure that changes it. */
    public val size: IntSize

    /**
     * Where this surface is in its life. Reading it during composition recomposes the reader when it changes, and a
     * `snapshotFlow` over it, collected anywhere, follows it.
     *
     * Content's own composition, in practice, only ever reads [SurfaceState.Running]: by the time the state moves
     * on, the surface is gone or its content has failed. [SurfaceState.Closed] and [SurfaceState.Crashed] reach code
     * that outlives the content: the host, another surface's content, or a coroutine outside the composition.
     * [close] leaves it Running until the surface has been removed.
     */
    public val state: SurfaceState

    /** Dismisses the surface. Safe to call more than once, and after it has already gone away. */
    public fun close()
}

/** Provided by the host around the content it draws; absent means content is running outside one. */
public val LocalKortexSurface: ProvidableCompositionLocal<KortexSurfaceHandle> =
    staticCompositionLocalOf { error("LocalKortexSurface is not provided outside a host's content") }
