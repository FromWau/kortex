package com.fromwau.kortex.wayland

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.getOrElse
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

/** A surface shown through [Show] in an application composition, and how each of its endings reaches `onClose`. */
class ShowTest {
    @Test
    fun `a Show places its surface one pass after it enters composition, under its namespace as written`() {
        onApplication({ Show(TestSurface<Nothing>(NAMESPACE)) }) { shell ->
            assertTrue(shell.shownSurfaces.isEmpty(), "Show placed its surface inside composition")

            shell.passOrFail()

            assertEquals(1, shell.shownSurfaces.size, "the pass after Show entered composition placed no surface")
            assertNotNull(Screen.awaitGeometry(NAMESPACE), "hyprctl never listed $NAMESPACE, its namespace as written")
        }
    }

    @Test
    fun `taking a Show out of composition removes its surface and reports Ok once`() {
        val showing = mutableStateOf(true)
        val reports = CopyOnWriteArrayList<EmptyResult<SurfaceError<Nothing>>>()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            if (showing.value) Show(TestSurface<Nothing>(NAMESPACE, onClose = { reports += it }))
        }

        onApplication(content) { shell ->
            assertTrue(shell.pumpOrFail(PUMP_MILLIS) { shell.shownSurfaces.isNotEmpty() }, "the surface was never placed")

            showing.value = false

            assertTrue(shell.pumpOrFail(PUMP_MILLIS) { reports.isNotEmpty() }, "taking the Show out reported nothing")
            shell.pumpOrFail(SETTLE_MILLIS)
            assertEquals(listOf(Ok(Unit)), reports.toList(), "taking the Show out did not report Ok exactly once")
            assertTrue(shell.shownSurfaces.isEmpty(), "the surface outlived its Show")
            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { Screen.geometry(NAMESPACE) == null },
                "hyprctl still lists $NAMESPACE after its Show left composition",
            )
        }
    }

    @Test
    fun `a new instance with the same settings draws its own content and gets the report, on the same surface`() {
        val showing = mutableStateOf(true)
        val label = mutableIntStateOf(1)
        val drawn = CopyOnWriteArrayList<Int>()
        val reportedTo = CopyOnWriteArrayList<Int>()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            if (showing.value) {
                val current = label.intValue
                Show(LabelSurface(current, drawn, onClose = { reportedTo += current }))
            }
        }

        onApplication(content) { shell ->
            assertTrue(shell.pumpOrFail(PUMP_MILLIS) { 1 in drawn }, "the first instance's content never drew")
            val surface = shell.shownSurfaces.single()

            label.intValue = 2

            assertTrue(shell.pumpOrFail(PUMP_MILLIS) { 2 in drawn }, "the surface never drew the newer instance")
            assertSame(surface, shell.shownSurfaces.single(), "an instance with the same settings replaced the surface")

            showing.value = false

            assertTrue(shell.pumpOrFail(PUMP_MILLIS) { reportedTo.isNotEmpty() }, "taking the Show out reported nothing")
            assertEquals(listOf(2), reportedTo.toList(), "the ending did not reach the newest instance's onClose alone")
        }
    }

    @Test
    fun `changed settings replace the surface with a new one and report nothing`() {
        val height = mutableIntStateOf(SHORT)
        val reports = CopyOnWriteArrayList<EmptyResult<SurfaceError<Nothing>>>()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            Show(TestSurface<Nothing>(NAMESPACE, height = height.intValue.dp, onClose = { reports += it }))
        }

        onApplication(content) { shell ->
            assertTrue(shell.pumpOrFail(PUMP_MILLIS) { shell.shownSurfaces.isNotEmpty() }, "the surface was never placed")
            val first = shell.shownSurfaces.single()

            height.intValue = TALL

            val replaced = shell.pumpOrFail(PUMP_MILLIS) {
                shell.shownSurfaces.singleOrNull()?.let { it !== first } == true
            }
            assertTrue(replaced, "changed settings never replaced the surface")
            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { Screen.geometry(NAMESPACE)?.logicalHeight == TALL },
                "hyprctl never listed the new surface at its new height",
            )
            shell.pumpOrFail(SETTLE_MILLIS)
            assertTrue(reports.isEmpty(), "replacing the surface reported $reports")
        }
    }

    /** Draws the [label] it was built with, so a test can tell which instance's content its surface composes. */
    private class LabelSurface(
        private val label: Int,
        private val drawn: MutableList<Int>,
        onClose: (EmptyResult<SurfaceError<Nothing>>) -> Unit,
    ) : LayerSurface<Nothing>(
        namespace = NAMESPACE,
        layer = Layer.Overlay,
        anchor = setOf(Edge.Bottom, Edge.Right),
        width = SHORT.dp,
        height = SHORT.dp,
        onClose = onClose,
    ) {
        @Composable
        override fun invoke() {
            Canvas(Modifier.fillMaxSize()) { drawn += label }
        }
    }

    /** Connects, starts an application of [content] and hands it to [block]; the application and connection close after. */
    private fun onApplication(content: @Composable KortexApplicationScope.() -> Unit, block: (KortexShell) -> Unit) {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }
        display.use { KortexShell.createApplicationOrFail(display, content).useOrFail(block) }
    }

    private companion object {
        const val NAMESPACE = "kortex-show"
        const val PUMP_MILLIS = 4_000L
        const val SETTLE_MILLIS = 300L
        const val SHORT = 8
        const val TALL = 16
    }
}
