package com.fromwau.kortex.wayland

import java.lang.foreign.MemorySegment

// Destructors for the core globals a surface or the shell binds one of its own of, kept together
// because each pairs an opcode with the version it first exists at, and sending one past that version
// kills the connection.

/** Gives a `wl_compositor` proxy back and frees it; a layer surface and a cursor surface each bind one. */
internal fun releaseCompositor(compositor: MemorySegment) {
    LibWayland.marshalIfSince(compositor, WL_COMPOSITOR_RELEASE, WL_COMPOSITOR_RELEASE_SINCE)
    LibWayland.proxyDestroy(compositor)
}

private const val WL_COMPOSITOR_RELEASE = 2
private const val WL_COMPOSITOR_RELEASE_SINCE = 7

/** Gives a `wl_shm` proxy back and frees it; both surfaces and cursor themes bind one of their own. */
internal fun releaseShm(shm: MemorySegment) {
    LibWayland.marshalIfSince(shm, WL_SHM_RELEASE, WL_SHM_RELEASE_SINCE)
    LibWayland.proxyDestroy(shm)
}

private const val WL_SHM_RELEASE = 1
private const val WL_SHM_RELEASE_SINCE = 2

/** Gives a `wl_output` proxy back and frees it; `KortexShell` binds one per connected output. */
internal fun releaseOutput(output: MemorySegment) {
    LibWayland.marshalIfSince(output, WL_OUTPUT_RELEASE, WL_OUTPUT_RELEASE_SINCE)
    LibWayland.proxyDestroy(output)
}

private const val WL_OUTPUT_RELEASE = 0
private const val WL_OUTPUT_RELEASE_SINCE = 3
