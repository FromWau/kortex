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
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

/** A surface a call keeps on screen in an application composition, and how each of its endings reaches `onClose`. */
class SurfaceTest {
    @Test
    fun `a surface call places its surface one pass after it enters composition, under its namespace as written`() {
        onApplication({ TestSurface<Nothing>(NAMESPACE) }) { shell ->
            assertTrue(shell.shownSurfaces.isEmpty(), "the call placed its surface inside composition")

            shell.passOrFail()

            assertEquals(1, shell.shownSurfaces.size, "the pass after the call entered composition placed no surface")
            assertNotNull(Screen.awaitGeometry(NAMESPACE), "hyprctl never listed $NAMESPACE, its namespace as written")
        }
    }

    @Test
    fun `taking a surface call out of composition removes its surface and reports LeftComposition once`() {
        val showing = mutableStateOf(true)
        val reports = CopyOnWriteArrayList<Result<SurfaceEnd, SurfaceError<Nothing>>>()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            if (showing.value) TestSurface<Nothing>(NAMESPACE, onClose = { reports += it })
        }

        onApplication(content) { shell ->
            awaitPlaced(shell)

            showing.value = false

            assertTrue(shell.pumpOrFail(PUMP_MILLIS) { reports.isNotEmpty() }, "taking the call out reported nothing")
            shell.pumpOrFail(SETTLE_MILLIS)
            assertEquals(
                listOf(Ok(SurfaceEnd.LeftComposition)),
                reports.toList(),
                "taking the call out did not report LeftComposition exactly once",
            )
            assertTrue(shell.shownSurfaces.isEmpty(), "the surface outlived its call")
            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { Screen.geometry(NAMESPACE) == null },
                "hyprctl still lists $NAMESPACE after its call left composition",
            )
        }
    }

    @Test
    fun `one call keeps its surface while its arguments change, and its newest onClose gets the report`() {
        val showing = mutableStateOf(true)
        val label = mutableIntStateOf(FIRST_LABEL)
        val drawn = CopyOnWriteArrayList<Int>()
        val reportedTo = CopyOnWriteArrayList<Int>()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            if (showing.value) {
                val current = label.intValue
                TestSurface<Nothing>(NAMESPACE, onClose = { reportedTo += current }) {
                    Canvas(Modifier.fillMaxSize()) { drawn += current }
                }
            }
        }

        onApplication(content) { shell ->
            assertTrue(shell.pumpOrFail(PUMP_MILLIS) { FIRST_LABEL in drawn }, "the first content never drew")
            val surface = shell.shownSurfaces.single()

            label.intValue = SECOND_LABEL

            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { SECOND_LABEL in drawn },
                "the surface never drew the newest content",
            )
            assertSame(
                surface,
                shell.shownSurfaces.singleOrNull(),
                "changed arguments did not leave exactly the surface the call started with",
            )

            showing.value = false

            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { reportedTo.isNotEmpty() },
                "taking the call out reported nothing",
            )
            assertEquals(
                listOf(SECOND_LABEL),
                reportedTo.toList(),
                "the ending did not reach the newest onClose alone",
            )
        }
    }

    @Test
    fun `another surface call in the same place is another surface, and the first reports LeftComposition`() {
        val showSecond = mutableStateOf(false)
        val firstReports = CopyOnWriteArrayList<Result<SurfaceEnd, SurfaceError<Nothing>>>()
        val secondReports = CopyOnWriteArrayList<Result<SurfaceEnd, SurfaceError<Nothing>>>()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            when {
                showSecond.value -> TestSurface<Nothing>(SECOND_NAMESPACE, onClose = { secondReports += it })
                else -> TestSurface<Nothing>(NAMESPACE, onClose = { firstReports += it })
            }
        }

        onApplication(content) { shell ->
            awaitPlaced(shell)
            val first = shell.shownSurfaces.single()

            showSecond.value = true

            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { firstReports.isNotEmpty() },
                "the call the other one took the place of reported nothing",
            )
            assertEquals(
                listOf(Ok(SurfaceEnd.LeftComposition)),
                firstReports.toList(),
                "the call taken out did not report LeftComposition once",
            )
            val placedItsOwn = shell.pumpOrFail(PUMP_MILLIS) {
                shell.shownSurfaces.singleOrNull()?.let { it !== first } == true
            }
            assertTrue(placedItsOwn, "the other call did not place a surface of its own")
            assertNotNull(Screen.awaitGeometry(SECOND_NAMESPACE), "hyprctl never listed $SECOND_NAMESPACE")
            shell.pumpOrFail(SETTLE_MILLIS)
            assertTrue(secondReports.isEmpty(), "the other call reported $secondReports")
        }
    }

    @Test
    fun `close() reports Ok(Closed), and close(error) reports Err(Closed(error))`() {
        val closeRequested = mutableStateOf(false)
        val plain = CopyOnWriteArrayList<Result<SurfaceEnd, SurfaceError<Nothing>>>()
        val withError = CopyOnWriteArrayList<Result<SurfaceEnd, SurfaceError<Dismissal>>>()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            TestSurface<Nothing>(NAMESPACE, onClose = { plain += it }) {
                val requested = closeRequested.value
                LaunchedEffect(requested) { if (requested) close() }
            }
            TestSurface<Dismissal>(SECOND_NAMESPACE, anchor = BOTTOM_LEFT, onClose = { withError += it }) {
                val requested = closeRequested.value
                LaunchedEffect(requested) { if (requested) close(Dismissal.Dismissed) }
            }
        }

        onApplication(content) { shell ->
            awaitPlaced(shell, count = 2)

            closeRequested.value = true

            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { plain.isNotEmpty() && withError.isNotEmpty() },
                "closing from content left a surface unreported: close() $plain, close(error) $withError",
            )
            assertEquals(listOf(Ok(SurfaceEnd.Closed)), plain.toList(), "close() did not report Closed once")
            assertEquals(
                listOf(Err(SurfaceError.Closed(Dismissal.Dismissed))),
                withError.toList(),
                "close(error) did not report the error as Closed once",
            )
            assertTrue(shell.shownSurfaces.isEmpty(), "a closed surface stayed on screen")
        }
    }

    @Test
    fun `close() from another thread ends a surface whose loop is asleep`() {
        val scope = AtomicReference<SurfaceScope<Nothing>?>(null)
        val reports = CopyOnWriteArrayList<Result<SurfaceEnd, SurfaceError<Nothing>>>()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            TestSurface<Nothing>(NAMESPACE, onClose = { reports += it }) {
                val own = this
                SideEffect { scope.set(own) }
            }
        }

        val result = LoopThread.runApplication(content) { _, _ ->
            assertTrue(LoopThread.awaitNamespace(NAMESPACE, present = true), "hyprctl never listed $NAMESPACE")
            // Once the loop has gone quiet, so nothing but the close itself can wake it.
            Thread.sleep(QUIET_MILLIS)
            val own = assertNotNull(scope.get(), "the surface's content never composed")

            own.close()

            assertTrue(
                LoopThread.awaitNamespace(NAMESPACE, present = false),
                "a close() from another thread never reached the sleeping loop",
            )
        }

        assertEquals(Ok(Unit), result, "an application whose surface closed itself did not return Ok")
        assertEquals(listOf(Ok(SurfaceEnd.Closed)), reports.toList(), "close() did not report Closed once")
    }

    @Test
    fun `size from the scope and from LocalKortexSurface is the logical size while shown, and zero once ended`() {
        val showing = mutableStateOf(true)
        val scope = AtomicReference<SurfaceScope<Nothing>?>(null)
        val below = AtomicReference<KortexSurfaceHandle?>(null)
        val content: @Composable KortexApplicationScope.() -> Unit = {
            if (showing.value) {
                TestSurface<Nothing>(NAMESPACE) {
                    val own = this
                    val handle = LocalKortexSurface.current
                    SideEffect {
                        scope.set(own)
                        below.set(handle)
                    }
                }
            }
        }

        onApplication(content) { shell ->
            awaitPlaced(shell)
            val geometry = assertNotNull(Screen.awaitGeometry(NAMESPACE), "hyprctl never listed $NAMESPACE")
            val own = assertNotNull(scope.get(), "the surface's content never composed")
            val handle = assertNotNull(below.get(), "content below never saw a LocalKortexSurface")
            val logical = IntSize(geometry.logicalWidth, geometry.logicalHeight)
            assertEquals(logical, own.size, "the scope's size was not the logical size hyprctl reports")
            assertEquals(logical, handle.size, "LocalKortexSurface's size was not the logical size hyprctl reports")

            showing.value = false

            assertTrue(shell.pumpOrFail(PUMP_MILLIS) { shell.shownSurfaces.isEmpty() }, "the surface outlived its call")
            assertEquals(IntSize.Zero, own.size, "the scope's size was not zero once the surface had ended")
            assertEquals(IntSize.Zero, handle.size, "LocalKortexSurface's size was not zero once the surface ended")
        }
    }

    @Test
    fun `content's first composition reads its surface's logical size`() {
        val composedWith = CopyOnWriteArrayList<IntSize>()

        onApplication({ TestSurface<Nothing>(NAMESPACE) { composedWith += size } }) { shell ->
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
    fun `a surface the compositor closes reports ClosedByCompositor, and nothing takes its place or reports again`() {
        val showing = mutableStateOf(true)
        val left = AtomicBoolean(false)
        val reports = CopyOnWriteArrayList<Result<SurfaceEnd, SurfaceError<Nothing>>>()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            if (showing.value) {
                TestSurface<Nothing>(NAMESPACE, onClose = { reports += it })
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
            assertEquals(
                listOf(Ok(SurfaceEnd.ClosedByCompositor)),
                reports.toList(),
                "the compositor's close did not report ClosedByCompositor once",
            )
            shell.pumpOrFail(SETTLE_MILLIS)
            assertTrue(shell.shownSurfaces.isEmpty(), "a surface the compositor closed was placed again")

            showing.value = false
            assertTrue(shell.pumpOrFail(PUMP_MILLIS) { left.get() }, "the call never left composition")
            shell.passOrFail()

            assertEquals(
                listOf(Ok(SurfaceEnd.ClosedByCompositor)),
                reports.toList(),
                "taking out a call whose surface had ended reported again",
            )
        }
    }

    @Test
    fun `a surface that cannot be placed reports Failed with the reason, and the run goes on`() {
        val reports = CopyOnWriteArrayList<Result<SurfaceEnd, SurfaceError<Nothing>>>()
        val unplaceable: @Composable KortexApplicationScope.() -> Unit = {
            TestSurface<Nothing>(NAMESPACE, anchor = emptySet(), width = 0.dp, onClose = { reports += it })
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
    fun `a surface with a width below 0 reports Failed with NegativeSize once, and the run goes on`() {
        val reports = CopyOnWriteArrayList<Result<SurfaceEnd, SurfaceError<Nothing>>>()
        val negative: @Composable KortexApplicationScope.() -> Unit = {
            // A corner anchor: should the width go out, Hyprland answers its commit by ending the connection.
            TestSurface<Nothing>(NAMESPACE, width = NEGATIVE_WIDTH.dp, onClose = { reports += it })
        }

        onApplication(negative) { shell ->
            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { reports.isNotEmpty() },
                "the surface with a width below 0 reported nothing",
            )
            shell.pumpOrFail(SETTLE_MILLIS)
            assertEquals(
                listOf(Err(SurfaceError.Failed(KortexError.NegativeSize(Axis.Horizontal, NEGATIVE_WIDTH)))),
                reports.toList(),
                "a surface with a width below 0 did not report Failed with NegativeSize once",
            )
            assertTrue(shell.shownSurfaces.isEmpty(), "a surface with a width below 0 is listed as shown")
        }
    }

    @Test
    fun `content whose effect throws reports Failed with the crash, and another shown surface keeps drawing`() {
        val reports = CopyOnWriteArrayList<Result<SurfaceEnd, SurfaceError<Nothing>>>()
        val tick = mutableIntStateOf(0)
        val drawn = CopyOnWriteArrayList<Int>()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            TestSurface<Nothing>(NAMESPACE, onClose = { reports += it }) { ThrowingEffect() }
            TestSurface<Nothing>(SECOND_NAMESPACE, anchor = BOTTOM_LEFT) {
                Canvas(Modifier.fillMaxSize()) { drawn += tick.intValue }
            }
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
    fun `content whose cleanup throws as its call is taken out reports the crash, not Ok`() {
        val showing = mutableStateOf(true)
        val reports = CopyOnWriteArrayList<Result<SurfaceEnd, SurfaceError<Nothing>>>()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            if (showing.value) TestSurface<Nothing>(NAMESPACE, onClose = { reports += it }) { ThrowingCleanup() }
        }

        onApplication(content) { shell ->
            awaitPlaced(shell)

            showing.value = false

            assertTrue(shell.pumpOrFail(PUMP_MILLIS) { reports.isNotEmpty() }, "taking the call out reported nothing")
            val crash = crashIn(reports.single(), "cleanup that threw as its call was taken out did not report a crash")
            assertEquals(CLEANUP_FAILURE, crash.failure.cause.message, "the crash did not carry what the cleanup threw")
        }
    }

    @Test
    fun `content stays composed under a new namespace, so its cleanup does not run and nothing is reported`() {
        val namespace = mutableStateOf(NAMESPACE)
        val reports = CopyOnWriteArrayList<Result<SurfaceEnd, SurfaceError<Nothing>>>()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            TestSurface<Nothing>(namespace.value, onClose = { reports += it }) { ThrowingCleanup() }
        }

        onApplication(content) { shell ->
            awaitPlaced(shell)

            namespace.value = SECOND_NAMESPACE

            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { Screen.geometry(SECOND_NAMESPACE) != null },
                "hyprctl never listed the surface under $SECOND_NAMESPACE",
            )
            shell.pumpOrFail(SETTLE_MILLIS)
            assertEquals(emptyList(), reports.toList(), "a new namespace ran the content's cleanup and reported it")
            assertEquals(1, shell.shownSurfaces.size, "the call was left without a surface under its new namespace")
        }
    }

    @Test
    fun `a surface that ends in the pass its call is taken out reports once`() {
        val showing = mutableStateOf(true)
        val scope = AtomicReference<SurfaceScope<Dismissal>?>(null)
        val reports = CopyOnWriteArrayList<Result<SurfaceEnd, SurfaceError<Dismissal>>>()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            if (showing.value) {
                TestSurface<Dismissal>(NAMESPACE, onClose = { reports += it }) {
                    val own = this
                    SideEffect { scope.set(own) }
                }
                // The host closes the surface as it takes the call out, so the ending and the removal meet in one pass.
                DisposableEffect(Unit) { onDispose { scope.get()?.close(Dismissal.Dismissed) } }
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
    fun `exitApplication from another thread, twice, returns Ok and reports LeftComposition on the loop thread`() {
        val reports = CopyOnWriteArrayList<Result<SurfaceEnd, SurfaceError<Nothing>>>()
        val reportingThreads = CopyOnWriteArraySet<Thread>()
        val onClose: (Result<SurfaceEnd, SurfaceError<Nothing>>) -> Unit = { result ->
            reports += result
            reportingThreads += Thread.currentThread()
        }
        val content: @Composable KortexApplicationScope.() -> Unit = {
            TestSurface(NAMESPACE, onClose = onClose)
            TestSurface(SECOND_NAMESPACE, anchor = BOTTOM_LEFT, onClose = onClose)
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
            listOf(Ok(SurfaceEnd.LeftComposition), Ok(SurfaceEnd.LeftComposition)),
            reports.toList(),
            "exitApplication did not report LeftComposition to each surface once",
        )
        assertEquals(setOf(application.get()), reportingThreads.toSet(), "an onClose ran off the application's thread")
    }

    @Test
    fun `closing the application reports LeftComposition to every shown surface and returns Ok`() {
        val reports = CopyOnWriteArrayList<Result<SurfaceEnd, SurfaceError<Nothing>>>()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            TestSurface<Nothing>(NAMESPACE, onClose = { reports += it })
            TestSurface<Nothing>(SECOND_NAMESPACE, anchor = BOTTOM_LEFT, onClose = { reports += it })
        }
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use {
            val shell = KortexShell.createApplicationOrFail(display, content)
            awaitPlaced(shell, count = 2)

            assertEquals(Ok(Unit), shell.close(), "closing the application did not return Ok")
            assertEquals(
                listOf(Ok(SurfaceEnd.LeftComposition), Ok(SurfaceEnd.LeftComposition)),
                reports.toList(),
                "closing the application did not report LeftComposition to each surface once",
            )
        }
    }

    @Test
    fun `a connection that dies under the run ends it with its error, which each shown surface reports as Failed`() {
        val reports = CopyOnWriteArrayList<Result<SurfaceEnd, SurfaceError<Nothing>>>()
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }
        val content: @Composable KortexApplicationScope.() -> Unit = {
            TestSurface<Nothing>(NAMESPACE, onClose = { reports += it }) {
                LaunchedEffect(Unit) { killConnection(display) }
            }
            // Ends a run the kill failed to end, which then returns Ok and fails the test.
            LaunchedEffect(Unit) {
                delay(PUMP_MILLIS)
                exitApplication()
            }
        }

        display.use {
            val shell = KortexShell.createApplicationOrFail(display, content)
            val run = shell.runEventLoop()
            assertEquals(Ok(Unit), shell.close(), "closing the application on a dead connection did not return Ok")

            val violation = assertIs<KortexError.ProtocolViolation>(
                run.errorOrNull(),
                "a run whose connection died did not end with its protocol error: $run",
            )
            assertEquals("wl_registry", violation.interfaceName, "the run's protocol error named another object")
            assertEquals(
                listOf(Err(SurfaceError.Failed(violation))),
                reports.toList(),
                "the shown surface did not report the connection's error as Failed once",
            )
        }
    }

    @Test
    fun `an onClose that throws on a dead connection returns ApplicationCrashed, and no other onClose is called`() {
        val killRequested = mutableStateOf(false)
        val others = CopyOnWriteArrayList<Result<SurfaceEnd, SurfaceError<Nothing>>>()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            val shell = LocalKortexShell.current
            TestSurface<Nothing>(NAMESPACE, onClose = { error(ON_CLOSE_FAILURE) }) {
                val kill = killRequested.value
                LaunchedEffect(kill) { if (kill) killConnection(shell.display) }
            }
            TestSurface<Nothing>(SECOND_NAMESPACE, anchor = BOTTOM_LEFT, onClose = { others += it })
        }

        val result = LoopThread.runApplication(content) { _, loop ->
            // Both on screen first: a connection killed as the first is placed takes the second's placement with it.
            assertTrue(
                LoopThread.awaitNamespace(NAMESPACE, present = true),
                "hyprctl layers never reported $NAMESPACE",
            )
            assertTrue(
                LoopThread.awaitNamespace(SECOND_NAMESPACE, present = true),
                "hyprctl layers never reported $SECOND_NAMESPACE",
            )

            killRequested.value = true

            loop.join(LoopThread.JOIN_MILLIS)
            assertFalse(loop.isAlive, "the connection dying did not end the run")
        }

        val crash = assertIs<KortexError.ApplicationCrashed>(
            result.errorOrNull(),
            "an onClose that threw on a dead connection did not return ApplicationCrashed: $result",
        )
        assertEquals(ON_CLOSE_FAILURE, crash.cause.message, "the crash did not carry what onClose threw")
        assertTrue(others.isEmpty(), "another surface's onClose was called after an onClose threw: $others")
    }

    @Test
    fun `an application with no surface shown keeps running, and a call added later still places`() {
        val showing = mutableStateOf(false)
        val content: @Composable KortexApplicationScope.() -> Unit = {
            if (showing.value) TestSurface<Nothing>(NAMESPACE)
        }

        val result = LoopThread.runApplication(content) { _, loop ->
            loop.join(IDLE_MILLIS)
            assertTrue(loop.isAlive, "an application with no surface shown stopped running")

            showing.value = true

            assertTrue(LoopThread.awaitNamespace(NAMESPACE, present = true), "a call added later never placed")
        }

        assertEquals(Ok(Unit), result, "the application did not return Ok on exitApplication")
    }

    @Test
    fun `the application's content throwing ends the run as ApplicationCrashed, and no onClose is called`() {
        val boom = mutableStateOf(false)
        val reports = CopyOnWriteArrayList<Result<SurfaceEnd, SurfaceError<Nothing>>>()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            TestSurface<Nothing>(NAMESPACE, onClose = { reports += it })
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
        val others = CopyOnWriteArrayList<Result<SurfaceEnd, SurfaceError<Nothing>>>()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            TestSurface<Nothing>(NAMESPACE, onClose = { error(ON_CLOSE_FAILURE) }) {
                val requested = closeRequested.value
                LaunchedEffect(requested) { if (requested) close() }
            }
            TestSurface<Nothing>(SECOND_NAMESPACE, anchor = BOTTOM_LEFT, onClose = { others += it })
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
    fun `a surface queued in the pass the application crashes in is never placed`() {
        val showSecond = mutableStateOf(false)
        val firstScope = AtomicReference<SurfaceScope<Nothing>?>(null)
        val content: @Composable KortexApplicationScope.() -> Unit = {
            TestSurface<Nothing>(NAMESPACE, onClose = { error(ON_CLOSE_FAILURE) }) {
                val own = this
                SideEffect { firstScope.set(own) }
            }
            if (showSecond.value) {
                TestSurface<Nothing>(SECOND_NAMESPACE, anchor = BOTTOM_LEFT)
                // Ending the first as the second is shown brings both to one pass, which reconciles the first first.
                SideEffect { firstScope.get()?.close() }
            }
        }

        onCrashingApplication(content) { shell ->
            awaitPlaced(shell)

            showSecond.value = true

            val crash = assertIs<KortexError.ApplicationCrashed>(
                shell.pump(PUMP_MILLIS).errorOrNull(),
                "an onClose that threw did not end the run as ApplicationCrashed",
            )
            assertTrue(
                shell.shownSurfaces.isEmpty(),
                "a surface queued in the pass the application crashed in was placed",
            )
            crash
        }
    }

    @Test
    fun `UI placed directly in the application's content ends the run as ApplicationCrashed with no onClose`() {
        val addUi = mutableStateOf(false)
        val reports = CopyOnWriteArrayList<Result<SurfaceEnd, SurfaceError<Nothing>>>()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            TestSurface<Nothing>(NAMESPACE, onClose = { reports += it })
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

        assertEquals(
            Ok(Unit),
            KortexShell.requireSurfaceGlobals(all),
            "a compositor advertising every global was found lacking one",
        )
        assertEquals(
            Err(KortexError.MissingGlobal("wl_compositor")),
            KortexShell.requireSurfaceGlobals(lacking("wl_compositor")),
            "a compositor without wl_compositor was not found lacking it",
        )
        assertEquals(
            Err(KortexError.MissingGlobal("wl_shm")),
            KortexShell.requireSurfaceGlobals(lacking("wl_shm")),
            "a compositor without wl_shm was not found lacking it",
        )
        assertEquals(
            Err(KortexError.MissingGlobal("zwlr_layer_shell_v1")),
            KortexShell.requireSurfaceGlobals(lacking("zwlr_layer_shell_v1")),
            "a compositor without zwlr_layer_shell_v1 was not found lacking it",
        )
    }

    @Test
    fun `a call inside content places a child, and taking the parent's call out reports LeftComposition to both`() {
        val showing = mutableStateOf(true)
        val parentReports = CopyOnWriteArrayList<Result<SurfaceEnd, SurfaceError<Nothing>>>()
        val childReports = CopyOnWriteArrayList<Result<SurfaceEnd, SurfaceError<Nothing>>>()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            if (showing.value) {
                TestSurface<Nothing>(NAMESPACE, onClose = { parentReports += it }) { ChildSurface(childReports) }
            }
        }

        onApplication(content) { shell ->
            awaitPlaced(shell, count = 2)
            assertNotNull(Screen.awaitGeometry(SECOND_NAMESPACE), "hyprctl never listed the child, $SECOND_NAMESPACE")

            showing.value = false

            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { parentReports.isNotEmpty() && childReports.isNotEmpty() },
                "taking the parent's call out left a surface unreported: parent $parentReports, child $childReports",
            )
            shell.pumpOrFail(SETTLE_MILLIS)
            assertEquals(
                listOf(Ok(SurfaceEnd.LeftComposition)),
                parentReports.toList(),
                "the parent did not report LeftComposition once",
            )
            assertEquals(
                listOf(Ok(SurfaceEnd.LeftComposition)),
                childReports.toList(),
                "the child did not report LeftComposition once",
            )
            assertTrue(shell.shownSurfaces.isEmpty(), "a surface outlived the parent's call")
        }
    }

    @Test
    fun `a parent whose content crashes reports the crash, and the child it showed reports LeftComposition`() {
        val crashing = mutableStateOf(false)
        val parentReports = CopyOnWriteArrayList<Result<SurfaceEnd, SurfaceError<Nothing>>>()
        val childReports = CopyOnWriteArrayList<Result<SurfaceEnd, SurfaceError<Nothing>>>()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            TestSurface<Nothing>(NAMESPACE, onClose = { parentReports += it }) {
                ChildSurface(childReports)
                val crashNow = crashing.value
                LaunchedEffect(crashNow) { if (crashNow) error(EFFECT_FAILURE) }
            }
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
                listOf(Ok(SurfaceEnd.LeftComposition)),
                childReports.toList(),
                "the child of a crashed parent did not report LeftComposition once",
            )
            assertTrue(shell.shownSurfaces.isEmpty(), "a surface outlived its crashed parent")
        }
    }

    @Test
    fun `a parent whose cleanup throws, composed after its child, reports the crash, its child LeftComposition`() =
        takeOutParentWithThrowingCleanup(ParentContent.ChildThenCleanup)

    @Test
    fun `a parent whose cleanup throws, composed before its child, reports the crash, its child LeftComposition`() =
        takeOutParentWithThrowingCleanup(ParentContent.CleanupThenChild)

    @Test
    fun `a parent whose composable body throws reports the crash, and the child it showed reports LeftComposition`() {
        val crashing = mutableStateOf(false)
        val parentReports = CopyOnWriteArrayList<Result<SurfaceEnd, SurfaceError<Nothing>>>()
        val childReports = CopyOnWriteArrayList<Result<SurfaceEnd, SurfaceError<Nothing>>>()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            TestSurface<Nothing>(NAMESPACE, onClose = { parentReports += it }) {
                ChildSurface(childReports)
                if (crashing.value) error(BODY_FAILURE)
            }
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
        assertEquals(
            listOf(Ok(SurfaceEnd.LeftComposition)),
            childReports.toList(),
            "the child of a crashed parent did not report LeftComposition once",
        )
        assertTrue(
            BODY_FAILURE in printed,
            "what Compose printed did not name the body's failure: ${printed.take(PRINTED_EXCERPT)}",
        )
    }

    @Test
    fun `exitApplication reports LeftComposition once to a surface and to the surface its content showed`() {
        val parentReports = CopyOnWriteArrayList<Result<SurfaceEnd, SurfaceError<Nothing>>>()
        val childReports = CopyOnWriteArrayList<Result<SurfaceEnd, SurfaceError<Nothing>>>()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            TestSurface<Nothing>(NAMESPACE, onClose = { parentReports += it }) { ChildSurface(childReports) }
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
        assertEquals(
            listOf(Ok(SurfaceEnd.LeftComposition)),
            parentReports.toList(),
            "exitApplication did not report LeftComposition to the parent once",
        )
        assertEquals(
            listOf(Ok(SurfaceEnd.LeftComposition)),
            childReports.toList(),
            "exitApplication did not report LeftComposition to the child once",
        )
    }

    @Test
    fun `LocalKortexSurface below a surface's content is its own scope, and closing it closes the surface`() {
        val closeRequested = mutableStateOf(false)
        val scope = AtomicReference<SurfaceScope<Nothing>?>(null)
        val below = AtomicReference<KortexSurfaceHandle?>(null)
        val reports = CopyOnWriteArrayList<Result<SurfaceEnd, SurfaceError<Nothing>>>()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            TestSurface<Nothing>(NAMESPACE, onClose = { reports += it }) {
                val own = this
                SideEffect { scope.set(own) }
                SurfaceBelow(below, closeRequested)
            }
        }

        onApplication(content) { shell ->
            assertTrue(shell.pumpOrFail(PUMP_MILLIS) { below.get() != null }, "the surface's content never composed")
            assertSame<Any?>(
                scope.get(),
                below.get(),
                "LocalKortexSurface below the content was not the content's own scope",
            )

            closeRequested.value = true

            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { reports.isNotEmpty() },
                "closing LocalKortexSurface reported nothing",
            )
            assertEquals(
                listOf(Ok(SurfaceEnd.Closed)),
                reports.toList(),
                "closing LocalKortexSurface did not report Closed once",
            )
            assertTrue(shell.shownSurfaces.isEmpty(), "closing LocalKortexSurface left the surface on screen")
        }
    }

    @Test
    fun `content reaches the shell's clipboard through LocalKortexClipboard, failures included`() {
        val clipboard = FakeTextClipboard(read = { Ok(OTHER_CLIENTS) }, set = { Err(ClipboardError.NoInputSerial) })
        val results = CopyOnWriteArrayList<Result<Any, ClipboardError>>()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            TestSurface<Nothing>(NAMESPACE) {
                val typed = LocalKortexClipboard.current
                LaunchedEffect(typed) {
                    results += typed.readText()
                    results += typed.setText(COPIED)
                    results += typed.clear()
                }
            }
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
            TestSurface<Nothing>(NAMESPACE) { typed.set(LocalKortexClipboard.current) }
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

    /** A speck under [SECOND_NAMESPACE], shown from the surface content it is called in, reporting to [reports]. */
    @Composable
    private fun ChildSurface(reports: MutableList<Result<SurfaceEnd, SurfaceError<Nothing>>>) {
        TestSurface<Nothing>(SECOND_NAMESPACE, anchor = BOTTOM_LEFT, onClose = { reports += it })
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
     * Shows a parent whose content holds a child surface and cleanup that throws, composed in [order], then takes
     * the parent's call out: the parent must report the crash and the child `Ok(SurfaceEnd.LeftComposition)`,
     * whichever Compose disposes first.
     */
    private fun takeOutParentWithThrowingCleanup(order: ParentContent) {
        val showing = mutableStateOf(true)
        val parentReports = CopyOnWriteArrayList<Result<SurfaceEnd, SurfaceError<Nothing>>>()
        val childReports = CopyOnWriteArrayList<Result<SurfaceEnd, SurfaceError<Nothing>>>()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            if (showing.value) {
                TestSurface<Nothing>(NAMESPACE, onClose = { parentReports += it }) {
                    when (order) {
                        ParentContent.ChildThenCleanup -> {
                            ChildSurface(childReports)
                            ThrowingCleanup()
                        }
                        ParentContent.CleanupThenChild -> {
                            ThrowingCleanup()
                            ChildSurface(childReports)
                        }
                    }
                }
            }
        }

        onApplication(content) { shell ->
            awaitPlaced(shell, count = 2)

            showing.value = false

            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { parentReports.isNotEmpty() && childReports.isNotEmpty() },
                "taking the parent's call out left a surface unreported: parent $parentReports, child $childReports",
            )
            shell.pumpOrFail(SETTLE_MILLIS)
            val crash = crashIn(parentReports.single(), "the parent's cleanup that threw did not report a crash")
            assertEquals(CLEANUP_FAILURE, crash.failure.cause.message, "the crash did not carry what the cleanup threw")
            assertEquals(
                listOf(Ok(SurfaceEnd.LeftComposition)),
                childReports.toList(),
                "the child did not report LeftComposition once",
            )
            assertTrue(shell.shownSurfaces.isEmpty(), "a surface outlived the parent's call")
        }
    }

    /** In which order a parent's content composes its child surface and its cleanup that throws. */
    private enum class ParentContent {
        ChildThenCleanup,
        CleanupThenChild,
    }

    private sealed interface Dismissal : IError {
        data object Dismissed : Dismissal
    }

    private companion object {
        const val NAMESPACE = "kortex-surface"
        const val SECOND_NAMESPACE = "kortex-surface-second"
        const val PUMP_MILLIS = 4_000L
        const val SETTLE_MILLIS = 300L
        const val FIRST_LABEL = 1
        const val SECOND_LABEL = 2
        const val NEGATIVE_WIDTH = -8
        const val EFFECT_FAILURE = "an effect threw"
        const val CLEANUP_FAILURE = "cleanup threw as the surface went"
        const val APPLICATION_FAILURE = "the application's content threw"
        const val ON_CLOSE_FAILURE = "an onClose threw"
        const val EFFECT_DELAY_MILLIS = 50L
        const val IDLE_MILLIS = 500L

        // Long enough for a fresh surface's own configure, first frames and buffer releases to come and go.
        const val QUIET_MILLIS = 1_500L
        const val PRINTED_EXCERPT = 300
        const val BODY_FAILURE = "a surface's content threw as it composed"
        const val COPIED = "copied in kortex"
        const val OTHER_CLIENTS = "from another client"
        const val CLIPBOARD_CALLS = 3

        // Clear of the default speck's corner, so a second surface is told apart on screen too.
        val BOTTOM_LEFT = setOf(Edge.Bottom, Edge.Left)
    }
}
