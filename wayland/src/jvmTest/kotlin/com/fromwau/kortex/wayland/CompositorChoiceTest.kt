package com.fromwau.kortex.wayland

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Ok
import java.util.concurrent.CopyOnWriteArrayList
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
    fun `the compositor closing a shown surface reports Ok, and it is not placed again`() {
        val reports = CopyOnWriteArrayList<EmptyResult<SurfaceError<Nothing>>>()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            Show(TestSurface<Nothing>(OSD_NAMESPACE, onClose = { reports += it }))
        }

        onApplication(content) { shell ->
            awaitPlaced(shell)

            shell.shownSurfaces.single().simulateCompositorClose()

            assertTrue(
                shell.pumpOrFail(PUMP_TIMEOUT_MILLIS) { reports.isNotEmpty() },
                "the compositor's close reported nothing",
            )
            assertEquals(listOf(Ok(Unit)), reports.toList(), "the compositor's close did not report Ok once")
            shell.pumpOrFail(SETTLE_MILLIS)
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
        val childReports = CopyOnWriteArrayList<EmptyResult<SurfaceError<Nothing>>>()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            Show(
                TestSurface<Nothing>(PANEL_NAMESPACE) {
                    if (showChild.value) {
                        Show(TestSurface<Nothing>(OPENED_NAMESPACE, anchor = BOTTOM_LEFT, onClose = { childReports += it }))
                    }
                },
            )
        }

        onApplication(content) { shell ->
            awaitPlaced(shell)
            val panelAlone = shell.shownSurfaces.size

            showChild.value = true
            val opened = shell.pumpOrFail(PUMP_TIMEOUT_MILLIS) { shell.shownSurfaces.size == panelAlone + 1 }
            assertTrue(opened, "the surface shown from inside the panel's content never placed")

            shell.shownSurfaces.last().simulateCompositorClose()

            assertTrue(
                shell.pumpOrFail(PUMP_TIMEOUT_MILLIS) { childReports.isNotEmpty() },
                "the compositor's close reported nothing",
            )
            assertEquals(listOf(Ok(Unit)), childReports.toList(), "the compositor's close did not report Ok once")
            shell.pumpOrFail(SETTLE_MILLIS)
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
