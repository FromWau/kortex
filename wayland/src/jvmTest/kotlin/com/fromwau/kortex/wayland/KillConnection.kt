package com.fromwau.kortex.wayland

/**
 * Ends [wayland]'s connection, and no other, with a protocol error on `wl_registry`: libwayland-server answers a bind
 * of a global it never advertised with `invalid_object`, and processes nothing the client sent after it.
 *
 * Returns once the compositor has hung up, having read nothing: the error waits unread for whatever reads next.
 */
internal fun killConnection(wayland: WaylandDisplay) {
    val unadvertised = WaylandGlobal(name = UNADVERTISED_GLOBAL, interfaceName = "wl_output", version = 1)
    val proxy = wayland.bind(unadvertised, LibWayland.outputInterface, maxVersion = 1)
    wayland.flush()
    LibWayland.proxyDestroy(proxy)
    awaitHangUp(wayland)
}

/**
 * Waits for the compositor to close its end, without reading the error it sent first.
 *
 * Without this the next requests race the close. Sent after it, they fail with EPIPE, which libwayland flushes past
 * so that the protocol error is still read. Sent before it, they sit unread in the compositor's socket when it
 * closes, the kernel marks this end ECONNRESET (`unix_release_sock` in `net/unix/af_unix.c`), and libwayland keeps
 * that as the connection's error in place of the protocol error, since it records only the first.
 */
private fun awaitHangUp(wayland: WaylandDisplay) {
    val fd = LibWayland.displayGetFd(wayland.display)
    val deadline = System.nanoTime() + HANG_UP_NANOS
    while (System.nanoTime() < deadline) {
        val revents = LibC.poll(intArrayOf(fd), intArrayOf(LibC.POLLIN), deadline).single()
        if (revents and POLLHUP != 0) return
        // The error event arrives before the hang-up and is POLLIN on its own; wait for the close itself.
        Thread.sleep(1)
    }
    error("the compositor did not hang up within ${HANG_UP_NANOS / 1_000_000} ms of the protocol error")
}

// libwayland-server names globals upward from 1, so no compositor has advertised this one.
private const val UNADVERTISED_GLOBAL = Int.MAX_VALUE

// poll(2)'s hang-up bit, reported whatever was asked for.
private const val POLLHUP = 0x010
private const val HANG_UP_NANOS = 5_000_000_000L
