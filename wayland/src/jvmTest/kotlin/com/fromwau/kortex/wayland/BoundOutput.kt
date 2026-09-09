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
