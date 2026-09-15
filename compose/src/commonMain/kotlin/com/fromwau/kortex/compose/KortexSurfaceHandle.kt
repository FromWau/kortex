package com.fromwau.kortex.compose

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.IntSize

/**
 * The surface a composition is drawn onto, and the one thing it can do about its own lifetime.
 *
 * [size] is the surface's logical (surface-local) size, not its size in physical pixels, which is larger by the
 * monitor's scale on a HiDPI monitor.
 */
public interface KortexSurfaceHandle {
    /** Reading it during composition recomposes the reader when it changes. */
    public val size: IntSize

    /** Dismisses the surface. Safe to call more than once, and after it has already gone away. */
    public fun close()
}

/**
 * The surface content is drawn on, provided around each surface's content. Reading it anywhere else throws
 * `IllegalStateException`.
 */
public val LocalKortexSurface: ProvidableCompositionLocal<KortexSurfaceHandle> =
    staticCompositionLocalOf { error("LocalKortexSurface is provided only inside a surface's content") }
