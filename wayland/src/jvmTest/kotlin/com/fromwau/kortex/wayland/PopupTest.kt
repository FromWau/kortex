package com.fromwau.kortex.wayland

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.errorOrNull
import com.fromwau.kern.result.getOrElse
import com.fromwau.kern.result.onSuccess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Opens real popups on the compositor, from a bar's content and from a window's.
 *
 * The window case takes the user's focus and tiles into the workspace they are looking at, so this class runs
 * only in a session kept free for it. The bar the other cases open from spans the bottom edge and reserves
 * nothing, so it never re-tiles anything.
 */
class PopupTest {
    @Test
    fun `a popup shown from a bar's content reaches the screen, and hyprctl layers lists only the bar`() {
        val bar = SurfaceState()
        val menu = SurfaceState()
        val showing = mutableStateOf(false)
        val content: @Composable KortexApplicationScope.() -> Unit = {
            BottomBar(bar) {
                if (showing.value) Popup(at = AT, width = MENU_WIDTH, height = MENU_HEIGHT, state = menu) { Grey() }
            }
        }

        onApplication(content) { shell ->
            awaitPlaced(shell)
            assertNotNull(Screen.awaitGeometry(BAR_NAMESPACE), "hyprctl never listed $BAR_NAMESPACE")

            showing.value = true

            awaitMenu(shell, menu)
            assertEquals(
                SurfaceStatus.OnScreen(MENU_SIZE), menu.status,
                "the popup is drawn at a size it never asked for",
            )
            assertEquals(2, shell.shownSurfaces.size, "the bar and the popup on it are not both held by the shell")
            assertEquals(
                listOf(BAR_NAMESPACE), ourNamespaces(),
                "a popup belongs to the surface it opens over, and hyprctl layers lists one of its own",
            )
        }
    }

    @Test
    fun `a popup on a bar along the bottom edge opens upwards, measured from the bar and not from the monitor`() {
        val bar = SurfaceState()
        val menu = SurfaceState()
        val showing = mutableStateOf(false)
        val content: @Composable KortexApplicationScope.() -> Unit = {
            BottomBar(bar) {
                if (showing.value) Popup(at = AT, width = MENU_WIDTH, height = MENU_HEIGHT, state = menu) { Grey() }
            }
        }

        onApplication(content) { shell ->
            awaitPlaced(shell)
            val geometry = assertNotNull(Screen.awaitGeometry(BAR_NAMESPACE), "hyprctl never listed $BAR_NAMESPACE")
            val screen = Hyprctl.monitor(geometry.monitor)

            showing.value = true

            awaitMenu(shell, menu)
            assertTrue(
                AT.y + MENU_SIZE.height <= screen.logicalHeight,
                "measured from the monitor's own top this point leaves no room either, so nothing here is shown",
            )
            assertTrue(
                geometry.y + AT.y + MENU_SIZE.height > screen.logicalHeight,
                "the bar does not sit near enough the monitor's bottom edge for a popup on it to run past it",
            )
            assertEquals(
                IntOffset(AT.x, AT.y + ANCHOR_SPAN - MENU_SIZE.height), popupRole(shell).placedAt,
                "the popup did not open upwards from the point it was given inside the bar",
            )
        }
    }

