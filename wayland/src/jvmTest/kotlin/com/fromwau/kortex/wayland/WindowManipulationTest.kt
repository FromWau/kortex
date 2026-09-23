package com.fromwau.kortex.wayland

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Hands a real window to the compositor's own dispatchers, which re-tile the windows the user has open around it
 * and warp their pointer onto it, so this class runs only in a session kept free for it.
 */
class WindowManipulationTest {
    @Test
    fun `a window the compositor floats keeps its content and the size it was given`() {
        onWindow { placed ->
            Hyprctl.dispatch("window.float", address = placed.window.address)

            placed.awaitFloating()
            placed.assertWhole("after the compositor floated it")
        }
    }

    @Test
    fun `a window the compositor gives the whole screen reports it and keeps its content`() {
        onWindow { placed ->
            assertFalse(placed.state.fullscreen, "the window reports the whole screen before anything gave it one")

            Hyprctl.dispatch("window.fullscreen", address = placed.window.address)

            assertTrue(
                placed.shell.pumpOrFail(PUMP_MILLIS) { placed.state.fullscreen },
                "the compositor gave the window the whole screen and its state never said so",
            )
            assertEquals(
                true, Hyprctl.window(TITLE)?.fullscreen,
                "the window reports a whole screen the compositor does not list it as having",
            )
            placed.assertWhole("while the compositor held it full screen")

            Hyprctl.dispatch("window.fullscreen", address = placed.window.address)

            assertTrue(
                placed.shell.pumpOrFail(PUMP_MILLIS) { !placed.state.fullscreen },
                "the window still reports the whole screen after the compositor took it back",
            )
            placed.assertWhole("after the compositor took the whole screen back")
        }
    }

    @Test
    fun `a window the compositor pins keeps its content and the size it was given`() {
        onWindow { placed ->
            // A compositor pins only a window it is not tiling, so the pin needs the float in front of it.
            Hyprctl.dispatch("window.float", address = placed.window.address)
            placed.awaitFloating()

            Hyprctl.dispatch("window.pin", address = placed.window.address)

            assertTrue(
                placed.shell.pumpOrFail(PUMP_MILLIS) { Hyprctl.window(TITLE)?.pinned == true },
                "the compositor was asked to pin the window and does not list it pinned",
            )
            placed.assertWhole("after the compositor pinned it")
        }
    }

    @Test
    fun `a window the compositor moves through the layout takes its neighbour's place and keeps its content`() {
        onTwoWindows { placed, neighbour ->
            val at = placed.window.at

            Hyprctl.dispatch(
                "window.move", placed.window.directionToward(neighbour).field,
                address = placed.window.address,
            )

            placed.shell.pumpOrFail(PUMP_MILLIS) { Hyprctl.window(TITLE)?.at == neighbour.at }
            assertEquals(
                neighbour.at, Hyprctl.window(TITLE)?.at,
                "the compositor was asked to move the window past the one beside it and never moved it",
            )
            assertEquals(
                at, Hyprctl.window(NEIGHBOUR_TITLE)?.at,
                "the window moved past never took the place of the one moved",
            )
            placed.assertWhole("after the compositor moved it through the layout")
        }
    }

    @Test
    fun `a window the compositor moves to another workspace keeps its content`() {
        onWindow { placed ->
            Hyprctl.dispatch(
                "window.move", "workspace = \"$ASIDE\"", "follow = false",
                address = placed.window.address,
            )

            assertTrue(
                placed.shell.pumpOrFail(PUMP_MILLIS) { Hyprctl.window(TITLE)?.workspace == ASIDE },
                "the compositor was asked to move the window aside and still lists it where it was",
            )
            placed.assertWhole("after the compositor moved it to another workspace")
        }
    }

    @Test
    fun `a window the compositor swaps with another keeps its content`() {
        onTwoWindows { placed, neighbour ->
            val at = placed.window.at

            Hyprctl.dispatch(
                "window.swap", "target = \"address:${neighbour.address}\"",
                address = placed.window.address,
            )

            placed.shell.pumpOrFail(PUMP_MILLIS) { Hyprctl.window(TITLE)?.at == neighbour.at }
            assertEquals(
                neighbour.at, Hyprctl.window(TITLE)?.at,
                "the compositor was asked to swap the windows and never moved this one",
            )
            assertEquals(
                at, Hyprctl.window(NEIGHBOUR_TITLE)?.at,
                "the window swapped with never took the place of the one swapped",
            )
            placed.assertWhole("after the compositor swapped it with another window")
        }
    }

