package com.fromwau.kortex.wayland

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.IError
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.errorOrNull
import com.fromwau.kern.result.getOrElse
import com.fromwau.kern.result.onSuccess
import com.fromwau.kortex.compose.ContentFailure
import com.fromwau.kortex.compose.KortexSurfaceHandle
import com.fromwau.kortex.compose.LocalKortexSurface
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertIsNot
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
            awaitPlaced(shell)

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

            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { reportedTo.isNotEmpty() },
                "taking the Show out reported nothing",
            )
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
            awaitPlaced(shell)
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
    fun `a Show handed an instance of another class replaces its surface, and only the new class can close it`() {
        val showRefusing = mutableStateOf(false)
        val dismissals = CopyOnWriteArrayList<EmptyResult<SurfaceError<Dismissal>>>()
        val refusals = CopyOnWriteArrayList<EmptyResult<SurfaceError<Refusal>>>()
        val instances = CopyOnWriteArrayList<LayerSurface<*>>()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            val surface = when {
                showRefusing.value -> RefusingSurface { refusals += it }
                else -> DismissingSurface { dismissals += it }
            }
            SideEffect { instances += surface }
            Show(surface)
        }

        onApplication(content) { shell ->
            awaitPlaced(shell)
            val first = shell.shownSurfaces.single()
            val dismissing = assertNotNull(instances.filterIsInstance<DismissingSurface>().lastOrNull())

            showRefusing.value = true

            val replaced = shell.pumpOrFail(PUMP_MILLIS) {
                shell.shownSurfaces.singleOrNull()?.let { it !== first } == true
            }
            assertTrue(replaced, "an instance of another class with equal settings did not replace the surface")

            dismissing.close(Dismissal.Dismissed)

            shell.pumpOrFail(SETTLE_MILLIS)
            assertEquals(1, shell.shownSurfaces.size, "close(error) from the class the Show left closed its surface")
            assertTrue(dismissals.isEmpty(), "the replaced class's onClose was called: $dismissals")
            assertTrue(refusals.isEmpty(), "the new class's onClose was called before it closed: $refusals")

            val refusing = assertNotNull(instances.filterIsInstance<RefusingSurface>().lastOrNull())
            refusing.close(Refusal.Refused)

            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { refusals.isNotEmpty() },
                "close(error) on the new class reported nothing",
            )
            assertEquals(
                listOf(Err(SurfaceError.Closed(Refusal.Refused))),
                refusals.toList(),
                "close(error) on the new class did not reach its own onClose once",
            )
            assertTrue(dismissals.isEmpty(), "the replaced class's onClose was called: $dismissals")
        }
    }

    @Test
    fun `a close() that lands as its Show is handed another class does not end the Show when that class returns`() {
        val showRefusing = mutableStateOf(false)
        val dismissals = CopyOnWriteArrayList<EmptyResult<SurfaceError<Dismissal>>>()
        val refusals = CopyOnWriteArrayList<EmptyResult<SurfaceError<Refusal>>>()
        val firstDismissing = AtomicReference<DismissingSurface?>(null)
        val closedAsItLeft = AtomicBoolean(false)
        val content: @Composable KortexApplicationScope.() -> Unit = {
            val surface = when {
                showRefusing.value -> {
                    // After the pass's reconcile, before Show is handed this class: the ask sees its own class shown.
                    if (closedAsItLeft.compareAndSet(false, true)) {
                        Snapshot.withoutReadObservation { firstDismissing.get()?.close() }
                    }
                    RefusingSurface { refusals += it }
                }

                else -> DismissingSurface { dismissals += it }
            }
            SideEffect { if (surface is DismissingSurface) firstDismissing.compareAndSet(null, surface) }
            Show(surface)
        }

        onApplication(content) { shell ->
            awaitPlaced(shell)
            val first = shell.shownSurfaces.single()

            showRefusing.value = true

            val replaced = shell.pumpOrFail(PUMP_MILLIS) {
                shell.shownSurfaces.singleOrNull()?.let { it !== first } == true
            }
            assertTrue(replaced, "an instance of another class did not replace the surface")
            assertTrue(closedAsItLeft.get(), "the first instance's close() never ran")
            val refusing = shell.shownSurfaces.single()

            showRefusing.value = false

            shell.pumpOrFail(PUMP_MILLIS) {
                dismissals.isNotEmpty() || shell.shownSurfaces.singleOrNull()?.let { it !== refusing } == true
            }
            shell.pumpOrFail(SETTLE_MILLIS)
            assertTrue(
                dismissals.isEmpty(),
                "a close() made as the Show left its first class ended it once that class returned: $dismissals",
            )
            assertTrue(refusals.isEmpty(), "the other class's onClose was called: $refusals")
            val last = assertNotNull(shell.shownSurfaces.singleOrNull(), "the Show placed nothing for its first class")
            assertTrue(last !== refusing && last !== first, "the Show's first class, handed back, got no new surface")
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
            awaitPlaced(shell, count = 2)

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
            awaitPlaced(shell)
            generation.intValue = 2
            assertTrue(shell.pumpOrFail(PUMP_MILLIS) { instances.size >= 2 }, "the application built no newer instance")

            instances[0].close()

            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { reportedTo.isNotEmpty() },
                "the older instance's close() reported nothing",
            )
            assertEquals(listOf(2), reportedTo.toList(), "the ending did not reach the newest instance's onClose alone")
            assertTrue(shell.shownSurfaces.isEmpty(), "the older instance's close() left its surface on screen")
        }
    }

    @Test
    fun `close() and close(error) on an instance never handed to a Show do nothing`() {
        val reports = CopyOnWriteArrayList<EmptyResult<SurfaceError<Dismissal>>>()
        val stray = TestSurface<Dismissal>(NAMESPACE, onClose = { reports += it })

        onApplication({ Show(TestSurface<Nothing>(NAMESPACE)) }) { shell ->
            awaitPlaced(shell)

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

            awaitPlaced(shell)
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
    fun `content's first composition reads its surface's logical size`() {
        val composedWith = CopyOnWriteArrayList<IntSize>()

        onApplication({ Show(TestSurface<Nothing>(NAMESPACE) { composedWith += size }) }) { shell ->
            awaitPlaced(shell)
            val geometry = assertNotNull(Screen.awaitGeometry(NAMESPACE), "hyprctl never listed $NAMESPACE")

            assertEquals(
                IntSize(geometry.logicalWidth, geometry.logicalHeight),
                composedWith[0],
                "content's first composition read a size other than its surface's; it composed with $composedWith",
            )
        }
    }

    @Test
    fun `a surface the compositor closes reports Ok, is not replaced, and its Show taken out reports nothing more`() {
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
            awaitPlaced(shell)

            shell.shownSurfaces.single().simulateCompositorClose()

            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { reports.isNotEmpty() },
                "the compositor's close reported nothing",
            )
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
            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { reports.isNotEmpty() },
                "the unplaceable surface reported nothing",
            )
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
            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { 1 in drawn },
                "the other surface stopped drawing after the crash",
            )
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
            awaitPlaced(shell)

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
            awaitPlaced(shell)

            height.intValue = TALL

            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { reports.isNotEmpty() },
                "the replaced content's crash reported nothing",
            )
            val crash = crashIn(
                reports.single(),
                "cleanup that threw as its surface was replaced did not report a crash",
            )
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
            awaitPlaced(shell)

            showing.value = false

            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { reports.isNotEmpty() },
                "the ending and the removal reported nothing",
            )
            shell.pumpOrFail(SETTLE_MILLIS)
            assertEquals(
                listOf(Err(SurfaceError.Closed(Dismissal.Dismissed))),
                reports.toList(),
                "an ending and a removal in one pass did not report the ending, once",
            )
        }
    }

    @Test
    fun `exitApplication from another thread, twice, returns Ok and every onClose gets Ok on the loop thread`() {
        val reports = CopyOnWriteArrayList<EmptyResult<SurfaceError<Nothing>>>()
        val reportingThreads = CopyOnWriteArraySet<Thread>()
        val onClose: (EmptyResult<SurfaceError<Nothing>>) -> Unit = { result ->
            reports += result
            reportingThreads += Thread.currentThread()
        }
        val content: @Composable KortexApplicationScope.() -> Unit = {
            Show(TestSurface(NAMESPACE, onClose = onClose))
            Show(TestSurface(SECOND_NAMESPACE, anchor = BOTTOM_LEFT, onClose = onClose))
        }
        val application = AtomicReference<Thread>()

        val result = LoopThread.runApplication(content) { scope, loop ->
            application.set(loop)
            assertTrue(LoopThread.awaitNamespace(NAMESPACE, present = true), "hyprctl never listed $NAMESPACE")
            assertTrue(
                LoopThread.awaitNamespace(SECOND_NAMESPACE, present = true),
                "hyprctl never listed $SECOND_NAMESPACE",
            )

            scope.exitApplication()
            scope.exitApplication()

            loop.join(LoopThread.JOIN_MILLIS)
            assertFalse(loop.isAlive, "exitApplication did not end the run")
        }

        assertEquals(Ok(Unit), result, "an application ended by exitApplication did not return Ok")
        assertEquals(
            listOf(Ok(Unit), Ok(Unit)),
            reports.toList(),
            "exitApplication did not report Ok to each surface once",
        )
        assertEquals(setOf(application.get()), reportingThreads.toSet(), "an onClose ran off the application's thread")
    }

    @Test
    fun `closing the application reports Ok to every shown surface and returns Ok`() {
        val reports = CopyOnWriteArrayList<EmptyResult<SurfaceError<Nothing>>>()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            Show(TestSurface<Nothing>(NAMESPACE, onClose = { reports += it }))
            Show(TestSurface<Nothing>(SECOND_NAMESPACE, anchor = BOTTOM_LEFT, onClose = { reports += it }))
        }
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use {
            val shell = KortexShell.createApplicationOrFail(display, content)
            awaitPlaced(shell, count = 2)

            assertEquals(Ok(Unit), shell.close(), "closing the application did not return Ok")
            assertEquals(
                listOf(Ok(Unit), Ok(Unit)),
                reports.toList(),
                "closing the application did not report Ok to each surface once",
            )
        }
    }

    @Test
    fun `an application with no surface shown keeps running, and a Show added later still places`() {
        val showing = mutableStateOf(false)
        val content: @Composable KortexApplicationScope.() -> Unit = {
            if (showing.value) Show(TestSurface<Nothing>(NAMESPACE))
        }

        val result = LoopThread.runApplication(content) { _, loop ->
            loop.join(IDLE_MILLIS)
            assertTrue(loop.isAlive, "an application with no surface shown stopped running")

            showing.value = true

            assertTrue(LoopThread.awaitNamespace(NAMESPACE, present = true), "a Show added later never placed")
        }

        assertEquals(Ok(Unit), result, "the application did not return Ok on exitApplication")
    }

    @Test
    fun `the application's content throwing ends the run as ApplicationCrashed, and no onClose is called`() {
        val boom = mutableStateOf(false)
        val reports = CopyOnWriteArrayList<EmptyResult<SurfaceError<Nothing>>>()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            Show(TestSurface<Nothing>(NAMESPACE, onClose = { reports += it }))
            if (boom.value) error(APPLICATION_FAILURE)
        }

        // Compose prints the failure of the composition it ran as well, which is kept off the test's own output.
        val printed = capturingStderr {
            onCrashingApplication(content) { shell ->
                awaitPlaced(shell)

                boom.value = true

                val crash = assertIs<KortexError.ApplicationCrashed>(
                    shell.pump(PUMP_MILLIS).errorOrNull(),
                    "content that threw did not end the run as ApplicationCrashed",
                )
                assertEquals(APPLICATION_FAILURE, crash.cause.message, "the crash did not carry what the content threw")
                crash
            }
        }
        assertTrue(reports.isEmpty(), "an onClose was called after the application crashed: $reports")
        assertTrue(
            APPLICATION_FAILURE in printed,
            "what Compose printed did not name the content's failure: ${printed.take(PRINTED_EXCERPT)}",
        )
    }

    @Test
    fun `an onClose that throws ends the run as ApplicationCrashed, and no other onClose is called`() {
        val closeRequested = mutableStateOf(false)
        val others = CopyOnWriteArrayList<EmptyResult<SurfaceError<Nothing>>>()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            Show(
                TestSurface<Nothing>(NAMESPACE, onClose = { error(ON_CLOSE_FAILURE) }) {
                    val requested = closeRequested.value
                    LaunchedEffect(requested) { if (requested) close() }
                },
            )
            Show(TestSurface<Nothing>(SECOND_NAMESPACE, anchor = BOTTOM_LEFT, onClose = { others += it }))
        }

        onCrashingApplication(content) { shell ->
            awaitPlaced(shell, count = 2)

            closeRequested.value = true

            val crash = assertIs<KortexError.ApplicationCrashed>(
                shell.pump(PUMP_MILLIS).errorOrNull(),
                "an onClose that threw did not end the run as ApplicationCrashed",
            )
            assertEquals(ON_CLOSE_FAILURE, crash.cause.message, "the crash did not carry what onClose threw")
            crash
        }
        assertTrue(others.isEmpty(), "another surface's onClose was called after an onClose threw: $others")
    }

    @Test
    fun `a Show queued in the pass the application crashes in is never placed`() {
        val showSecond = mutableStateOf(false)
        val content: @Composable KortexApplicationScope.() -> Unit = {
            val first = TestSurface<Nothing>(NAMESPACE, onClose = { error(ON_CLOSE_FAILURE) })
            Show(first)
            if (showSecond.value) {
                Show(TestSurface<Nothing>(SECOND_NAMESPACE, anchor = BOTTOM_LEFT))
                // Ending the first as the second is shown brings both to one pass, which reconciles the first first.
                SideEffect { first.close() }
            }
        }

        onCrashingApplication(content) { shell ->
            awaitPlaced(shell)

            showSecond.value = true

            val crash = assertIs<KortexError.ApplicationCrashed>(
                shell.pump(PUMP_MILLIS).errorOrNull(),
                "an onClose that threw did not end the run as ApplicationCrashed",
            )
            assertTrue(shell.shownSurfaces.isEmpty(), "a Show queued in the pass the application crashed in was placed")
            crash
        }
    }

    @Test
    fun `UI placed directly in the application's content ends the run as ApplicationCrashed with no onClose`() {
        val addUi = mutableStateOf(false)
        val reports = CopyOnWriteArrayList<EmptyResult<SurfaceError<Nothing>>>()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            Show(TestSurface<Nothing>(NAMESPACE, onClose = { reports += it }))
            if (addUi.value) Box(Modifier.fillMaxSize())
        }

        val printed = capturingStderr {
            onCrashingApplication(content) { shell ->
                awaitPlaced(shell)

                addUi.value = true

                val crash = assertIs<KortexError.ApplicationCrashed>(
                    shell.pump(PUMP_MILLIS).errorOrNull(),
                    "UI placed in the application's content did not end the run as ApplicationCrashed",
                )
                assertEquals(UI_OUTSIDE_A_SURFACE, crash.cause.message, "the crash was not kortex's rejection of UI")
                crash
            }
        }
        assertTrue(reports.isEmpty(), "an onClose was called after the application crashed: $reports")
        assertTrue(
            UI_OUTSIDE_A_SURFACE in printed,
            "what Compose printed did not name kortex's rejection of UI: ${printed.take(PRINTED_EXCERPT)}",
        )
    }

    @Test
    fun `UI placed in the application's first composition fails to start it as ApplicationCrashed`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use {
            var error: KortexError? = null
            val printed = capturingStderr {
                error = KortexShell
                    .createApplication(display) { Box(Modifier.fillMaxSize()) }
                    .onSuccess { it.close() }
                    .errorOrNull()
            }

            val crash = assertIs<KortexError.ApplicationCrashed>(error, "an application whose content is UI started")
            assertEquals(UI_OUTSIDE_A_SURFACE, crash.cause.message, "the crash was not kortex's rejection of UI")
            assertTrue(
                UI_OUTSIDE_A_SURFACE in printed,
                "what Compose printed did not name kortex's rejection of UI: ${printed.take(PRINTED_EXCERPT)}",
            )
        }
    }

    @Test
    fun `an application needs wl_compositor, wl_shm and zwlr_layer_shell_v1 from the compositor`() {
        val all = listOf("wl_compositor", "wl_shm", "zwlr_layer_shell_v1", "wl_seat")
            .mapIndexed { name, interfaceName -> WaylandGlobal(name, interfaceName, version = 1) }
        fun lacking(interfaceName: String) = all.filterNot { it.interfaceName == interfaceName }

        assertNull(KortexShell.missingSurfaceGlobal(all), "a compositor advertising every global was found lacking one")
        assertEquals(
            KortexError.MissingGlobal("wl_compositor"),
            KortexShell.missingSurfaceGlobal(lacking("wl_compositor")),
            "a compositor without wl_compositor was not found lacking it",
        )
        assertEquals(
            KortexError.MissingGlobal("wl_shm"),
            KortexShell.missingSurfaceGlobal(lacking("wl_shm")),
            "a compositor without wl_shm was not found lacking it",
        )
        assertEquals(
            KortexError.MissingGlobal("zwlr_layer_shell_v1"),
            KortexShell.missingSurfaceGlobal(lacking("zwlr_layer_shell_v1")),
            "a compositor without zwlr_layer_shell_v1 was not found lacking it",
        )
    }

    @Test
    fun `first() on an ArrayList of surfaces returns its first surface`() {
        val surface = TestSurface<Nothing>(NAMESPACE)
        val surfaces = ArrayList<LayerSurface<Nothing>>()
        surfaces.add(surface)

        assertSame<LayerSurface<Nothing>>(surface, surfaces.first(), "first() did not return the list's surface")
    }

    @Test
    fun `a Show inside content places a child, and taking the parent's Show out ends both, each reporting Ok`() {
        val showing = mutableStateOf(true)
        val parentReports = CopyOnWriteArrayList<EmptyResult<SurfaceError<Nothing>>>()
        val childReports = CopyOnWriteArrayList<EmptyResult<SurfaceError<Nothing>>>()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            if (showing.value) {
                Show(TestSurface<Nothing>(NAMESPACE, onClose = { parentReports += it }) { ShowChild(childReports) })
            }
        }

        onApplication(content) { shell ->
            awaitPlaced(shell, count = 2)
            assertNotNull(Screen.awaitGeometry(SECOND_NAMESPACE), "hyprctl never listed the child, $SECOND_NAMESPACE")

            showing.value = false

            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { parentReports.isNotEmpty() && childReports.isNotEmpty() },
                "taking the parent's Show out left a surface unreported: parent $parentReports, child $childReports",
            )
            shell.pumpOrFail(SETTLE_MILLIS)
            assertEquals(listOf(Ok(Unit)), parentReports.toList(), "the parent did not report Ok once")
            assertEquals(listOf(Ok(Unit)), childReports.toList(), "the child did not report Ok once")
            assertTrue(shell.shownSurfaces.isEmpty(), "a surface outlived the parent's Show")
        }
    }

    @Test
    fun `a parent whose content crashes reports the crash, and the child it showed reports Ok`() {
        val crashing = mutableStateOf(false)
        val parentReports = CopyOnWriteArrayList<EmptyResult<SurfaceError<Nothing>>>()
        val childReports = CopyOnWriteArrayList<EmptyResult<SurfaceError<Nothing>>>()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            Show(
                TestSurface<Nothing>(NAMESPACE, onClose = { parentReports += it }) {
                    ShowChild(childReports)
                    val crashNow = crashing.value
                    LaunchedEffect(crashNow) { if (crashNow) error(EFFECT_FAILURE) }
                },
            )
        }

        onApplication(content) { shell ->
            awaitPlaced(shell, count = 2)

            crashing.value = true

            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { parentReports.isNotEmpty() && childReports.isNotEmpty() },
                "the parent's crash left a surface unreported: parent $parentReports, child $childReports",
            )
            shell.pumpOrFail(SETTLE_MILLIS)
            val crash = crashIn(parentReports.single(), "the parent's crash did not report Failed(SurfaceCrashed)")
            assertEquals(EFFECT_FAILURE, crash.failure.cause.message, "the crash did not carry what the effect threw")
            assertEquals(
                listOf(Ok(Unit)),
                childReports.toList(),
                "the child of a crashed parent did not report Ok once",
            )
            assertTrue(shell.shownSurfaces.isEmpty(), "a surface outlived its crashed parent")
        }
    }

    @Test
    fun `a parent whose cleanup throws as it goes, composed after its child, reports the crash and the child Ok`() =
        takeOutParentWithThrowingCleanup(ParentContent.ChildThenCleanup)

    @Test
    fun `a parent whose cleanup throws as it goes, composed before its child, reports the crash and the child Ok`() =
        takeOutParentWithThrowingCleanup(ParentContent.CleanupThenChild)

    @Test
    fun `a parent whose composable body throws reports the crash, and the child it showed reports Ok`() {
        val crashing = mutableStateOf(false)
        val parentReports = CopyOnWriteArrayList<EmptyResult<SurfaceError<Nothing>>>()
        val childReports = CopyOnWriteArrayList<EmptyResult<SurfaceError<Nothing>>>()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            Show(
                TestSurface<Nothing>(NAMESPACE, onClose = { parentReports += it }) {
                    ShowChild(childReports)
                    if (crashing.value) error(BODY_FAILURE)
                },
            )
        }

        // Compose prints the failure of the composition it ran as well, which is kept off the test's own output.
        val printed = capturingStderr {
            onApplication(content) { shell ->
                awaitPlaced(shell, count = 2)

                crashing.value = true

                assertTrue(
                    shell.pumpOrFail(PUMP_MILLIS) { parentReports.isNotEmpty() && childReports.isNotEmpty() },
                    "the parent's crash left a surface unreported: parent $parentReports, child $childReports",
                )
                shell.pumpOrFail(SETTLE_MILLIS)
                assertTrue(shell.shownSurfaces.isEmpty(), "a surface outlived its crashed parent")
            }
        }
        val crash = crashIn(parentReports.single(), "a parent whose body threw did not report Failed(SurfaceCrashed)")
        assertEquals(BODY_FAILURE, crash.failure.cause.message, "the crash did not carry what the body threw")
        assertEquals(listOf(Ok(Unit)), childReports.toList(), "the child of a crashed parent did not report Ok once")
        assertTrue(
            BODY_FAILURE in printed,
            "what Compose printed did not name the body's failure: ${printed.take(PRINTED_EXCERPT)}",
        )
    }

    @Test
    fun `exitApplication reports Ok once to a surface and to the surface its content showed`() {
        val parentReports = CopyOnWriteArrayList<EmptyResult<SurfaceError<Nothing>>>()
        val childReports = CopyOnWriteArrayList<EmptyResult<SurfaceError<Nothing>>>()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            Show(TestSurface<Nothing>(NAMESPACE, onClose = { parentReports += it }) { ShowChild(childReports) })
        }

        val result = LoopThread.runApplication(content) { scope, loop ->
            assertTrue(LoopThread.awaitNamespace(NAMESPACE, present = true), "hyprctl never listed $NAMESPACE")
            assertTrue(
                LoopThread.awaitNamespace(SECOND_NAMESPACE, present = true),
                "hyprctl never listed the child, $SECOND_NAMESPACE",
            )

            scope.exitApplication()

            loop.join(LoopThread.JOIN_MILLIS)
            assertFalse(loop.isAlive, "exitApplication did not end the run")
        }

        assertEquals(Ok(Unit), result, "an application ended by exitApplication did not return Ok")
        assertEquals(listOf(Ok(Unit)), parentReports.toList(), "exitApplication did not report Ok to the parent once")
        assertEquals(listOf(Ok(Unit)), childReports.toList(), "exitApplication did not report Ok to the child once")
    }

    @Test
    fun `LocalKortexSurface below a surface's invoke() is its instance, and closing it closes the surface`() {
        val closeRequested = mutableStateOf(false)
        val instance = AtomicReference<LayerSurface<*>?>(null)
        val below = AtomicReference<KortexSurfaceHandle?>(null)
        val reports = CopyOnWriteArrayList<EmptyResult<SurfaceError<Nothing>>>()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            val surface = TestSurface<Nothing>(NAMESPACE, onClose = { reports += it }) {
                SurfaceBelow(below, closeRequested)
            }
            SideEffect { instance.set(surface) }
            Show(surface)
        }

        onApplication(content) { shell ->
            assertTrue(shell.pumpOrFail(PUMP_MILLIS) { below.get() != null }, "the surface's content never composed")
            assertSame<Any?>(
                instance.get(),
                below.get(),
                "LocalKortexSurface below invoke() was not the surface's instance",
            )

            closeRequested.value = true

            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { reports.isNotEmpty() },
                "closing LocalKortexSurface reported nothing",
            )
            assertEquals(listOf(Ok(Unit)), reports.toList(), "closing LocalKortexSurface did not report Ok once")
            assertTrue(shell.shownSurfaces.isEmpty(), "closing LocalKortexSurface left the surface on screen")
        }
    }

    @Test
    fun `content reaches the shell's clipboard through LocalKortexClipboard, failures included`() {
        val clipboard = FakeTextClipboard(read = { Ok(OTHER_CLIENTS) }, set = { Err(ClipboardError.NoInputSerial) })
        val results = CopyOnWriteArrayList<Result<Any, ClipboardError>>()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            Show(
                TestSurface<Nothing>(NAMESPACE) {
                    val typed = LocalKortexClipboard.current
                    LaunchedEffect(typed) {
                        results += typed.readText()
                        results += typed.setText(COPIED)
                        results += typed.clear()
                    }
                },
            )
        }
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use {
            val shell = KortexShell
                .createApplication(display, contentClipboard = { clipboard }, content = content)
                .getOrElse { error -> fail("the application failed to start: $error") }
            shell.useOrFail {
                assertTrue(
                    shell.pumpOrFail(PUMP_MILLIS) { results.size == CLIPBOARD_CALLS },
                    "content's clipboard calls never returned",
                )
            }
        }
        assertEquals(
            listOf(Ok(OTHER_CLIENTS), Err(ClipboardError.NoInputSerial), Ok(Unit)),
            results.toList(),
            "content did not get the shell's clipboard's own results",
        )
        assertEquals(listOf(COPIED), clipboard.setTexts.toList(), "the copy did not reach the shell's clipboard")
        assertEquals(1, clipboard.clears.get(), "the clear did not reach the shell's clipboard")
    }

    @Test
    fun `content's LocalKortexClipboard cannot close the shell's clipboard`() {
        val typed = AtomicReference<KortexClipboard?>(null)
        val content: @Composable KortexApplicationScope.() -> Unit = {
            Show(TestSurface<Nothing>(NAMESPACE) { typed.set(LocalKortexClipboard.current) })
        }

        onApplication(content) { shell ->
            assertTrue(shell.pumpOrFail(PUMP_MILLIS) { typed.get() != null }, "content was handed no clipboard")
            assertIsNot<AutoCloseable>(typed.get(), "content was handed a clipboard it could close")
        }
    }

    /**
     * Starts an application of [content] and hands it to [crash], which drives it into a crash and returns it; closing
     * the application must then return that same crash.
     */
    private fun onCrashingApplication(
        content: @Composable KortexApplicationScope.() -> Unit,
        crash: (KortexShell) -> KortexError.ApplicationCrashed,
    ) {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }
        display.use {
            val shell = KortexShell.createApplicationOrFail(display, content)
            val crashed = try {
                crash(shell)
            } catch (failure: Throwable) {
                shell.close()
                throw failure
            }
            assertEquals(Err(crashed), shell.close(), "closing a crashed application did not return its crash")
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

    /** Shows a speck under [SECOND_NAMESPACE] from the surface content it is called in, reporting to [reports]. */
    @Composable
    private fun ShowChild(reports: MutableList<EmptyResult<SurfaceError<Nothing>>>) {
        Show(TestSurface<Nothing>(SECOND_NAMESPACE, anchor = BOTTOM_LEFT, onClose = { reports += it }))
    }

    /** Hands [seen] the LocalKortexSurface it finds, and closes that surface once [requested] turns true. */
    @Composable
    private fun SurfaceBelow(
        seen: AtomicReference<KortexSurfaceHandle?>,
        requested: MutableState<Boolean>,
    ) {
        val surface = LocalKortexSurface.current
        SideEffect { seen.set(surface) }
        CloseWhen(requested)
    }

    /**
     * Shows a parent whose content holds a child's Show and cleanup that throws, composed in [order], then takes the
     * parent's Show out: the parent must report the crash and the child `Ok(Unit)`, whichever Compose disposes first.
     */
    private fun takeOutParentWithThrowingCleanup(order: ParentContent) {
        val showing = mutableStateOf(true)
        val parentReports = CopyOnWriteArrayList<EmptyResult<SurfaceError<Nothing>>>()
        val childReports = CopyOnWriteArrayList<EmptyResult<SurfaceError<Nothing>>>()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            if (showing.value) {
                Show(
                    TestSurface<Nothing>(NAMESPACE, onClose = { parentReports += it }) {
                        when (order) {
                            ParentContent.ChildThenCleanup -> {
                                ShowChild(childReports)
                                ThrowingCleanup()
                            }
                            ParentContent.CleanupThenChild -> {
                                ThrowingCleanup()
                                ShowChild(childReports)
                            }
                        }
                    },
                )
            }
        }

        onApplication(content) { shell ->
            awaitPlaced(shell, count = 2)

            showing.value = false

            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { parentReports.isNotEmpty() && childReports.isNotEmpty() },
                "taking the parent's Show out left a surface unreported: parent $parentReports, child $childReports",
            )
            shell.pumpOrFail(SETTLE_MILLIS)
            val crash = crashIn(parentReports.single(), "the parent's cleanup that threw did not report a crash")
            assertEquals(CLEANUP_FAILURE, crash.failure.cause.message, "the crash did not carry what the cleanup threw")
            assertEquals(listOf(Ok(Unit)), childReports.toList(), "the child did not report Ok once")
            assertTrue(shell.shownSurfaces.isEmpty(), "a surface outlived the parent's Show")
        }
    }

    /** In which order a parent's content composes its child's Show and its cleanup that throws. */
    private enum class ParentContent {
        ChildThenCleanup,
        CleanupThenChild,
    }

    private sealed interface Dismissal : IError {
        data object Dismissed : Dismissal
    }

    private sealed interface Refusal : IError {
        data object Refused : Refusal
    }

    /** A speck in the default corner under [NAMESPACE]: the settings every subclass below shares. */
    private abstract class SpeckSurface<E : IError>(onClose: (EmptyResult<SurfaceError<E>>) -> Unit) :
        LayerSurface<E>(
            namespace = NAMESPACE,
            layer = Layer.Overlay,
            anchor = setOf(Edge.Bottom, Edge.Right),
            width = SHORT.dp,
            height = SHORT.dp,
            onClose = onClose,
        )

    /** Draws the [label] it was built with, so a test can tell which instance's content its surface composes. */
    private class LabelSurface(
        private val label: Int,
        private val drawn: MutableList<Int>,
        onClose: (EmptyResult<SurfaceError<Nothing>>) -> Unit,
    ) : SpeckSurface<Nothing>(onClose) {
        @Composable
        override fun invoke() {
            Canvas(Modifier.fillMaxSize()) { drawn += label }
        }
    }

    // Two classes with equal settings and an error of each's own, for one Show handed first one, then the other.
    private class DismissingSurface(
        onClose: (EmptyResult<SurfaceError<Dismissal>>) -> Unit,
    ) : SpeckSurface<Dismissal>(onClose) {
        @Composable
        override fun invoke() = Unit
    }

    private class RefusingSurface(
        onClose: (EmptyResult<SurfaceError<Refusal>>) -> Unit,
    ) : SpeckSurface<Refusal>(onClose) {
        @Composable
        override fun invoke() = Unit
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
        const val APPLICATION_FAILURE = "the application's content threw"
        const val ON_CLOSE_FAILURE = "an onClose threw"
        const val EFFECT_DELAY_MILLIS = 50L
        const val IDLE_MILLIS = 500L
        const val PRINTED_EXCERPT = 300
        const val BODY_FAILURE = "a surface's content threw as it composed"
        const val COPIED = "copied in kortex"
        const val OTHER_CLIENTS = "from another client"
        const val CLIPBOARD_CALLS = 3

        // Clear of the default speck's corner, so a second surface is told apart on screen too.
        val BOTTOM_LEFT = setOf(Edge.Bottom, Edge.Left)
    }
}
