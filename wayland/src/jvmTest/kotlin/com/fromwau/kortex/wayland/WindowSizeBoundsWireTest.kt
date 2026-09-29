package com.fromwau.kortex.wayland

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * That [WindowState.askMinSize] and [WindowState.askMaxSize] reach the compositor, and that each is committed
 * where it is sent.
 *
 * Both are the only double-buffered requests kortex sends: the compositor holds them until a commit and
 * applies them then. A window drawing nothing may not commit for a long time, so a bound sent without one
 * would sit pending, and every way of reading the window back would say the same as if it had never been
 * asked for. That is what the commit leg below is for.
 *
 * Puts a window on the user's workspace, so like the other window tests this runs only in a session kept free
 * for it.
 */
class WindowSizeBoundsWireTest {
    @Test
    fun `a window asked for a size bound sends it and commits it, rather than leaving it pending`() {
        val (exitCode, output) = runProbe(
            "com.fromwau.kortex.wayland.WindowSizeBoundsProbeKt",
            environment = mapOf("WAYLAND_DEBUG" to "client"),
        )
        val raw = output.joinToString("\n")
        assertEquals(0, exitCode, "the size bounds probe exited $exitCode; output:\n$raw")

        val upAt = output.indexOfFirst { it == PROBE_MARKER_BOUNDS_WINDOW_UP }
        val askedAt = output.indexOfFirst { it == PROBE_MARKER_BOUNDS_ASKED }
        assertTrue(
            upAt >= 0 && askedAt > upAt,
            "the probe's markers are missing or out of order (up=$upAt asked=$askedAt); output:\n$raw",
        )

        // Requests alone, so "what was sent next" means what this client sent next and not what arrived in
        // between, which is what makes the commit assertions below say anything.
        val window = output.subList(upAt + 1, askedAt)
        val sent = window.filter {
            it.isRequest("xdg_toplevel", "set_min_size") ||
                it.isRequest("xdg_toplevel", "set_max_size") ||
                it.isRequest("wl_surface", "commit")
        }
        val traffic = window.traceOf("xdg_toplevel", "wl_surface")

        assertBoundThenCommit(sent, "set_min_size", PROBE_MIN_WIDTH, PROBE_MIN_HEIGHT, traffic)
        assertBoundThenCommit(sent, "set_max_size", PROBE_MAX_WIDTH, PROBE_MAX_HEIGHT, traffic)
    }

    /** That [name] went out carrying [width] by [height], and that the very next request committed it. */
    private fun assertBoundThenCommit(
        sent: List<String>,
        name: String,
        width: Int,
        height: Int,
        traffic: String,
    ) {
        val at = sent.indexOfFirst { it.isRequest("xdg_toplevel", name) }
        assertTrue(at >= 0, "the window was asked for a bound and no $name left the client; traffic:\n$traffic")

        val size = assertNotNull(
            SIZE_ARGS.find(sent[at]),
            "could not read $name's width and height from: ${sent[at]}",
        )
        // Both axes, since a bound with its axes swapped is still a bound and reads as correct everywhere else.
        assertEquals(
            listOf(width, height),
            listOf(size.groupValues[1].toIntOrNull(), size.groupValues[2].toIntOrNull()),
            "$name went out carrying some other size: ${sent[at]}",
        )
        assertTrue(
            sent.getOrNull(at + 1)?.isRequest("wl_surface", "commit") == true,
            "$name was sent and the next request was not the commit that applies it, so the bound sits " +
                "pending until the window happens to draw; traffic:\n$traffic",
        )
    }
}

// set_min_size(width, height) and set_max_size(width, height), which carry nothing else.
private val SIZE_ARGS = Regex("\\.set_m(?:in|ax)_size\\((-?\\d+),\\s*(-?\\d+)\\)")
