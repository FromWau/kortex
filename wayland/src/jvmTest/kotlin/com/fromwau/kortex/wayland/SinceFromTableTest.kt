package com.fromwau.kortex.wayland

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.getOrElse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * That a request is skipped when the negotiated version does not carry it, read off the request's own table
 * entry rather than named at the call site.
 *
 * libwayland writes the version into the leading digits of a signature and reads it back with `atoi`
 * (`wl_message_get_since`, src/connection.c); only its server side compares that against the resource's
 * version (wayland-server.c), and answers a request below it with `wl_display.error(invalid_method)`, which
 * destroys the client. So the client has to make the same comparison, and kortex makes it once in `marshal`.
 *
 * `wl_compositor.release` is the case that matters here: it is `since=7`, and Hyprland offers 6, so it must
 * not be sent and the connection must survive binding and dropping a compositor.
 */
class SinceFromTableTest {
    @Test
    fun `a request the negotiated version does not carry is not sent`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use { wayland ->
            val compositor = wayland
                .require("wl_compositor", LibWayland.compositorInterface, WlVersion.COMPOSITOR)
                .getOrElse { error -> fail("no wl_compositor: $error") }

            // Whatever the compositor offers: below 7 the release must be skipped, at 7 it must be sent, and
            // either way what must not happen is a protocol error.
            releaseCompositor(compositor)
            wayland.roundtrip()

            assertEquals(
                Ok(Unit), wayland.requireAlive(),
                "releasing a wl_compositor bound below the version that request needs killed the connection",
            )
        }
    }
}
