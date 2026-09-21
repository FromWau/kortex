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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.errorOrNull
import com.fromwau.kern.result.getOrElse
import com.fromwau.kern.result.onSuccess
import com.fromwau.kortex.compose.ContentFailure
import com.fromwau.kortex.compose.KortexSurfaceHandle
import com.fromwau.kortex.compose.LocalKortexSurface
import java.util.concurrent.CopyOnWriteArrayList
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

/** A surface a call keeps on screen in an application composition, and how each of its endings reaches its state. */
class SurfaceTest {
    @Test
    fun `a call is Placing until the pass after it enters composition places its surface, under its namespace`() {
        val speck = SurfaceState()

        onApplication({ TestSurface(NAMESPACE, state = speck) }) { shell ->
            assertTrue(shell.shownSurfaces.isEmpty(), "the call placed its surface inside composition")
            assertEquals(SurfaceStatus.Placing, speck.status, "a call was not Placing before its surface was placed")

            shell.passOrFail()

            assertEquals(1, shell.shownSurfaces.size, "the pass after the call entered composition placed no surface")
            val geometry = assertNotNull(
                Screen.awaitGeometry(NAMESPACE),
                "hyprctl never listed $NAMESPACE, its namespace as written",
            )
            assertEquals(
                SurfaceStatus.OnScreen(IntSize(geometry.logicalWidth, geometry.logicalHeight)),
                speck.status,
                "a placed surface was not OnScreen at the size hyprctl reports",
            )
        }
    }

    @Test
    fun `taking a surface call out of composition removes its surface and ends it as LeftComposition`() {
        val showing = mutableStateOf(true)
        val speck = SurfaceState()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            if (showing.value) TestSurface(NAMESPACE, state = speck)
        }