    @Test
    fun `a window the compositor resizes reports the size it was given and keeps its content`() {
        onWindow { placed ->
            // Floating first: while it tiles a window, a compositor answers a resize with whatever the layout
            // around it leaves over, which is a number this test cannot know.
            Hyprctl.dispatch("window.float", address = placed.window.address)
            placed.awaitFloating()
            placed.shell.pumpOrFail(SETTLE_MILLIS)
            val before = assertNotNull(Hyprctl.window(TITLE), "the floating window went off screen").size

            Hyprctl.dispatch(
                "window.resize", "x = $GROW_BY", "y = $GROW_BY", "relative = true",
                address = placed.window.address,
            )

            val grown = IntSize(before.width + GROW_BY, before.height + GROW_BY)
            placed.shell.pumpOrFail(PUMP_MILLIS) { Hyprctl.window(TITLE)?.size == grown }
            assertEquals(
                grown, Hyprctl.window(TITLE)?.size,
                "the compositor was asked to grow the window and never grew it",
            )
            placed.assertWhole("after the compositor resized it")
        }
    }

    @Test
    fun `a floating window is drawn at the size its call asks for and the compositor keeps its own`() {
        val width = mutableStateOf(WIDTH)
        val state = WindowState()
        val watch = Watch()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            Window(title = TITLE, appId = APP_ID, width = width.value, height = HEIGHT, state = state) {
                Watched(watch)
            }
        }

        onApplication(content) { shell ->
            awaitPlaced(shell)
            val window = awaitWindow(shell, TITLE)
            val placed = Placed(shell, state, watch, window)

            Hyprctl.dispatch("window.float", address = window.address)
            placed.awaitFloating()
            // The float's own configure lands after the layout change; everything below is about no further one.
            shell.pumpOrFail(SETTLE_MILLIS)
            val given = assertNotNull(Hyprctl.window(TITLE), "the floating window went off screen").size
            assertNotEquals(NARROW_SIZE, given, "the compositor gave the window the size this test asks for")

            width.value = NARROW_WIDTH

            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { state.status == WindowStatus.OnScreen(NARROW_SIZE) },
                "the changed width never reached the window, which reports ${state.status}",
            )
            shell.pumpOrFail(SETTLE_MILLIS)

