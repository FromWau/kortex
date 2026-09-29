package com.fromwau.kortex.wayland

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.getOrElse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
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
 * Two legs, because one of them depends on a compositor lagging. `wl_compositor.release` is `since=7` and
 * Hyprland offers 6, so a real kortex call path skips it; the day a compositor offers 7 that leg stops
 * exercising the guard, and it says so rather than passing for nothing. The second leg binds low on purpose
 * and needs no compositor to lag at all.
 */
class SinceFromTableTest {
    @Test
    fun `a request the negotiated version does not carry is not sent`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use { wayland ->
            val compositor = wayland
                .require(WaylandInterface.Compositor, LibWayland.compositorInterface, WlVersion.COMPOSITOR)
                .getOrElse { error -> fail("no wl_compositor: $error") }

            // Only meaningful below the version release needs: at or past it the release is sent, legally,
            // and this leg would pass with no guard running at all.
            val negotiated = LibWayland.proxyGetVersion(compositor)
            assertTrue(
                negotiated < RELEASE_SINCE,
                "this compositor offers wl_compositor v$negotiated, at or past the v$RELEASE_SINCE that " +
                    "release needs, so this leg no longer exercises the guard; the one below still does",
            )

            releaseCompositor(compositor)
            wayland.roundtrip()

            assertEquals(
                Ok(Unit), wayland.requireAlive(),
                "releasing a wl_compositor bound below the version that request needs killed the connection",
            )
        }
    }

    /**
     * The same guard against a version this test chose, rather than one a compositor happened to offer.
     *
     * `wl_surface.set_buffer_scale` is `since=3`, so a surface made by a `wl_compositor` bound at 1 must not
     * be sent it, whatever any compositor advertises now or later.
     */
    @Test
    fun `a request newer than a deliberately low bind is skipped, whatever the compositor offers`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use { wayland ->
            val compositor = wayland
                .require(WaylandInterface.Compositor, LibWayland.compositorInterface, FORCED_LOW)
                .getOrElse { error -> fail("no wl_compositor: $error") }
            assertEquals(
                FORCED_LOW, LibWayland.proxyGetVersion(compositor),
                "the bind did not take the low version this test asked for, so it would prove nothing",
            )

            val surface =
                LibWayland.marshal(compositor, WL_COMPOSITOR_CREATE_SURFACE, LibWayland.surfaceInterface, FORCED_LOW)
            // The server answers a request below the resource's version with wl_display.error(invalid_method)
            // and destroys the client, so an unguarded marshal here takes the connection with it.
            LibWayland.marshal(surface, WL_SURFACE_SET_BUFFER_SCALE, args = listOf(WlArg.Num(SCALE)))
            LibWayland.marshal(surface, WL_SURFACE_DESTROY)
            LibWayland.proxyDestroy(surface)
            // Destroyed rather than released: the release is itself a request this bind is too old for.
            LibWayland.proxyDestroy(compositor)
            wayland.roundtrip()

            assertEquals(
                Ok(Unit), wayland.requireAlive(),
                "a request newer than the negotiated version reached the compositor and killed the connection",
            )
        }
    }

    private companion object {
        /** Below every versioned request there is, so nothing here rests on what a compositor offers. */
        const val FORCED_LOW = 1

        /** `wl_compositor.release`'s own `since`, which the first leg needs the compositor to be under. */
        const val RELEASE_SINCE = 7

        /** Anything but 1, so a scale that did reach the compositor would be one it had to act on. */
        const val SCALE = 2
    }
}
