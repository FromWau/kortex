package com.fromwau.kortex.wayland

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.errorOrNull
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Places a real window on the compositor, which takes the user's focus and tiles into the workspace they are
 * looking at, and real dialogs beside it, which do the same. One test parks its window on a special workspace and
 * focuses it there, which pulls that workspace up over the screen and warps the pointer onto the window. So this
 * class runs only in a session kept free for it.
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
            val window = awaitWindow(shell, TITLE)

            // Driven rather than assumed. Left alone the compositor may tile this window, which overrides the
            // requested size, or leave it floating at exactly what was asked, and which of those happens is a
            // property of the workspace rather than of anything kortex does. The whole screen is certain to
            // differ from a 640 by 480 request, so the assertion below is about the drawing and not the luck.
            Hyprctl.dispatch("window.fullscreen", address = window.address)
            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { shell.shownSurfaces.single().logicalSize != ASKED_SIZE },
                "the compositor was asked for the whole screen and never configured the window off $ASKED_SIZE",
            )

            val configured = shell.shownSurfaces.single().logicalSize
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
            Window(title = title.value, appId = APP_ID, width = WIDTH, height = HEIGHT) { Watched(watch) }
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
            Hyprctl.dispatch("window.move", "workspace = \"$ASIDE\"", "follow = false", address = window.address)
            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { listedWindow()?.workspace == ASIDE },
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

            Hyprctl.dispatch("focus", address = window.address)

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

            Hyprctl.dispatch("window.close", address = window.address)

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

            Hyprctl.dispatch("window.close", address = window.address)
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
    fun `a declined close request goes back down and the compositor's next one is seen`() {
        val state = WindowState()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            Window(title = TITLE, appId = APP_ID, width = WIDTH, height = HEIGHT, state = state) { Grey() }
        }

        onApplication(content) { shell ->
            awaitPlaced(shell)
            val window = awaitWindow(shell, TITLE)

            Hyprctl.dispatch("window.close", address = window.address)
            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { state.closeRequested },
                "the compositor asked the window to close and its state never said so",
            )

            state.declineClose()

            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { !state.closeRequested },
                "the window the caller kept still reports the close it refused",
            )

            Hyprctl.dispatch("window.close", address = window.address)

            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { state.closeRequested },
                "the compositor asked a second time and the window it kept never said so",
            )
            assertIs<WindowStatus.OnScreen>(state.status, "the window the caller kept ended: ${state.status}")
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

            Hyprctl.dispatch("window.fullscreen", address = window.address)

            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { state.fullscreen },
                "the compositor gave the window the whole screen and its state never said so",
            )

            Hyprctl.dispatch("window.fullscreen", address = window.address)

            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { !state.fullscreen },
                "the window still reports the whole screen after the compositor took it back",
            )
        }
    }

    @Test
    fun `a dialog shown from a window's content hangs off that window, and both stay on screen`() {
        val window = WindowState()
        val dialog = WindowState()
        val showing = mutableStateOf(false)
        val content: @Composable KortexApplicationScope.() -> Unit = {
            Window(title = TITLE, appId = APP_ID, width = WIDTH, height = HEIGHT, state = window) {
                if (showing.value) Dialog(title = DIALOG_TITLE, state = dialog) { Grey() }
            }
        }

        onApplication(content) { shell ->
            awaitPlaced(shell)
            val parent = awaitWindow(shell, TITLE)
            // Hyprland floats a toplevel that names a parent and tiles one that does not
            // (0.56.2, XWaylandManager.cpp:141 through Window.cpp:2129), which is how a parent shows up here.
            assertFalse(parent.floating, "the compositor floated a window with no parent, so floating says nothing")

            showing.value = true

            val shown = awaitDialog(shell, dialog)
            assertTrue(shown.floating, "the compositor tiled the dialog, which is what it does with no parent named")
            assertNotEquals(parent.address, shown.address, "the dialog and the window it belongs to are one window")
            assertIs<WindowStatus.OnScreen>(window.status, "the dialog took its window with it: ${window.status}")
            assertEquals(2, shell.shownSurfaces.size, "the window and its dialog are not both held by the shell")
        }
    }

    @Test
    fun `a dialog shown from a layer surface's content is a window of its own, and that surface stands`() {
        val speck = SurfaceState()
        val dialog = WindowState()
        val showing = mutableStateOf(false)
        val content: @Composable KortexApplicationScope.() -> Unit = {
            TestSurface(SPECK_NAMESPACE, state = speck) {
                if (showing.value) Dialog(title = DIALOG_TITLE, state = dialog) { Grey() }
            }
        }

        onApplication(content) { shell ->
            awaitPlaced(shell)
            assertNotNull(Screen.awaitGeometry(SPECK_NAMESPACE), "hyprctl never listed $SPECK_NAMESPACE")

            showing.value = true

            val shown = awaitDialog(shell, dialog)
            assertFalse(shown.floating, "the compositor floated the dialog, which is what it does for a parented one")
            assertNull(
                shell.display.requireAlive().errorOrNull(),
                "the compositor rejected something this dialog sent and took the connection with it",
            )
            assertIs<SurfaceStatus.OnScreen>(
                speck.status,
                "the dialog took the surface it was shown from with it: ${speck.status}",
            )
            assertNotNull(Screen.geometry(SPECK_NAMESPACE), "hyprctl no longer lists the surface it was shown from")
        }
    }

    /** The window hyprctl lists under this test's app id, or null while it lists none. */
    private fun listedWindow(): HyprWindow? = Hyprctl.windows().firstOrNull { it.appId == APP_ID }

    /** The window hyprctl lists under this test's dialog title, or null while it lists none. */
    private fun listedDialog(): HyprWindow? = Hyprctl.window(DIALOG_TITLE)

    /** The dialog once its state and hyprctl both report it, driving [shell] until they do. */
    private fun awaitDialog(shell: KortexShell, state: WindowState): HyprWindow {
        assertTrue(
            shell.pumpOrFail(PUMP_MILLIS) { state.status is WindowStatus.OnScreen && listedDialog() != null },
            "the dialog never reached the screen: ${state.status}",
        )
        return assertNotNull(listedDialog(), "the dialog hyprctl listed was gone again a moment later")
    }

    /** The window once hyprctl lists it under [title], driving [shell] until it does. */
    private fun awaitWindow(shell: KortexShell, title: String): HyprWindow {
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

        /** A dialog carries no app id of its own, so hyprctl tells this one apart by its title. */
        const val DIALOG_TITLE = "kortex window test dialog"
        const val SPECK_NAMESPACE = "kortex-window-test-speck"

        /**
         * A workspace of the window's own, which goes when the window does. Focusing a window parked here pulls
         * it up over whatever the user is looking at and warps their pointer onto the window.
         */
        const val ASIDE = "special:kortex-window-test"

        val WIDTH = 640.dp
        val HEIGHT = 480.dp
        val NARROW_WIDTH = 320.dp

        /** What the call asks for, which a compositor that tiles replaces with the size it has room for. */
        val ASKED_SIZE = IntSize(WIDTH.toLogicalPx(), HEIGHT.toLogicalPx())

        /** What the call asks for once its width has changed, which is what content is drawn at until a configure. */
        val NARROW_SIZE = IntSize(NARROW_WIDTH.toLogicalPx(), HEIGHT.toLogicalPx())

        const val PUMP_MILLIS = 4_000L
        const val SETTLE_MILLIS = 1_000L
        const val TICKS = 3
    }
}
