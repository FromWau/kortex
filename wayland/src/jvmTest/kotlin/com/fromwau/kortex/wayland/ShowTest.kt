package com.fromwau.kortex.wayland

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.IError
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.errorOrNull
import com.fromwau.kern.result.getOrElse
import com.fromwau.kortex.compose.ContentFailure
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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

    @Test
    fun `close() reports Ok and close(error) reports the error as Closed`() {
        val closeRequested = mutableStateOf(false)
        val plain = CopyOnWriteArrayList<EmptyResult<SurfaceError<Nothing>>>()
        val withError = CopyOnWriteArrayList<EmptyResult<SurfaceError<Dismissal>>>()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            Show(
                TestSurface<Nothing>(NAMESPACE, onClose = { plain += it }) {
                    val requested = closeRequested.value
                    LaunchedEffect(requested) { if (requested) close() }
                },
            )
            Show(
                TestSurface<Dismissal>(SECOND_NAMESPACE, anchor = BOTTOM_LEFT, onClose = { withError += it }) {
                    val requested = closeRequested.value
                    LaunchedEffect(requested) { if (requested) close(Dismissal.Dismissed) }
                },
            )
        }

        onApplication(content) { shell ->
            assertTrue(shell.pumpOrFail(PUMP_MILLIS) { shell.shownSurfaces.size == 2 }, "the surfaces were never placed")

            closeRequested.value = true

            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { plain.isNotEmpty() && withError.isNotEmpty() },
                "closing from content left a surface unreported: close() $plain, close(error) $withError",
            )
            assertEquals(listOf(Ok(Unit)), plain.toList(), "close() did not report Ok once")
            assertEquals(
                listOf(Err(SurfaceError.Closed(Dismissal.Dismissed))),
                withError.toList(),
                "close(error) did not report the error as Closed once",
            )
            assertTrue(shell.shownSurfaces.isEmpty(), "a closed surface stayed on screen")
        }
    }

    @Test
    fun `close() on an older instance of the same Show closes its surface`() {
        val generation = mutableIntStateOf(1)
        val instances = CopyOnWriteArrayList<TestSurface<Nothing>>()
        val reportedTo = CopyOnWriteArrayList<Int>()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            val current = generation.intValue
            val surface = TestSurface<Nothing>(NAMESPACE, onClose = { reportedTo += current })
            SideEffect { instances += surface }
            Show(surface)
        }

        onApplication(content) { shell ->
            assertTrue(shell.pumpOrFail(PUMP_MILLIS) { shell.shownSurfaces.isNotEmpty() }, "the surface was never placed")
            generation.intValue = 2
            assertTrue(shell.pumpOrFail(PUMP_MILLIS) { instances.size >= 2 }, "the application built no newer instance")

            instances[0].close()

            assertTrue(shell.pumpOrFail(PUMP_MILLIS) { reportedTo.isNotEmpty() }, "the older instance's close() reported nothing")
            assertEquals(listOf(2), reportedTo.toList(), "the ending did not reach the newest instance's onClose alone")
            assertTrue(shell.shownSurfaces.isEmpty(), "the older instance's close() left its surface on screen")
        }
    }

    @Test
    fun `close() and close(error) on an instance never handed to a Show do nothing`() {
        val reports = CopyOnWriteArrayList<EmptyResult<SurfaceError<Dismissal>>>()
        val stray = TestSurface<Dismissal>(NAMESPACE, onClose = { reports += it })

        onApplication({ Show(TestSurface<Nothing>(NAMESPACE)) }) { shell ->
            assertTrue(shell.pumpOrFail(PUMP_MILLIS) { shell.shownSurfaces.isNotEmpty() }, "the surface was never placed")

            stray.close()
            stray.close(Dismissal.Dismissed)

            shell.pumpOrFail(SETTLE_MILLIS)
            assertTrue(reports.isEmpty(), "an instance never shown reported $reports")
            assertEquals(1, shell.shownSurfaces.size, "closing an instance never shown closed a shown surface")
        }
    }

    @Test
    fun `size is zero until the surface is placed, its logical size while shown, and zero once it has ended`() {
        val showing = mutableStateOf(true)
        val instance = AtomicReference<TestSurface<Nothing>>()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            if (showing.value) {
                val surface = TestSurface<Nothing>(NAMESPACE)
                SideEffect { instance.set(surface) }
                Show(surface)
            }
        }

        onApplication(content) { shell ->
            val shown = assertNotNull(instance.get(), "the application never built its instance")
            assertEquals(IntSize.Zero, shown.size, "size was not zero before the surface was placed")

            assertTrue(shell.pumpOrFail(PUMP_MILLIS) { shell.shownSurfaces.isNotEmpty() }, "the surface was never placed")
            val geometry = assertNotNull(Screen.awaitGeometry(NAMESPACE), "hyprctl never listed $NAMESPACE")
            assertEquals(
                IntSize(geometry.logicalWidth, geometry.logicalHeight),
                shown.size,
                "size was not the logical size hyprctl reports for the surface",
            )

            showing.value = false

            assertTrue(shell.pumpOrFail(PUMP_MILLIS) { shell.shownSurfaces.isEmpty() }, "the surface outlived its Show")
            assertEquals(IntSize.Zero, shown.size, "size was not zero once the surface had ended")
        }
    }

    @Test
    fun `a surface the compositor closes reports Ok and is not replaced, and taking its Show out reports nothing more`() {
        val showing = mutableStateOf(true)
        val left = AtomicBoolean(false)
        val reports = CopyOnWriteArrayList<EmptyResult<SurfaceError<Nothing>>>()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            if (showing.value) {
                Show(TestSurface<Nothing>(NAMESPACE, onClose = { reports += it }))
                OnLeave(left)
            }
        }

        onApplication(content) { shell ->
            assertTrue(shell.pumpOrFail(PUMP_MILLIS) { shell.shownSurfaces.isNotEmpty() }, "the surface was never placed")

            shell.shownSurfaces.single().simulateCompositorClose()

            assertTrue(shell.pumpOrFail(PUMP_MILLIS) { reports.isNotEmpty() }, "the compositor's close reported nothing")
            assertEquals(listOf(Ok(Unit)), reports.toList(), "the compositor's close did not report Ok once")
            shell.pumpOrFail(SETTLE_MILLIS)
            assertTrue(shell.shownSurfaces.isEmpty(), "a surface the compositor closed was placed again")

            showing.value = false
            assertTrue(shell.pumpOrFail(PUMP_MILLIS) { left.get() }, "the Show never left composition")
            shell.passOrFail()

            assertEquals(listOf(Ok(Unit)), reports.toList(), "taking out a Show whose surface had ended reported again")
        }
    }

    @Test
    fun `a surface that cannot be placed reports Failed with the reason, and the run goes on`() {
        val reports = CopyOnWriteArrayList<EmptyResult<SurfaceError<Nothing>>>()
        val unplaceable: @Composable KortexApplicationScope.() -> Unit = {
            Show(TestSurface<Nothing>(NAMESPACE, anchor = emptySet(), width = 0.dp, onClose = { reports += it }))
        }

        onApplication(unplaceable) { shell ->
            assertTrue(shell.pumpOrFail(PUMP_MILLIS) { reports.isNotEmpty() }, "the unplaceable surface reported nothing")
            assertEquals(
                listOf(Err(SurfaceError.Failed(KortexError.UnspannableAxis(Axis.Horizontal, emptySet())))),
                reports.toList(),
                "a surface with an unspannable axis did not report Failed with that reason once",
            )
            assertTrue(shell.shownSurfaces.isEmpty(), "a surface that could not be placed is listed as shown")
        }
    }

    @Test
    fun `content whose effect throws reports Failed with the crash, and another shown surface keeps drawing`() {
        val reports = CopyOnWriteArrayList<EmptyResult<SurfaceError<Nothing>>>()
        val tick = mutableIntStateOf(0)
        val drawn = CopyOnWriteArrayList<Int>()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            Show(TestSurface<Nothing>(NAMESPACE, onClose = { reports += it }) { ThrowingEffect() })
            Show(
                TestSurface<Nothing>(SECOND_NAMESPACE, anchor = BOTTOM_LEFT) {
                    Canvas(Modifier.fillMaxSize()) { drawn += tick.intValue }
                },
            )
        }

        onApplication(content) { shell ->
            assertTrue(shell.pumpOrFail(PUMP_MILLIS) { reports.isNotEmpty() }, "the crashed surface reported nothing")
            val crash = crashIn(reports.single(), "an effect that threw did not report Failed(SurfaceCrashed)")
            assertEquals(NAMESPACE, crash.namespace, "the crash named another surface")
            assertIs<ContentFailure.Composition>(crash.failure, "the crash was not the effect's")
            assertEquals(EFFECT_FAILURE, crash.failure.cause.message, "the crash did not carry what the effect threw")

            assertEquals(1, shell.shownSurfaces.size, "the crash did not end its own surface, and only its own")
            tick.intValue = 1
            assertTrue(shell.pumpOrFail(PUMP_MILLIS) { 1 in drawn }, "the other surface stopped drawing after the crash")
        }
    }

    @Test
    fun `content whose cleanup throws as its Show is taken out reports the crash, not Ok`() {
        val showing = mutableStateOf(true)
        val reports = CopyOnWriteArrayList<EmptyResult<SurfaceError<Nothing>>>()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            if (showing.value) Show(TestSurface<Nothing>(NAMESPACE, onClose = { reports += it }) { ThrowingCleanup() })
        }

        onApplication(content) { shell ->
            assertTrue(shell.pumpOrFail(PUMP_MILLIS) { shell.shownSurfaces.isNotEmpty() }, "the surface was never placed")

            showing.value = false

            assertTrue(shell.pumpOrFail(PUMP_MILLIS) { reports.isNotEmpty() }, "taking the Show out reported nothing")
            val crash = crashIn(reports.single(), "cleanup that threw as its Show was taken out did not report a crash")
            assertEquals(CLEANUP_FAILURE, crash.failure.cause.message, "the crash did not carry what the cleanup threw")
        }
    }

    @Test
    fun `content whose cleanup throws as changed settings replace its surface reports the crash and places nothing`() {
        val height = mutableIntStateOf(SHORT)
        val reports = CopyOnWriteArrayList<EmptyResult<SurfaceError<Nothing>>>()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            Show(
                TestSurface<Nothing>(NAMESPACE, height = height.intValue.dp, onClose = { reports += it }) {
                    ThrowingCleanup()
                },
            )
        }

        onApplication(content) { shell ->
            assertTrue(shell.pumpOrFail(PUMP_MILLIS) { shell.shownSurfaces.isNotEmpty() }, "the surface was never placed")

            height.intValue = TALL

            assertTrue(shell.pumpOrFail(PUMP_MILLIS) { reports.isNotEmpty() }, "the replaced content's crash reported nothing")
            val crash = crashIn(reports.single(), "cleanup that threw as its surface was replaced did not report a crash")
            assertEquals(CLEANUP_FAILURE, crash.failure.cause.message, "the crash did not carry what the cleanup threw")
            shell.pumpOrFail(SETTLE_MILLIS)
            assertTrue(shell.shownSurfaces.isEmpty(), "a surface was placed for a Show whose content had crashed")
        }
    }

    @Test
    fun `a surface that ends in the pass its Show is taken out reports once`() {
        val showing = mutableStateOf(true)
        val reports = CopyOnWriteArrayList<EmptyResult<SurfaceError<Dismissal>>>()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            if (showing.value) {
                val surface = TestSurface<Dismissal>(NAMESPACE, onClose = { reports += it })
                Show(surface)
                // The host closes the surface as it takes the Show out, so the ending and the removal meet in one pass.
                DisposableEffect(Unit) { onDispose { surface.close(Dismissal.Dismissed) } }
            }
        }

        onApplication(content) { shell ->
            assertTrue(shell.pumpOrFail(PUMP_MILLIS) { shell.shownSurfaces.isNotEmpty() }, "the surface was never placed")

            showing.value = false

            assertTrue(shell.pumpOrFail(PUMP_MILLIS) { reports.isNotEmpty() }, "the ending and the removal reported nothing")
            shell.pumpOrFail(SETTLE_MILLIS)
            assertEquals(
                listOf(Err(SurfaceError.Closed(Dismissal.Dismissed))),
                reports.toList(),
                "an ending and a removal in one pass did not report the ending, once",
            )
        }
    }

    /** The crash [report] carries; the test fails with [message] if it is not `Err(Failed(SurfaceCrashed))`. */
    private fun crashIn(report: EmptyResult<SurfaceError<*>>, message: String): KortexError.SurfaceCrashed {
        val failed = assertIs<SurfaceError.Failed>(report.errorOrNull(), "$message: $report")
        return assertIs<KortexError.SurfaceCrashed>(failed.error, "$message: $report")
    }

    @Composable
    private fun ThrowingEffect() {
        LaunchedEffect(Unit) {
            delay(EFFECT_DELAY_MILLIS)
            error(EFFECT_FAILURE)
        }
    }

    @Composable
    private fun ThrowingCleanup() {
        DisposableEffect(Unit) { onDispose { error(CLEANUP_FAILURE) } }
    }

    /** Sets [left] as the caller leaves composition, so a test can wait for its removal to have been queued. */
    @Composable
    private fun OnLeave(left: AtomicBoolean) {
        DisposableEffect(Unit) { onDispose { left.set(true) } }
    }

    private sealed interface Dismissal : IError {
        data object Dismissed : Dismissal
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
        const val SECOND_NAMESPACE = "kortex-show-second"
        const val PUMP_MILLIS = 4_000L
        const val SETTLE_MILLIS = 300L
        const val SHORT = 8
        const val TALL = 16
        const val EFFECT_FAILURE = "an effect threw"
        const val CLEANUP_FAILURE = "cleanup threw as the surface went"
        const val EFFECT_DELAY_MILLIS = 50L

        // Clear of the default speck's corner, so a second surface is told apart on screen too.
        val BOTTOM_LEFT = setOf(Edge.Bottom, Edge.Left)
    }
}