    @Test
    fun `a popup whose call leaves composition ends with it, and the bar it opened over stands`() {
        val bar = SurfaceState()
        val menu = SurfaceState()
        val showing = mutableStateOf(false)
        val content: @Composable KortexApplicationScope.() -> Unit = {
            BottomBar(bar) {
                if (showing.value) Popup(at = AT, width = MENU_WIDTH, height = MENU_HEIGHT, state = menu) { Grey() }
            }
        }

        onApplication(content) { shell ->
            awaitPlaced(shell)
            assertNotNull(Screen.awaitGeometry(BAR_NAMESPACE), "hyprctl never listed $BAR_NAMESPACE")

            showing.value = true
            awaitMenu(shell, menu)

            showing.value = false

            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { menu.hasEnded },
                "the call left composition and its popup never ended",
            )
            menu.assertEnded(
                Ok(SurfaceEnd.LeftComposition),
                "the popup whose call left composition ended some other way",
            )
            assertIs<SurfaceStatus.OnScreen>(bar.status, "the popup took the bar with it: ${bar.status}")
            assertEquals(1, shell.shownSurfaces.size, "the bar is no longer the one surface the shell holds")
            assertNotNull(Screen.geometry(BAR_NAMESPACE), "hyprctl no longer lists the bar the popup opened over")
        }
    }

    @Test
    fun `a popup shown from a window's content opens inside that window, and is no window or layer of its own`() {
        val window = WindowState()
        val menu = SurfaceState()
        val showing = mutableStateOf(false)
        val content: @Composable KortexApplicationScope.() -> Unit = {
            Window(title = TITLE, appId = APP_ID, width = WINDOW_WIDTH, height = WINDOW_HEIGHT, state = window) {
                if (showing.value) Popup(at = AT, width = MENU_WIDTH, height = MENU_HEIGHT, state = menu) { Grey() }
            }
        }

        onApplication(content) { shell ->
            awaitPlaced(shell)
            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { listedWindow() != null },
                "hyprctl clients never listed a window of this test's own",
            )
            val placed = assertNotNull(listedWindow(), "the window hyprctl listed was gone again a moment later")
            val screen = Hyprctl.monitors().first()

            showing.value = true

            awaitMenu(shell, menu)
            assertTrue(
                placed.at.x + AT.x + MENU_SIZE.width <= screen.logicalWidth &&
                    placed.at.y + AT.y + MENU_SIZE.height <= screen.logicalHeight,
                "the compositor left the window too near an edge for a popup at this point to open unflipped",
            )
            assertEquals(AT, popupRole(shell).placedAt, "the popup did not open at its point inside the window")
            assertEquals(1, Hyprctl.windows().count { it.appId == APP_ID }, "the popup is a window of its own")
            assertEquals(emptyList(), ourNamespaces(), "the popup is a layer-shell surface of its own")
            assertIs<WindowStatus.OnScreen>(window.status, "the popup took its window with it: ${window.status}")
        }
    }

    @Test
    fun `a ContextMenu opens a popup of its menu size at the point it was given`() {
        val bar = SurfaceState()
        val menu = SurfaceState()
        val showing = mutableStateOf(false)
        val content: @Composable KortexApplicationScope.() -> Unit = {
            BottomBar(bar) {
                if (showing.value) ContextMenu(at = AT, menuSize = MENU_SIZE, state = menu) { Grey() }
            }
        }

        onApplication(content) { shell ->
            awaitPlaced(shell)
            assertNotNull(Screen.awaitGeometry(BAR_NAMESPACE), "hyprctl never listed $BAR_NAMESPACE")

            showing.value = true

            awaitMenu(shell, menu)
            assertEquals(
                SurfaceStatus.OnScreen(MENU_SIZE), menu.status,
                "the menu is drawn at a size other than the one it was given",
            )
            assertEquals(
                IntOffset(AT.x, AT.y + ANCHOR_SPAN - MENU_SIZE.height), popupRole(shell).placedAt,
                "the menu did not open from the point it was given inside the bar",
            )
        }
    }

    @Test
    fun `a popup called outside a surface's content fails the application and says where one belongs`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use {
            var error: KortexError? = null
            capturingStderr {
                error = KortexShell
                    .createApplication(display) { Popup(at = AT, width = MENU_WIDTH, height = MENU_HEIGHT) { Grey() } }
                    .onSuccess { it.close() }
                    .errorOrNull()
            }

            val crash = assertIs<KortexError.ApplicationCrashed>(error, "a popup with no surface to open over started")
            assertEquals(POPUP_WITHOUT_PARENT, crash.cause.message, "the crash did not say where a popup belongs")
        }
    }

    /** A bar along the bottom edge that reserves nothing, so nothing of the user's is re-tiled around it. */
    @Composable
    private fun BottomBar(
        state: SurfaceState,
        content: @Composable SurfaceScope.() -> Unit,
    ) {
        LayerSurface(
            namespace = BAR_NAMESPACE,
            anchor = setOf(Edge.Bottom, Edge.Left, Edge.Right),
            height = BAR_THICKNESS,
            exclusiveZone = ExclusiveZone.Overlap,
            state = state,
            content = content,
        )
    }

    /** Drives [shell] until the popup [menu] watches is on screen; the test fails if it never is. */
    private fun awaitMenu(shell: KortexShell, menu: SurfaceState) {
        assertTrue(
            shell.pumpOrFail(PUMP_MILLIS) { menu.status is SurfaceStatus.OnScreen },
            "the popup never reached the screen: ${menu.status}",
        )
    }

    /** The popup the shell placed last, which every test here opens after its parent. */
    private fun popupRole(shell: KortexShell): XdgPopupSurface = assertIs<XdgPopupSurface>(
        shell.shownSurfaces.last().role,
        "the last surface the shell placed was built on another role",
    )

    /** Every layer surface hyprctl lists under a namespace of this test's own. */
    private fun ourNamespaces(): List<String> = Hyprctl.namespaces().filter { it.startsWith(NAMESPACE_PREFIX) }

    /** The window hyprctl lists under this test's app id, or null while it lists none. */
    private fun listedWindow(): HyprWindow? = Hyprctl.windows().firstOrNull { it.appId == APP_ID }

    private companion object {
        const val NAMESPACE_PREFIX = "kortex-popup-test"
        const val BAR_NAMESPACE = "$NAMESPACE_PREFIX-bar"
        const val TITLE = "kortex popup host"
        const val APP_ID = "kortex-popup-test"

        val BAR_THICKNESS = 32.dp
        val WINDOW_WIDTH = 640.dp
        val WINDOW_HEIGHT = 480.dp

        val MENU_WIDTH = 200.dp
        val MENU_HEIGHT = 120.dp
        val MENU_SIZE = IntSize(MENU_WIDTH.toLogicalPx(), MENU_HEIGHT.toLogicalPx())

        /** Both coordinates non-zero, so a popup placed against its parent's corner instead shows up as one. */
        val AT = IntOffset(40, 8)

        /** A popup that flips is mirrored around the far edge of the one-pixel anchor rectangle at its point. */
        const val ANCHOR_SPAN = 1

        const val PUMP_MILLIS = 6_000L
    }
}