            assertEquals(
                given, Hyprctl.window(TITLE)?.size,
                "the compositor took the size the window drew itself at as the window's own",
            )
        }
    }

    @Test
    fun `tiled and maximized report what the compositor set as it mapped the window, floating included`() {
        onWindow { placed ->
            // The states are pushed at the toplevel's construction but only reach a client on a configure
            // scheduled for a later turn of the compositor's loop, so the one that maps the window carries none.
            assertTrue(
                placed.shell.pumpOrFail(PUMP_MILLIS) { placed.state.tiled && placed.state.maximized },
                "the window the compositor placed into its layout and maximized reports neither",
            )

            Hyprctl.dispatch("window.float", address = placed.window.address)
            placed.awaitFloating()
            placed.shell.pumpOrFail(SETTLE_MILLIS)

            assertTrue(placed.state.tiled, "the window stopped reporting a state the compositor never took back")
            assertTrue(
                placed.state.maximized,
                "the window stopped reporting a maximized the compositor never took back",
            )
        }
    }

    /** One placed window: the shell driving it, what it publishes, what its content holds, and hyprctl's view. */
    private class Placed(
        val shell: KortexShell,
        val state: WindowState,
        val watch: Watch,
        val window: HyprWindow,
    )

    /** Drives the shell until the compositor lists the window as one it no longer tiles. */
    private fun Placed.awaitFloating() {
        assertTrue(
            shell.pumpOrFail(PUMP_MILLIS) { Hyprctl.window(window.title)?.floating == true },
            "the compositor was asked to float the window and still lists it tiled",
        )
    }

    /**
     * Fails unless the window is still the one that was placed, its content is untouched, and its state reports
     * the size the compositor gives it.
     */
    private fun Placed.assertWhole(after: String) {
        val listed = assertNotNull(Hyprctl.window(window.title), "hyprctl no longer lists the window $after")
        assertEquals(window.address, listed.address, "the window was placed again $after")
        assertEquals(1, watch.compositions.get(), "the content was composed again from scratch $after")
        assertEquals(1, watch.effects.get(), "the content's effect was started a second time $after")
        assertEquals(1, watch.held.get(), "the content lost what it held behind remember $after")

        val ticks = watch.ticks.get()
        assertTrue(
            shell.pumpOrFail(PUMP_MILLIS) { watch.ticks.get() >= ticks + TICKS },
            "the effect running in the content stopped $after",
        )
        assertTrue(
            shell.pumpOrFail(PUMP_MILLIS) { state.agreesWith(Hyprctl.window(window.title)) },
            "the window reports ${state.status} $after, where the compositor gives it its own size",
        )
    }

    /** Whether the window's own state and hyprctl agree on the size the compositor gave it. */
    private fun WindowState.agreesWith(listed: HyprWindow?): Boolean =
        listed != null && status == WindowStatus.OnScreen(listed.size)

    /** Places one window whose content holds a counter and runs an effect, and hands it to [block]. */
    private fun onWindow(block: (Placed) -> Unit) {
        val state = WindowState()
        val watch = Watch()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            Window(title = TITLE, appId = APP_ID, width = WIDTH, height = HEIGHT, state = state) { Watched(watch) }
        }

        onApplication(content) { shell ->
            awaitPlaced(shell)
            block(Placed(shell, state, watch, awaitWindow(shell, TITLE)))
        }
    }

    /** [onWindow] with a second window of its own beside it, for a dispatcher that needs two to act on. */
    private fun onTwoWindows(block: (Placed, HyprWindow) -> Unit) {
        val state = WindowState()
        val watch = Watch()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            Window(title = TITLE, appId = APP_ID, width = WIDTH, height = HEIGHT, state = state) { Watched(watch) }
            Window(title = NEIGHBOUR_TITLE, appId = APP_ID, width = WIDTH, height = HEIGHT) { Grey() }
        }

        onApplication(content) { shell ->
            awaitPlaced(shell, count = 2)
            block(
                Placed(shell, state, watch, awaitWindow(shell, TITLE)),
                awaitWindow(shell, NEIGHBOUR_TITLE),
            )
        }
    }

    /** The window once hyprctl lists it under [title], driving [shell] until it does. */
    private fun awaitWindow(shell: KortexShell, title: String): HyprWindow {
        assertTrue(
            shell.pumpOrFail(PUMP_MILLIS) { Hyprctl.window(title) != null },
            "hyprctl clients never listed a window titled \"$title\"",
        )
        return assertNotNull(Hyprctl.window(title), "the window hyprctl listed was gone again a moment later")
    }

    /** The way from this window toward [neighbour], which is the axis the layout split the two along. */
    private fun HyprWindow.directionToward(neighbour: HyprWindow): Direction = when {
        neighbour.at.x < at.x -> Direction.Left
        neighbour.at.x > at.x -> Direction.Right
        neighbour.at.y < at.y -> Direction.Up
        else -> Direction.Down
    }

    private companion object {
        const val TITLE = "kortex manipulated window"
        const val NEIGHBOUR_TITLE = "kortex manipulated window's neighbour"
        const val APP_ID = "kortex-window-manipulation-test"

        /** A workspace of the window's own, which the user is not looking at and which goes when it does. */
        const val ASIDE = "special:kortex-window-manipulation-test"

        /** Logical pixels to grow the window by on each axis, far enough out that no rounding hides it. */
        const val GROW_BY = 60

        val WIDTH = 640.dp
        val HEIGHT = 480.dp
        val NARROW_WIDTH = 320.dp

        /** What the call asks for once its width has changed, which is what content is drawn at. */
        val NARROW_SIZE = IntSize(NARROW_WIDTH.toLogicalPx(), HEIGHT.toLogicalPx())

        const val PUMP_MILLIS = 4_000L
        const val SETTLE_MILLIS = 1_000L
        const val TICKS = 3
    }
}
