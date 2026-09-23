package com.fromwau.kortex.wayland

import com.fromwau.kortex.compose.KortexSurfaceHandle

/**
 * The surface a piece of content is drawn on, as its own `this`: its `size`, the logical size the compositor last
 * gave it or `IntSize.Zero` once it has ended, and `close()`, which ends it with `Ok(SurfaceEnd.Closed)`.
 */
public interface SurfaceScope : KortexSurfaceHandle
