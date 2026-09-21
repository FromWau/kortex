package com.fromwau.kortex.wayland

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import com.fromwau.kern.result.Ok
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A surface the compositor closes is never placed again, whatever showed it: this drives that rule with
 * [KortexSurface.simulateCompositorClose], the seam standing in for the one event a headless output cannot
 * produce here, on a top-level surface and on one a surface's own content showed.
 */
class CompositorChoiceTest {
    @Test
    fun `the compositor closing a shown surface ends it as ClosedByCompositor, and it is not placed again`() {
        val osd = SurfaceState()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            TestSurface(OSD_NAMESPACE, state = osd)
        }

        onApplication(content) { shell ->
            awaitPlaced(shell)

            shell.shownSurfaces.single().simulateCompositorClose()

            assertTrue(
                shell.pumpOrFail(PUMP_TIMEOUT_MILLIS) { osd.hasEnded },
                "the compositor's close ended nothing",
            )
            shell.pumpOrFail(SETTLE_MILLIS)
            osd.assertEnded(
                Ok(SurfaceEnd.ClosedByCompositor),
                "the compositor's close did not end the surface as ClosedByCompositor",
            )
            assertTrue(shell.shownSurfaces.isEmpty(), "a surface the compositor closed was placed again")
            assertTrue(
                shell.pumpOrFail(PUMP_TIMEOUT_MILLIS) { Screen.geometry(OSD_NAMESPACE) == null },
                "hyprctl layers still reports $OSD_NAMESPACE after the compositor closed it",
            )
        }
    }

    @Test
    fun `a surface shown from inside another's content is not placed again once the compositor closes it`() {
        val showChild = mutableStateOf(false)
        val child = SurfaceState()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            TestSurface(PANEL_NAMESPACE) {
                if (showChild.value) {
                    TestSurface(
                        OPENED_NAMESPACE,
                        anchor = BOTTOM_LEFT,
                        state = child,
                    )
                }
            }
        }

        onApplication(content) { shell ->
            awaitPlaced(shell)
            val panelAlone = shell.shownSurfaces.size

            showChild.value = true
            val opened = shell.pumpOrFail(PUMP_TIMEOUT_MILLIS) { shell.shownSurfaces.size == panelAlone + 1 }
            assertTrue(opened, "the surface shown from inside the panel's content never placed")

            shell.shownSurfaces.last().simulateCompositorClose()

            assertTrue(
                shell.pumpOrFail(PUMP_TIMEOUT_MILLIS) { child.hasEnded },
                "the compositor's close ended nothing",
            )
            shell.pumpOrFail(SETTLE_MILLIS)
            child.assertEnded(
                Ok(SurfaceEnd.ClosedByCompositor),
                "the compositor's close did not end the surface as ClosedByCompositor",
            )
            assertEquals(
                panelAlone, shell.shownSurfaces.size,
                "the surface the compositor closed was placed again, or the panel went with it",
            )
        }
    }

    private companion object {
        const val OSD_NAMESPACE = "kortex-compositorchoice-osd"
        const val PANEL_NAMESPACE = "kortex-compositorchoice-panel"
        const val OPENED_NAMESPACE = "kortex-compositorchoice-opened"

        const val PUMP_TIMEOUT_MILLIS = 4000L
        const val SETTLE_MILLIS = 300L

        val BOTTOM_LEFT = setOf(Edge.Bottom, Edge.Left)
    }
}
