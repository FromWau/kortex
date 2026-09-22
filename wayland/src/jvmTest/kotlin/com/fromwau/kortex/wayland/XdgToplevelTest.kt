package com.fromwau.kortex.wayland

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.getOrElse
import com.fromwau.kortex.compose.KortexPlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Places a real window on the compositor, which takes the user's focus and tiles into the workspace they are
 * looking at, so this class runs only in a session kept free for it.
 */
class XdgToplevelTest {
    @Test
    fun `a toplevel reaches its first configure and reports a size`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use {
            val toplevel = XdgToplevelSurface
                .create(display, TITLE, APP_ID, WIDTH, HEIGHT)
                .getOrElse { error -> fail("the toplevel was not created: $error") }

            toplevel.use {
                toplevel.waitForConfigure()
                    .getOrElse { error -> fail("the compositor never configured the toplevel: $error") }
                assertFalse(toplevel.closed, "the compositor closed the toplevel")

                assertTrue(toplevel.tiled, "the toplevel's first configure carried no tiled state")
                assertTrue(toplevel.logicalWidth > 0, "the configure left the toplevel without a width")
                assertTrue(toplevel.logicalHeight > 0, "the configure left the toplevel without a height")
            }
        }
    }

    @Test
    fun `a toplevel maps only once kortex's configure handshake completes`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use {
            onToplevel(
                display,
                // The toplevel has committed once with no buffer and acknowledged nothing, which is the one
                // moment at which the compositor cannot map it however long it waits.
                beforeConfigure = {
                    assertFalse(
                        LoopThread.waitUntil(SETTLE_WITHIN_MILLIS) { mappedWindow() != null },
                        "the compositor mapped a window that acknowledged no configure",
                    )
                },
            ) { _, surface ->
                assertTrue(surface.renders > 0, "the acknowledged toplevel never had a buffer attached")
                assertNotNull(awaitMappedWindow(), "the toplevel a buffer reached never mapped")
            }
        }
    }

    @Test
    fun `hyprctl lists the window under the title and app id the call gave`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use {
            onToplevel(display) { toplevel, _ ->
                val window = assertNotNull(awaitMappedWindow(), "hyprctl clients never listed the window")
                assertEquals(TITLE, window.title, "the window carries another title")
                assertEquals(APP_ID, window.appId, "the window carries another app id")

                assertFalse(window.floating, "hyprctl reports the window floating, so nothing tiles it")
                assertTrue(toplevel.tiled, "the states the configure carried never reached the toplevel")
            }
        }
    }

    @Test
    fun `closing a toplevel gives back every proxy and leaves the connection alive`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use {
            onToplevel(display) { _, _ -> }
            assertTrue(
                LoopThread.waitUntil(SETTLE_WITHIN_MILLIS) { mappedWindow() == null },
                "the window outlived the toplevel it was on",
            )

            // libwayland hands a destroyed proxy's id to the next object it creates, so a second toplevel is
            // built from the very ids this close gave back: one the compositor still holds fails here.
            val second = XdgToplevelSurface
                .create(display, TITLE, APP_ID, WIDTH, HEIGHT)
                .getOrElse { error -> fail("a second toplevel was not created: $error") }

            second.use {
                second.waitForConfigure()
                    .getOrElse { error -> fail("the connection did not survive the first window's close: $error") }
            }
            display.requireAlive()
                .getOrElse { error -> fail("closing the window left the connection dead: $error") }
        }
    }

    /**
     * Places a window with grey content on it and hands the toplevel and the surface around it to [block]; both
     * are closed after, whatever [block] does.
     *
     * [beforeConfigure] runs once the toplevel exists and before anything drives it to a configure.
     */
    private fun onToplevel(
        display: WaylandDisplay,
        beforeConfigure: () -> Unit = {},
        block: (toplevel: XdgToplevelSurface, surface: KortexSurface) -> Unit,
    ) {
        val loop = LoopQueue(display::wake)
        val toplevel = XdgToplevelSurface
            .create(display, TITLE, APP_ID, WIDTH, HEIGHT)
            .getOrElse { error -> fail("the toplevel was not created: $error") }
        val scene = SurfaceScene(APP_ID, loop, KortexPlatform.None, onCrash = {})

        val surface = try {
            beforeConfigure()
            KortexSurface
                .create(display, loopQueue = loop) { Ok(toplevel) }
                .getOrElse { error -> fail("the surface was not built on the toplevel: $error") }
        } catch (failure: Throwable) {
            // A close the surface already made is a no-op, and one it never reached is this test's to make.
            toplevel.close()
            scene.close()
            throw failure
        }

        try {
            surface.attach(scene).getOrElse { error -> fail("the scene was not attached to the window: $error") }
            scene.setContent { Box(Modifier.fillMaxSize().background(Color.Gray)) }
            assertTrue(
                surface.pumpOrFail(SETTLE_WITHIN_MILLIS) { surface.renders > 0 },
                "the window never drew within the settle budget",
            )
            block(toplevel, surface)
        } finally {
            // Before the scene: the seat the surface owns keeps delivering into a composition about to go.
            surface.close()
            scene.close()
        }
    }

    /** The mapped window Hyprland lists under this test's app id, or null while it lists none. */
    private fun mappedWindow(): HyprClient? = Hyprctl.clients().firstOrNull { it.appId == APP_ID }

    /** [mappedWindow] once the compositor has mapped it, or null if it never does within the budget. */
    private fun awaitMappedWindow(): HyprClient? {
        LoopThread.waitUntil(SETTLE_WITHIN_MILLIS) { mappedWindow() != null }
        return mappedWindow()
    }

    private companion object {
        const val TITLE = "kortex toplevel"
        const val APP_ID = "kortex-xdg-toplevel-test"
        const val WIDTH = 480
        const val HEIGHT = 320
        const val SETTLE_WITHIN_MILLIS = 4_000L
    }
}
