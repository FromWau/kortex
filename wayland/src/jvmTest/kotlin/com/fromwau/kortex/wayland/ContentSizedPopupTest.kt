package com.fromwau.kortex.wayland

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Opens popups sized by their content on the compositor, from a bar along the bottom edge that reserves nothing, so
 * nothing of the user's is re-tiled and no focus is taken.
 */
class ContentSizedPopupTest {
    @Test
    fun `a popup sized by its content opens at the size its content measures`() {
        val popup = SurfaceState()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            BottomBar {
                Popup(at = AT, state = popup) { Box(Modifier.size(CONTENT_WIDTH, CONTENT_HEIGHT)) }
            }
        }

        onApplication(content) { shell ->
            awaitOnScreen(shell, popup)

            assertEquals(
                SurfaceStatus.OnScreen(CONTENT_SIZE), popup.status,
                "the popup opened at a size other than the one its content measures",
            )
        }
    }

    @Test
    fun `content that grows opens its popup again at the new size, and keeps its state`() {
        val popup = SurfaceState()
        val height = mutableStateOf(CONTENT_HEIGHT)
        val remembered = CopyOnWriteArrayList<Any>()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            BottomBar {
                Popup(at = AT, state = popup) {
                    val kept = remember { Any() }
                    SideEffect { if (remembered.lastOrNull() !== kept) remembered += kept }
                    Box(Modifier.size(CONTENT_WIDTH, height.value))
                }
            }
        }

        onApplication(content) { shell ->
            awaitOnScreen(shell, popup)

            height.value = GROWN_HEIGHT

            val grown = IntSize(CONTENT_SIZE.width, GROWN_HEIGHT.toLogicalPx())
            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { popup.status == SurfaceStatus.OnScreen(grown) },
                "the popup never opened again at the size its content grew to: ${popup.status}",
            )
            assertEquals(1, remembered.size, "the content lost what it remembered as its popup was opened again")
        }
    }

    @Test
    fun `content that fills its room takes all of the most the popup may be`() {
        val popup = SurfaceState()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            BottomBar {
                Popup(at = AT, maxSize = DpSize(CONTENT_WIDTH, CONTENT_HEIGHT), state = popup) {
                    Box(Modifier.fillMaxSize())
                }
            }
        }

        onApplication(content) { shell ->
            awaitOnScreen(shell, popup)

            assertEquals(
                SurfaceStatus.OnScreen(CONTENT_SIZE), popup.status,
                "content filling its room did not take all of the popup's max size",
            )
        }
    }

    @Test
    fun `content that measures at nothing opens a popup one pixel square`() {
        val popup = SurfaceState()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            BottomBar {
                Popup(at = AT, state = popup) { Box(Modifier) }
            }
        }

        onApplication(content) { shell ->
            awaitOnScreen(shell, popup)

            assertEquals(SurfaceStatus.OnScreen(IntSize(1, 1)), popup.status, "an empty popup opened at no size")
        }
    }

    @Test
    fun `a max size under one pixel ends the surface the popup is on`() {
        val bar = SurfaceState()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            BottomBar(bar) {
                Popup(at = AT, maxSize = DpSize(0.dp, Dp.Infinity)) { Grey() }
            }
        }

        onApplication(content) { shell ->
            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { bar.hasEnded },
                "a popup with no room across left the surface it opened over running",
            )
            val crash = bar.crashOrFail("a popup with no room across did not end its surface as a crash")
            assertEquals(
                "a popup measures its content in at least one pixel on each axis, not ${DpSize(0.dp, Dp.Infinity)}",
                crash.failure.cause.message,
                "the crash did not say what room a popup needs",
            )
        }
    }

    /** A bar along the bottom edge that reserves nothing, so nothing of the user's is re-tiled around it. */
    @Composable
    private fun BottomBar(
        state: SurfaceState = remember { SurfaceState() },
        content: @Composable SurfaceScope.() -> Unit,
    ) {
        LayerSurface(
            namespace = NAMESPACE,
            anchor = setOf(Edge.Bottom, Edge.Left, Edge.Right),
            height = Length.Of(BAR_THICKNESS),
            exclusiveZone = ExclusiveZone.Overlap,
            state = state,
            content = content,
        )
    }

    private fun awaitOnScreen(shell: KortexShell, popup: SurfaceState) {
        assertTrue(
            shell.pumpOrFail(PUMP_MILLIS) { popup.status is SurfaceStatus.OnScreen },
            "the popup never reached the screen: ${popup.status}",
        )
    }

    private companion object {
        const val NAMESPACE = "kortex-fitted-popup-test"

        val BAR_THICKNESS = 32.dp
        val CONTENT_WIDTH = 150.dp
        val CONTENT_HEIGHT = 40.dp
        val GROWN_HEIGHT = 90.dp
        val CONTENT_SIZE = IntSize(CONTENT_WIDTH.toLogicalPx(), CONTENT_HEIGHT.toLogicalPx())

        val AT = IntOffset(40, 8)

        const val PUMP_MILLIS = 6_000L
    }
}
