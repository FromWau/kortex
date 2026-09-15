package com.fromwau.kortex.wayland

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
