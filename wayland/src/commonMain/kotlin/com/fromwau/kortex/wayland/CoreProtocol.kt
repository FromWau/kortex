package com.fromwau.kortex.wayland

import java.lang.foreign.MemorySegment

// The core-global opcodes every role in this package sends, in one place because each is the wire number a
// request is read as: a second copy that drifts from the protocol is a message the compositor misreads.

internal const val WL_COMPOSITOR_CREATE_SURFACE = 0

internal const val WL_SURFACE_DESTROY = 0
internal const val WL_SURFACE_ATTACH = 1
internal const val WL_SURFACE_FRAME = 3
internal const val WL_SURFACE_COMMIT = 6
internal const val WL_SURFACE_SET_BUFFER_SCALE = 8
internal const val WL_SURFACE_DAMAGE_BUFFER = 9

/** Attaches [buffer] at the surface's origin and marks the whole of it damaged, as every surface role draws. */
internal fun attachWholeBuffer(surface: MemorySegment, buffer: ShmBuffer) {
    LibWayland.marshal(
        surface, WL_SURFACE_ATTACH,
        args = listOf(WlArg.Ptr(buffer.buffer), WlArg.Num(0), WlArg.Num(0)),
    )
    LibWayland.marshal(
        surface, WL_SURFACE_DAMAGE_BUFFER,
        args = listOf(WlArg.Num(0), WlArg.Num(0), WlArg.Num(buffer.width), WlArg.Num(buffer.height)),
    )
}

// Destructors for core globals: each pairs an opcode with the version it first exists at, and sending one
// past that version kills the connection.

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
