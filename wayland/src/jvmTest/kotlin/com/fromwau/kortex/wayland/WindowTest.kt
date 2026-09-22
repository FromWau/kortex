package com.fromwau.kortex.wayland

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.Ok
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.delay

/**
 * Places a real window on the compositor, which takes the user's focus and tiles into the workspace they are
 * looking at, so this class runs only in a session kept free for it.
 */
class WindowTest {
    @Test
    fun `a window reaches the screen and hyprctl lists it under its title`() {
        val state = WindowState()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            Window(title = TITLE, appId = APP_ID, width = WIDTH, height = HEIGHT, state = state) { Grey() }
        }

        onApplication(content) { shell ->
            awaitPlaced(shell)

            val onScreen = assertIs<WindowStatus.OnScreen>(
                state.status,
                "the window was placed and its state reports ${state.status}",
            )
            assertTrue(onScreen.size.width > 0 && onScreen.size.height > 0, "the window on screen reports no size")

            val window = awaitWindow(shell, TITLE)
            assertEquals(APP_ID, window.appId, "hyprctl lists the window under another app id")
        }
    }

    @Test
    fun `content is drawn at the size the compositor gave the window, not the size the call asked for`() {
        val seen = AtomicReference<IntSize?>(null)
        val state = WindowState()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            Window(title = TITLE, appId = APP_ID, width = WIDTH, height = HEIGHT, state = state) {
                val drawnAt = size
                SideEffect { seen.set(drawnAt) }
                Grey()
            }
        }

        onApplication(content) { shell ->
            awaitPlaced(shell)
            awaitWindow(shell, TITLE)

            val configured = shell.shownSurfaces.single().logicalSize
            assertNotEquals(ASKED_SIZE, configured, "the compositor left the window at the size the call asked for")
            assertEquals(configured, seen.get(), "content was drawn at a size the compositor never configured")
            assertEquals(
                WindowStatus.OnScreen(configured), state.status,
                "the window reports a size other than the one its content is drawn at",
            )
        }
    }

    @Test
    fun `a changed title reaches the window on screen and no other window takes its place`() {
        val title = mutableStateOf(TITLE)
        val state = WindowState()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            Window(title = title.value, appId = APP_ID, width = WIDTH, height = HEIGHT, state = state) { Grey() }
        }

        onApplication(content) { shell ->
            awaitPlaced(shell)
            val before = awaitWindow(shell, TITLE)
            val placed = shell.shownSurfaces.single()

            title.value = SECOND_TITLE

            val after = awaitWindow(shell, SECOND_TITLE)
            assertEquals(
                before.address, after.address,
                "the compositor holds another window, so the title was carried by making a second one",
            )
            assertSame(placed, shell.shownSurfaces.single(), "the call was put on a new surface to carry its title")
            assertIs<WindowStatus.OnScreen>(state.status, "the changed title left the window ${state.status}")
        }
    }

    @Test
    fun `a call leaving composition ends its window and takes it off screen`() {
        val showing = mutableStateOf(true)
        val state = WindowState()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            if (showing.value) {
                Window(title = TITLE, appId = APP_ID, width = WIDTH, height = HEIGHT, state = state) { Grey() }
            }
        }

        onApplication(content) { shell ->
            awaitPlaced(shell)
            awaitWindow(shell, TITLE)

            showing.value = false

            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { state.status is WindowStatus.Ended },
                "the call left composition and its window never ended",
            )
            assertEquals(
                WindowStatus.Ended(Ok(SurfaceEnd.LeftComposition)), state.status,
                "the window whose call left composition ended some other way",
            )
            assertTrue(shell.shownSurfaces.isEmpty(), "the ended window is still held by the shell")
            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { listedWindow() == null },
                "hyprctl clients still lists the window whose call left composition",
            )
        }
    }

    @Test
    fun `a window's content keeps what it holds and keeps its effect running across a title change`() {
        val title = mutableStateOf(TITLE)
        val watch = Watch()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            Window(title = title.value, appId = APP_ID, width = WIDTH, height = HEIGHT) {
                val held = remember { watch.compositions.incrementAndGet() }
                LaunchedEffect(Unit) {
                    watch.effects.incrementAndGet()
                    while (true) {
                        watch.held.set(held)
                        watch.ticks.incrementAndGet()
                        delay(TICK_MILLIS)
                    }
                }
                Grey()
            }
        }

        onApplication(content) { shell ->
            awaitPlaced(shell)
            awaitWindow(shell, TITLE)

            title.value = SECOND_TITLE

            awaitWindow(shell, SECOND_TITLE)
            val ticks = watch.ticks.get()

            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { watch.ticks.get() >= ticks + TICKS },
                "the effect running in the content stopped when the window's title changed",
            )
            assertEquals(1, watch.compositions.get(), "the content was composed again from scratch")
            assertEquals(1, watch.effects.get(), "the content's effect was started a second time")
            assertEquals(1, watch.held.get(), "the content lost what it held behind remember")
        }
    }

    @Test
    fun `a changed width draws content at it until the compositor's next configure takes the window back`() {
        val width = mutableStateOf(WIDTH)
        val state = WindowState()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            Window(title = TITLE, appId = APP_ID, width = width.value, height = HEIGHT, state = state) { Grey() }
        }

        onApplication(content) { shell ->
            awaitPlaced(shell)
            val window = awaitWindow(shell, TITLE)

            // Unfocused here, so the focus below draws out a configure at the size the window already has.
            Hyprctl.dispatch("movetoworkspacesilent", "$ASIDE,address:${window.address}")
            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { listedWindow()?.workspace?.name == ASIDE },
                "the window never left the workspace the user is looking at",
            )
            // The move's own configure lands after the workspace change; everything below is about the next one.
            shell.pumpOrFail(SETTLE_MILLIS)

            val configured = assertIs<WindowStatus.OnScreen>(
                state.status,
                "the window moved workspaces and its state reports ${state.status}",
            ).size
            assertNotEquals(NARROW_SIZE, configured, "the compositor gave the window the size this test asks for")

            width.value = NARROW_WIDTH

            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { state.status == WindowStatus.OnScreen(NARROW_SIZE) },
                "the changed width never reached the window, which reports ${state.status}",
            )

            Hyprctl.dispatch("focuswindow", "address:${window.address}")

            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { state.status == WindowStatus.OnScreen(configured) },
                "the window kept the size its call asked for: ${state.status}, not $configured",
            )
        }
    }

    @Test
    fun `a close request reaches the window's state and leaves the window on screen`() {
        val state = WindowState()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            Window(title = TITLE, appId = APP_ID, width = WIDTH, height = HEIGHT, state = state) { Grey() }
        }

        onApplication(content) { shell ->
            awaitPlaced(shell)
            val window = awaitWindow(shell, TITLE)

            Hyprctl.dispatch("closewindow", "address:${window.address}")

            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { state.closeRequested },
                "the compositor asked the window to close and its state never said so",
            )

            shell.pumpOrFail(SETTLE_MILLIS)

            assertIs<WindowStatus.OnScreen>(state.status, "the close request ended the window: ${state.status}")
            assertEquals(
                window.address, listedWindow()?.address,
                "hyprctl no longer lists the window the compositor asked to close",
            )
        }
    }

    @Test
    fun `a window the caller stops composing after a close request ends with its call leaving composition`() {
        val showing = mutableStateOf(true)
        val state = WindowState()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            if (showing.value) {
                Window(title = TITLE, appId = APP_ID, width = WIDTH, height = HEIGHT, state = state) { Grey() }
            }
        }

        onApplication(content) { shell ->
            awaitPlaced(shell)
            val window = awaitWindow(shell, TITLE)

            Hyprctl.dispatch("closewindow", "address:${window.address}")
            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { state.closeRequested },
                "the compositor asked the window to close and its state never said so",
            )

            showing.value = false

            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { state.status is WindowStatus.Ended },
                "the call left composition after a close request and its window never ended",
            )
            assertEquals(
                WindowStatus.Ended(Ok(SurfaceEnd.LeftComposition)), state.status,
                "the window the caller took down on the compositor's request ended some other way",
            )
        }
    }

    @Test
    fun `fullscreen follows the compositor giving the window the whole screen and taking it back`() {
        val state = WindowState()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            Window(title = TITLE, appId = APP_ID, width = WIDTH, height = HEIGHT, state = state) { Grey() }
        }

        onApplication(content) { shell ->
            awaitPlaced(shell)
            val window = awaitWindow(shell, TITLE)
            assertFalse(state.fullscreen, "the window reports the whole screen before anything gave it one")

            // The dispatcher acts on whatever window holds focus, so the address goes in through the focus.
            Hyprctl.dispatch("focuswindow", "address:${window.address}")
            Hyprctl.dispatch("fullscreen", FULL_SCREEN)

            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { state.fullscreen },
                "the compositor gave the window the whole screen and its state never said so",
            )

            Hyprctl.dispatch("fullscreen", FULL_SCREEN)

            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { !state.fullscreen },
                "the window still reports the whole screen after the compositor took it back",
            )
        }
    }

    /** What the window's content publishes: how often it was composed, what it holds, and that its effect runs. */
    private class Watch {
        val compositions = AtomicInteger()
        val effects = AtomicInteger()
        val held = AtomicInteger()
        val ticks = AtomicInteger()
    }

    /** A grey fill, so the window that maps has something of its own in it. */
    @Composable
    private fun Grey() {
        Box(Modifier.fillMaxSize().background(Color.Gray))
    }

    /** The window hyprctl lists under this test's app id, or null while it lists none. */
    private fun listedWindow(): HyprClient? = Hyprctl.clients().firstOrNull { it.appId == APP_ID }

    /** The window once hyprctl lists it under [title], driving [shell] until it does. */
    private fun awaitWindow(shell: KortexShell, title: String): HyprClient {
        assertTrue(
            shell.pumpOrFail(PUMP_MILLIS) { listedWindow()?.title == title },
            "hyprctl clients never listed a window titled \"$title\"",
        )
        return assertNotNull(listedWindow(), "the window hyprctl listed was gone again a moment later")
    }

    private companion object {
        const val TITLE = "kortex window"
        const val SECOND_TITLE = "kortex window renamed"
        const val APP_ID = "kortex-window-test"

        /** A workspace of the window's own, which the user is not looking at and which goes when it does. */
        const val ASIDE = "special:kortex-window-test"

        /**
         * What Hyprland's `fullscreen` dispatcher calls the whole screen; `1` is its own kind of maximize, which
         * reaches no window state, since every toplevel it maps is told it is maximized and never told otherwise.
         */
        const val FULL_SCREEN = "0"

        val WIDTH = 640.dp
        val HEIGHT = 480.dp
        val NARROW_WIDTH = 320.dp

        /** What the call asks for, which a compositor that tiles replaces with the size it has room for. */
        val ASKED_SIZE = IntSize(WIDTH.toLogicalPx(), HEIGHT.toLogicalPx())

        /** What the call asks for once its width has changed, which is what content is drawn at until a configure. */
        val NARROW_SIZE = IntSize(NARROW_WIDTH.toLogicalPx(), HEIGHT.toLogicalPx())

        const val PUMP_MILLIS = 4_000L
        const val SETTLE_MILLIS = 1_000L
        const val TICK_MILLIS = 20L
        const val TICKS = 3
    }
}
