package com.fromwau.kortex.wayland

import java.lang.foreign.MemorySegment
import kotlin.test.assertNotNull
import kotlin.test.fail

/** A bound `wl_output` and the geometry it published, for a test that must place a surface on it. */
internal class BoundOutput(val proxy: MemorySegment, val geometry: OutputGeometry)

/** Binds the first advertised `wl_output` and waits for it to publish its geometry. */
internal fun bindFirstOutput(wayland: WaylandDisplay): BoundOutput {
    val global = wayland.globals.firstOrNull { it.interfaceName == "wl_output" }
        ?: fail("no wl_output advertised to anchor against")
    val proxy = wayland.bind(global, LibWayland.outputInterface, WlVersion.OUTPUT)
    // A wl_output proxy with no listener crashes on its first event.
    val listener = OutputListener()
    listener.install(proxy)
    wayland.roundtrip()
    val geometry = assertNotNull(listener.geometry, "output ${global.name} never published geometry")
    return BoundOutput(proxy, geometry)
}

/**
 * Ends [wayland]'s connection, and no other, with a protocol error on `wl_registry`: libwayland-server answers a bind
 * of a global it never advertised with `invalid_object`, and processes nothing the client sent after it.
 */
internal fun killConnection(wayland: WaylandDisplay) {
    val unadvertised = WaylandGlobal(name = UNADVERTISED_GLOBAL, interfaceName = "wl_output", version = 1)
    val proxy = wayland.bind(unadvertised, LibWayland.outputInterface, maxVersion = 1)
    wayland.flush()
    LibWayland.proxyDestroy(proxy)
}

// libwayland-server names globals upward from 1, so no compositor has advertised this one.
private const val UNADVERTISED_GLOBAL = Int.MAX_VALUE