        onApplication(content) { shell ->
            awaitPlaced(shell)

            showing.value = false

            assertTrue(shell.pumpOrFail(PUMP_MILLIS) { speck.hasEnded }, "taking the call out ended nothing")
            shell.pumpOrFail(SETTLE_MILLIS)
            speck.assertEnded(
                Ok(SurfaceEnd.LeftComposition),
                "taking the call out did not end the surface as LeftComposition",
            )
            assertTrue(shell.shownSurfaces.isEmpty(), "the surface outlived its call")
            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { Screen.geometry(NAMESPACE) == null },
                "hyprctl still lists $NAMESPACE after its call left composition",
            )
        }
    }

    @Test
    fun `one call keeps its surface while its arguments change, and its newest state gets the ending`() {
        val showing = mutableStateOf(true)
        val label = mutableIntStateOf(FIRST_LABEL)
        val drawn = CopyOnWriteArrayList<Int>()
        val first = SurfaceState()
        val second = SurfaceState()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            if (showing.value) {
                val current = label.intValue
                TestSurface(NAMESPACE, state = if (current == FIRST_LABEL) first else second) {
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
                shell.pumpOrFail(PUMP_MILLIS) { second.hasEnded },
                "taking the call out ended nothing on the state the call composed with last",
            )
            shell.pumpOrFail(SETTLE_MILLIS)
            second.assertEnded(Ok(SurfaceEnd.LeftComposition), "the newest state did not get the ending")
            assertFalse(first.hasEnded, "the ending reached a state the call had already left behind: ${first.status}")
        }
    }

    @Test
    fun `another surface call in the same place is another surface, and the first ends as LeftComposition`() {
        val showSecond = mutableStateOf(false)
        val first = SurfaceState()
        val second = SurfaceState()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            when {
                showSecond.value -> TestSurface(SECOND_NAMESPACE, state = second)
                else -> TestSurface(NAMESPACE, state = first)
            }
        }

        onApplication(content) { shell ->
            awaitPlaced(shell)
            val placedFirst = shell.shownSurfaces.single()

            showSecond.value = true

            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { first.hasEnded },
                "the call the other one took the place of ended nothing",
            )
            first.assertEnded(
                Ok(SurfaceEnd.LeftComposition),
                "the call taken out did not end its surface as LeftComposition",
            )
            val placedItsOwn = shell.pumpOrFail(PUMP_MILLIS) {
                shell.shownSurfaces.singleOrNull()?.let { it !== placedFirst } == true
            }
            assertTrue(placedItsOwn, "the other call did not place a surface of its own")
            assertNotNull(Screen.awaitGeometry(SECOND_NAMESPACE), "hyprctl never listed $SECOND_NAMESPACE")
            shell.pumpOrFail(SETTLE_MILLIS)
            assertFalse(second.hasEnded, "the other call's surface ended: ${second.status}")
        }
    }

    @Test
    fun `close() from content ends each surface it is called on as Closed`() {
        val closeRequested = mutableStateOf(false)
        val first = SurfaceState()
        val second = SurfaceState()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            TestSurface(NAMESPACE, state = first) {
                val requested = closeRequested.value
                LaunchedEffect(requested) { if (requested) close() }
            }
            TestSurface(SECOND_NAMESPACE, anchor = BOTTOM_LEFT, state = second) {
                val requested = closeRequested.value
                LaunchedEffect(requested) { if (requested) close() }
            }
        }

        onApplication(content) { shell ->
            awaitPlaced(shell, count = 2)

            closeRequested.value = true

            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { first.hasEnded && second.hasEnded },
                "closing from content left a surface running: first ${first.status}, second ${second.status}",
            )
            shell.pumpOrFail(SETTLE_MILLIS)
            first.assertEnded(Ok(SurfaceEnd.Closed), "close() did not end the first surface as Closed")
            second.assertEnded(Ok(SurfaceEnd.Closed), "close() did not end the second surface as Closed")
            assertTrue(shell.shownSurfaces.isEmpty(), "a closed surface stayed on screen")
        }
    }

    @Test
    fun `close() from another thread ends a surface whose loop is asleep`() {
        val scope = AtomicReference<SurfaceScope?>(null)
        val speck = SurfaceState()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            TestSurface(NAMESPACE, state = speck) {
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
        speck.assertEnded(Ok(SurfaceEnd.Closed), "close() did not end the surface as Closed")
    }

    @Test
    fun `the scope, LocalKortexSurface and OnScreen all give the logical size, which the scope loses once ended`() {
        val showing = mutableStateOf(true)
        val scope = AtomicReference<SurfaceScope?>(null)
        val below = AtomicReference<KortexSurfaceHandle?>(null)
        val speck = SurfaceState()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            if (showing.value) {
                TestSurface(NAMESPACE, state = speck) {
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
            assertEquals(
                SurfaceStatus.OnScreen(logical),
                speck.status,
                "the status was not OnScreen at the logical size hyprctl reports",
            )

            showing.value = false

            assertTrue(shell.pumpOrFail(PUMP_MILLIS) { shell.shownSurfaces.isEmpty() }, "the surface outlived its call")
            assertEquals(IntSize.Zero, own.size, "the scope's size was not zero once the surface had ended")
            assertEquals(IntSize.Zero, handle.size, "LocalKortexSurface's size was not zero once the surface ended")
            speck.assertEnded(Ok(SurfaceEnd.LeftComposition), "the surface did not end as LeftComposition")
        }
    }

    @Test
    fun `content's first composition reads its surface's logical size`() {
        val composedWith = CopyOnWriteArrayList<IntSize>()

        onApplication({ TestSurface(NAMESPACE) { composedWith += size } }) { shell ->
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
    fun `a surface the compositor closes ends as ClosedByCompositor, and nothing takes its place or ends it again`() {
        val showing = mutableStateOf(true)
        val left = AtomicBoolean(false)
        val speck = SurfaceState()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            if (showing.value) {
                TestSurface(NAMESPACE, state = speck)
                OnLeave(left)
            }
        }

        onApplication(content) { shell ->
            awaitPlaced(shell)

            shell.shownSurfaces.single().simulateCompositorClose()

            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { speck.hasEnded },
                "the compositor's close ended nothing",
            )
            shell.pumpOrFail(SETTLE_MILLIS)
            speck.assertEnded(
                Ok(SurfaceEnd.ClosedByCompositor),
                "the compositor's close did not end the surface as ClosedByCompositor",
            )
            assertTrue(shell.shownSurfaces.isEmpty(), "a surface the compositor closed was placed again")

            showing.value = false
            assertTrue(shell.pumpOrFail(PUMP_MILLIS) { left.get() }, "the call never left composition")
            shell.passOrFail()

            speck.assertEnded(
                Ok(SurfaceEnd.ClosedByCompositor),
                "taking out a call whose surface had ended gave its state another ending",
            )
        }
    }

    @Test
    fun `a surface that cannot be placed ends with the reason, and the run goes on`() {
        val speck = SurfaceState()
        val unplaceable: @Composable KortexApplicationScope.() -> Unit = {
            TestSurface(NAMESPACE, anchor = emptySet(), width = 0.dp, state = speck)
        }

        onApplication(unplaceable) { shell ->
            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { speck.hasEnded },
                "the unplaceable surface ended nothing",
            )
            shell.pumpOrFail(SETTLE_MILLIS)
            speck.assertEnded(
                Err(KortexError.UnspannableAxis(Axis.Horizontal, emptySet())),
                "a surface with an unspannable axis did not end with that reason",
            )
            assertTrue(shell.shownSurfaces.isEmpty(), "a surface that could not be placed is listed as shown")
        }
    }

    @Test
    fun `a surface with a width below 0 ends with NegativeSize, and the run goes on`() {
        val speck = SurfaceState()
        val negative: @Composable KortexApplicationScope.() -> Unit = {
            // A corner anchor: should the width go out, Hyprland answers its commit by ending the connection.
            TestSurface(NAMESPACE, width = NEGATIVE_WIDTH.dp, state = speck)
        }

        onApplication(negative) { shell ->
            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { speck.hasEnded },
                "the surface with a width below 0 ended nothing",
            )
            shell.pumpOrFail(SETTLE_MILLIS)
            speck.assertEnded(
                Err(KortexError.NegativeSize(Axis.Horizontal, NEGATIVE_WIDTH)),
                "a surface with a width below 0 did not end with NegativeSize",
            )
            assertTrue(shell.shownSurfaces.isEmpty(), "a surface with a width below 0 is listed as shown")
        }
    }

    @Test
    fun `content whose effect throws ends with the crash, and another shown surface keeps drawing`() {
        val speck = SurfaceState()
        val tick = mutableIntStateOf(0)
        val drawn = CopyOnWriteArrayList<Int>()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            TestSurface(NAMESPACE, state = speck) { ThrowingEffect() }
            TestSurface(SECOND_NAMESPACE, anchor = BOTTOM_LEFT) {
                Canvas(Modifier.fillMaxSize()) { drawn += tick.intValue }
            }
        }

        onApplication(content) { shell ->
            assertTrue(shell.pumpOrFail(PUMP_MILLIS) { speck.hasEnded }, "the crashed surface ended nothing")
            val crash = speck.crashOrFail("an effect that threw did not end the surface with SurfaceCrashed")
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
    fun `content whose cleanup throws as its call is taken out ends with the crash, not Ok`() {
        val showing = mutableStateOf(true)
        val speck = SurfaceState()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            if (showing.value) TestSurface(NAMESPACE, state = speck) { ThrowingCleanup() }
        }

        onApplication(content) { shell ->
            awaitPlaced(shell)

            showing.value = false

            assertTrue(shell.pumpOrFail(PUMP_MILLIS) { speck.hasEnded }, "taking the call out ended nothing")
            val crash = speck.crashOrFail("cleanup that threw as its call was taken out did not end with a crash")
            assertEquals(CLEANUP_FAILURE, crash.failure.cause.message, "the crash did not carry what the cleanup threw")
        }
    }

    @Test
    fun `content stays composed under a new namespace, so its cleanup does not run and the surface does not end`() {
        val namespace = mutableStateOf(NAMESPACE)
        val speck = SurfaceState()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            TestSurface(namespace.value, state = speck) { ThrowingCleanup() }
        }

        onApplication(content) { shell ->
            awaitPlaced(shell)

            namespace.value = SECOND_NAMESPACE

            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { Screen.geometry(SECOND_NAMESPACE) != null },
                "hyprctl never listed the surface under $SECOND_NAMESPACE",
            )
            shell.pumpOrFail(SETTLE_MILLIS)
            assertFalse(speck.hasEnded, "a new namespace ran the content's cleanup and ended the surface")
            assertEquals(1, shell.shownSurfaces.size, "the call was left without a surface under its new namespace")
        }
    }

    @Test
    fun `a surface that ends in the pass its call is taken out keeps its own ending`() {
        val showing = mutableStateOf(true)
        val scope = AtomicReference<SurfaceScope?>(null)
        val speck = SurfaceState()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            if (showing.value) {
                TestSurface(NAMESPACE, state = speck) {
                    val own = this
                    SideEffect { scope.set(own) }
                }
                // The host closes the surface as it takes the call out, so the ending and the removal meet in one pass.
                DisposableEffect(Unit) { onDispose { scope.get()?.close() } }
            }
        }

        onApplication(content) { shell ->
            awaitPlaced(shell)

            showing.value = false

            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { speck.hasEnded },
                "the ending and the removal ended nothing",
            )
            shell.pumpOrFail(SETTLE_MILLIS)
            speck.assertEnded(
                Ok(SurfaceEnd.Closed),
                "an ending and a removal in one pass did not leave the surface's own ending standing",
            )
        }
    }

    @Test
    fun `exitApplication from another thread, twice, returns Ok and ends every surface as LeftComposition`() {
        val first = SurfaceState()
        val second = SurfaceState()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            TestSurface(NAMESPACE, state = first)
            TestSurface(SECOND_NAMESPACE, anchor = BOTTOM_LEFT, state = second)
        }

        val result = LoopThread.runApplication(content) { scope, loop ->
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
        first.assertEnded(Ok(SurfaceEnd.LeftComposition), "exitApplication did not end the first surface")
        second.assertEnded(Ok(SurfaceEnd.LeftComposition), "exitApplication did not end the second surface")
    }

    @Test
    fun `closing the application ends every shown surface as LeftComposition and returns Ok`() {
        val first = SurfaceState()
        val second = SurfaceState()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            TestSurface(NAMESPACE, state = first)
            TestSurface(SECOND_NAMESPACE, anchor = BOTTOM_LEFT, state = second)
        }
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use {
            val shell = KortexShell.createApplicationOrFail(display, content)
            awaitPlaced(shell, count = 2)

            assertEquals(Ok(Unit), shell.close(), "closing the application did not return Ok")
            first.assertEnded(Ok(SurfaceEnd.LeftComposition), "closing did not end the first surface")
            second.assertEnded(Ok(SurfaceEnd.LeftComposition), "closing did not end the second surface")
        }
    }

    @Test
    fun `a connection that dies under the run ends it with its error, which each shown surface ends with too`() {
        val speck = SurfaceState()
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }
        val content: @Composable KortexApplicationScope.() -> Unit = {
            TestSurface(NAMESPACE, state = speck) {
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
            speck.assertEnded(Err(violation), "the shown surface did not end with the connection's error")
        }
    }

    @Test
    fun `a connection that dies under kortexApplication returns its error, and its surface ends with it`() {
        val killRequested = mutableStateOf(false)
        val speck = SurfaceState()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            val shell = LocalKortexShell.current
            TestSurface(NAMESPACE, state = speck) {
                val kill = killRequested.value
                LaunchedEffect(kill) { if (kill) killConnection(shell.display) }
            }
        }

        val result = LoopThread.runApplication(content) { _, loop ->
            assertTrue(LoopThread.awaitNamespace(NAMESPACE, present = true), "hyprctl never listed $NAMESPACE")

            killRequested.value = true

            loop.join(LoopThread.JOIN_MILLIS)
            assertFalse(loop.isAlive, "the connection dying did not end the run")
        }

        val violation = assertIs<KortexError.ProtocolViolation>(
            result.errorOrNull(),
            "an application whose connection died did not return the run's protocol error: $result",
        )
        speck.assertEnded(Err(violation), "the shown surface did not end with the connection's error")
    }

    @Test
    fun `two surface calls sharing one state fail as ApplicationCrashed`() {
        val shared = SurfaceState()
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use {
            var error: KortexError? = null
            capturingStderr {
                error = KortexShell
                    .createApplication(display) {
                        TestSurface(NAMESPACE, state = shared)
                        TestSurface(SECOND_NAMESPACE, anchor = BOTTOM_LEFT, state = shared)
                    }
                    .onSuccess { it.close() }
                    .errorOrNull()
            }

            val crash = assertIs<KortexError.ApplicationCrashed>(error, "two calls sharing a state started")
            assertEquals(SURFACE_STATE_SHARED, crash.cause.message, "the crash did not name the shared state")
        }
    }

    @Test
    fun `a state held above its call reads the ending, and goes back to Placing when the call returns`() {
        val showing = mutableStateOf(true)
        val bar = SurfaceState()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            if (showing.value) TestSurface(NAMESPACE, state = bar)
        }

        onApplication(content) { shell ->
            awaitPlaced(shell)

            showing.value = false

            assertTrue(shell.pumpOrFail(PUMP_MILLIS) { bar.hasEnded }, "taking the call out ended nothing")
            bar.assertEnded(Ok(SurfaceEnd.LeftComposition), "the state held above the call did not read its ending")

            showing.value = true

            // Pass by pass to the one that composes the call again, where it takes the state back: a pass places
            // what is queued before it composes, so the state is read while nothing is placed for it yet.
            val asked = (1..PASSES_FOR_A_RECOMPOSITION).any {
                shell.passOrFail()
                shell.queuedSettings.isNotEmpty()
            }
            assertTrue(asked, "the call that came back never asked for a surface")
            assertEquals(SurfaceStatus.Placing, bar.status, "the call that took the state back left it on its ending")
            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { bar.status is SurfaceStatus.OnScreen },
                "the surface placed for the call that came back never reached the state: ${bar.status}",
            )
            assertEquals(1, shell.shownSurfaces.size, "the call that came back placed no surface of its own")
        }
    }

    @Test
    fun `an application with no surface shown keeps running, and a call added later still places`() {
        val showing = mutableStateOf(false)
        val content: @Composable KortexApplicationScope.() -> Unit = {
            if (showing.value) TestSurface(NAMESPACE)
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
    fun `the application's content throwing ends the run as ApplicationCrashed`() {
        val boom = mutableStateOf(false)
        val content: @Composable KortexApplicationScope.() -> Unit = {
            TestSurface(NAMESPACE)
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
        assertTrue(
            APPLICATION_FAILURE in printed,
            "what Compose printed did not name the content's failure: ${printed.take(PRINTED_EXCERPT)}",
        )
    }

    @Test
    fun `UI placed directly in the application's content ends the run as ApplicationCrashed`() {
        val addUi = mutableStateOf(false)
        val content: @Composable KortexApplicationScope.() -> Unit = {
            TestSurface(NAMESPACE)
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
    fun `a call inside content places a child, and taking the parent's call out ends both as LeftComposition`() {
        val showing = mutableStateOf(true)
        val parent = SurfaceState()
        val child = SurfaceState()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            if (showing.value) {
                TestSurface(NAMESPACE, state = parent) { ChildSurface(child) }
            }
        }

        onApplication(content) { shell ->
            awaitPlaced(shell, count = 2)
            assertNotNull(Screen.awaitGeometry(SECOND_NAMESPACE), "hyprctl never listed the child, $SECOND_NAMESPACE")

            showing.value = false

            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { parent.hasEnded && child.hasEnded },
                "taking the parent's call out left a surface running: parent ${parent.status}, child ${child.status}",
            )
            shell.pumpOrFail(SETTLE_MILLIS)
            parent.assertEnded(Ok(SurfaceEnd.LeftComposition), "the parent did not end as LeftComposition")
            child.assertEnded(Ok(SurfaceEnd.LeftComposition), "the child did not end as LeftComposition")
            assertTrue(shell.shownSurfaces.isEmpty(), "a surface outlived the parent's call")
        }
    }

    @Test
    fun `a parent whose content crashes ends with the crash, and the child it showed as LeftComposition`() {
        val crashing = mutableStateOf(false)
        val parent = SurfaceState()
        val child = SurfaceState()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            TestSurface(NAMESPACE, state = parent) {
                ChildSurface(child)
                val crashNow = crashing.value
                LaunchedEffect(crashNow) { if (crashNow) error(EFFECT_FAILURE) }
            }
        }

        onApplication(content) { shell ->
            awaitPlaced(shell, count = 2)

            crashing.value = true

            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { parent.hasEnded && child.hasEnded },
                "the parent's crash left a surface running: parent ${parent.status}, child ${child.status}",
            )
            shell.pumpOrFail(SETTLE_MILLIS)
            val crash = parent.crashOrFail("the parent's crash did not end it with SurfaceCrashed")
            assertEquals(EFFECT_FAILURE, crash.failure.cause.message, "the crash did not carry what the effect threw")
            child.assertEnded(
                Ok(SurfaceEnd.LeftComposition),
                "the child of a crashed parent did not end as LeftComposition",
            )
            assertTrue(shell.shownSurfaces.isEmpty(), "a surface outlived its crashed parent")
        }
    }

    @Test
    fun `a parent whose cleanup throws, composed after its child, ends with the crash, its child LeftComposition`() =
        takeOutParentWithThrowingCleanup(ParentContent.ChildThenCleanup)

    @Test
    fun `a parent whose cleanup throws, composed before its child, ends with the crash, its child LeftComposition`() =
        takeOutParentWithThrowingCleanup(ParentContent.CleanupThenChild)

    @Test
    fun `a parent whose composable body throws ends with the crash, and the child it showed as LeftComposition`() {
        val crashing = mutableStateOf(false)
        val parent = SurfaceState()
        val child = SurfaceState()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            TestSurface(NAMESPACE, state = parent) {
                ChildSurface(child)
                if (crashing.value) error(BODY_FAILURE)
            }
        }

        // Compose prints the failure of the composition it ran as well, which is kept off the test's own output.
        val printed = capturingStderr {
            onApplication(content) { shell ->
                awaitPlaced(shell, count = 2)

                crashing.value = true

                assertTrue(
                    shell.pumpOrFail(PUMP_MILLIS) { parent.hasEnded && child.hasEnded },
                    "the parent's crash left a surface running: parent ${parent.status}, child ${child.status}",
                )
                shell.pumpOrFail(SETTLE_MILLIS)
                assertTrue(shell.shownSurfaces.isEmpty(), "a surface outlived its crashed parent")
            }
        }
        val crash = parent.crashOrFail("a parent whose body threw did not end it with SurfaceCrashed")
        assertEquals(BODY_FAILURE, crash.failure.cause.message, "the crash did not carry what the body threw")
        child.assertEnded(
            Ok(SurfaceEnd.LeftComposition),
            "the child of a crashed parent did not end as LeftComposition",
        )
        assertTrue(
            BODY_FAILURE in printed,
            "what Compose printed did not name the body's failure: ${printed.take(PRINTED_EXCERPT)}",
        )
    }

    @Test
    fun `exitApplication ends a surface and the surface its content showed as LeftComposition`() {
        val parent = SurfaceState()
        val child = SurfaceState()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            TestSurface(NAMESPACE, state = parent) { ChildSurface(child) }
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
        parent.assertEnded(Ok(SurfaceEnd.LeftComposition), "exitApplication did not end the parent")
        child.assertEnded(Ok(SurfaceEnd.LeftComposition), "exitApplication did not end the child")
    }

    @Test
    fun `LocalKortexSurface below a surface's content is its own scope, and closing it closes the surface`() {
        val closeRequested = mutableStateOf(false)
        val scope = AtomicReference<SurfaceScope?>(null)
        val below = AtomicReference<KortexSurfaceHandle?>(null)
        val speck = SurfaceState()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            TestSurface(NAMESPACE, state = speck) {
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
                shell.pumpOrFail(PUMP_MILLIS) { speck.hasEnded },
                "closing LocalKortexSurface ended nothing",
            )
            speck.assertEnded(Ok(SurfaceEnd.Closed), "closing LocalKortexSurface did not end the surface as Closed")
            assertTrue(shell.shownSurfaces.isEmpty(), "closing LocalKortexSurface left the surface on screen")
        }
    }

    @Test
    fun `content reaches the shell's clipboard through LocalKortexClipboard, failures included`() {
        val clipboard = FakeTextClipboard(read = { Ok(OTHER_CLIENTS) }, set = { Err(ClipboardError.NoInputSerial) })
        val results = CopyOnWriteArrayList<Result<Any, ClipboardError>>()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            TestSurface(NAMESPACE) {
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
            TestSurface(NAMESPACE) { typed.set(LocalKortexClipboard.current) }
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

    /** A speck under [SECOND_NAMESPACE], shown from the surface content it is called in, watched through [state]. */
    @Composable
    private fun ChildSurface(state: SurfaceState) {
        TestSurface(SECOND_NAMESPACE, anchor = BOTTOM_LEFT, state = state)
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
        val parent = SurfaceState()
        val child = SurfaceState()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            if (showing.value) {
                TestSurface(NAMESPACE, state = parent) {
                    when (order) {
                        ParentContent.ChildThenCleanup -> {
                            ChildSurface(child)
                            ThrowingCleanup()
                        }
                        ParentContent.CleanupThenChild -> {
                            ThrowingCleanup()
                            ChildSurface(child)
                        }
                    }
                }
            }
        }

        onApplication(content) { shell ->
            awaitPlaced(shell, count = 2)

            showing.value = false

            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { parent.hasEnded && child.hasEnded },
                "taking the parent's call out left a surface running: parent ${parent.status}, child ${child.status}",
            )
            shell.pumpOrFail(SETTLE_MILLIS)
            val crash = parent.crashOrFail("the parent's cleanup that threw did not end it with a crash")
            assertEquals(CLEANUP_FAILURE, crash.failure.cause.message, "the crash did not carry what the cleanup threw")
            child.assertEnded(Ok(SurfaceEnd.LeftComposition), "the child did not end as LeftComposition")
            assertTrue(shell.shownSurfaces.isEmpty(), "a surface outlived the parent's call")
        }
    }

    /** In which order a parent's content composes its child surface and its cleanup that throws. */
    private enum class ParentContent {
        ChildThenCleanup,
        CleanupThenChild,
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
        const val EFFECT_DELAY_MILLIS = 50L
        const val IDLE_MILLIS = 500L

        // A recomposition the loop asks for arrives within a pass or two; the bound only keeps a test from spinning.
        const val PASSES_FOR_A_RECOMPOSITION = 20

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
