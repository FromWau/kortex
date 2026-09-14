package com.fromwau.kortex.wayland

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.asContextElement
import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.errorOrNull
import com.fromwau.kern.result.getOrElse
import com.fromwau.kortex.compose.KortexSurfaceHandle
import com.fromwau.kortex.compose.LocalKortexSurface
import com.fromwau.kortex.compose.SurfaceState
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** Host and content read where a surface is in its life, and only kortex's teardown or its content failing moves it. */
class SurfaceStateTest {
    @Test
    fun `a placed surface reads Running on its ActiveSurface and through the handle its content holds`() {
        val handle = AtomicReference<KortexSurfaceHandle>()
        val firstRead = AtomicReference<SurfaceState>()
        val spec = stateSpec {
            val surface = LocalKortexSurface.current
            handle.set(surface)
            firstRead.compareAndSet(null, surface.state)
        }

        onShell(spec) { shell ->
            shell.useOrFail {
                shell.pumpOrFail(SETTLE_MILLIS)

                assertEquals(
                    SurfaceState.Running,
                    firstRead.get(),
                    "content's first read of its handle was not Running",
                )
                assertEquals(
                    SurfaceState.Running,
                    shell.activeSurfaces.single().state,
                    "the ActiveSurface did not read Running",
                )
                assertEquals(
                    SurfaceState.Running,
                    assertNotNull(handle.get(), "content never saw a LocalKortexSurface").state,
                    "the handle content holds did not read Running",
                )
            }
        }
    }

    @Test
    fun `a surface whose content closes it reads Running until the shell drops it and Closed once it has`() {
        val handle = AtomicReference<KortexSurfaceHandle>()
        val afterRequest = AtomicReference<SurfaceState>()
        val closeRequested = mutableStateOf(false)
        val spec = stateSpec {
            val surface = LocalKortexSurface.current
            handle.set(surface)
            val requested = closeRequested.value
            LaunchedEffect(requested) {
                if (requested) {
                    surface.close()
                    afterRequest.set(surface.state)
                }
            }
        }

        onShell(spec) { shell ->
            shell.useOrFail {
                val active = shell.activeSurfaces.single()

                closeRequested.value = true
                val dropped = shell.pumpOrFail(PUMP_MILLIS) { shell.activeSurfaces.isEmpty() }

                assertTrue(dropped, "the shell never dropped the surface its content closed")
                assertEquals(SurfaceState.Running, afterRequest.get(), "content's close() moved the state by itself")
                assertEquals(
                    SurfaceState.Closed,
                    active.state,
                    "the ActiveSurface kept from before did not read Closed",
                )
                assertEquals(
                    SurfaceState.Closed,
                    assertNotNull(handle.get(), "content never saw a LocalKortexSurface").state,
                    "the handle content held did not read Closed",
                )
            }
        }
    }

    @Test
    fun `a CompositorChoice surface the compositor closes reads Closed and its replacement reads Running`() {
        onShell(stateSpec {}) { shell ->
            shell.useOrFail {
                val original = shell.activeSurfaces.single()

                original.surface.simulateCompositorClose()
                val replaced = shell.pumpOrFail(PUMP_MILLIS) {
                    shell.activeSurfaces.singleOrNull()?.let { it !== original } ?: false
                }

                assertTrue(
                    replaced,
                    "the shell never placed a surface in the stead of the one the compositor closed",
                )
                assertEquals(
                    SurfaceState.Closed,
                    original.state,
                    "the surface the compositor closed did not read Closed",
                )
                assertEquals(
                    SurfaceState.Running,
                    shell.activeSurfaces.single().state,
                    "the surface placed in its stead did not read Running",
                )
            }
        }
    }

    @Test
    fun `every surface of a shell with two specs reads Closed once the shell closes`() {
        val actives = onShell(stateSpec {}, stateSpec(SECOND_NAMESPACE) {}) { shell ->
            shell.useOrFail { shell.activeSurfaces }
        }

        assertEquals(2, actives.size, "the shell did not place one surface per spec")
        actives.forEach { active ->
            assertEquals(
                SurfaceState.Closed,
                active.state,
                "${active.spec.config.namespace} did not read Closed once the shell closed",
            )
        }
    }

    @Test
    fun `a surface whose effect throws reads Crashed with the failure onCrashSurface received`() {
        val reported = CopyOnWriteArrayList<KortexError.SurfaceCrashed>()

        onShell(stateSpec { ThrowingEffect() }, onCrashSurface = reported::add) { shell ->
            shell.useOrFail {
                val active = shell.activeSurfaces.single()

                val error = shell.pump(PUMP_MILLIS).errorOrNull()

                assertIs<KortexError.SurfaceCrashed>(error, "an effect that throws must end the run")
                assertEquals(
                    SurfaceState.Crashed(reported.single().failure),
                    active.state,
                    "the crashed surface did not read Crashed with the failure onCrashSurface received",
                )
            }
        }
    }

    @Test
    fun `a crashed surface still reads Crashed after the shell closes`() {
        val reported = CopyOnWriteArrayList<KortexError.SurfaceCrashed>()

        val active = onShell(stateSpec { ThrowingEffect() }, onCrashSurface = reported::add) { shell ->
            shell.useOrFail {
                assertIs<KortexError.SurfaceCrashed>(
                    shell.pump(PUMP_MILLIS).errorOrNull(),
                    "an effect that throws must end the run",
                )
                shell.activeSurfaces.single()
            }
        }

        assertEquals(
            SurfaceState.Crashed(reported.single().failure),
            active.state,
            "closing the shell moved a crashed surface off Crashed",
        )
    }

    @Test
    fun `a surface whose cleanup throws as the shell closes reads Crashed, not Closed`() {
        onShell(stateSpec { ThrowingOnClose() }) { shell ->
            assertNull(
                shell.pump(SETTLE_MILLIS).errorOrNull(),
                "content that only throws in its cleanup must keep running",
            )
            val active = shell.activeSurfaces.single()

            val crash = assertIs<KortexError.SurfaceCrashed>(
                shell.close().errorOrNull(),
                "cleanup that throws as the shell closes must fail the close",
            )

            assertEquals(
                SurfaceState.Crashed(crash.failure),
                active.state,
                "a surface whose cleanup threw as it was torn down did not read Crashed",
            )
        }
    }

    @Test
    fun `snapshotFlow collected outside any composition sees a surface go from Running to Closed`() {
        val closeRequested = mutableStateOf(false)

        onShell(stateSpec { CloseWhen(closeRequested) }) { shell ->
            shell.useOrFail {
                collectingState(shell.activeSurfaces.single()) { seen ->
                    closeRequested.value = true
                    assertTrue(
                        shell.pumpOrFail(PUMP_MILLIS) { shell.activeSurfaces.isEmpty() },
                        "the shell never dropped the surface its content closed",
                    )

                    // Not pumped meanwhile: nothing but kortex itself may tell the flow the surface closed.
                    LoopThread.waitUntil(COLLECT_MILLIS) { seen.size >= 2 }
                    assertEquals(
                        listOf(SurfaceState.Running, SurfaceState.Closed),
                        seen.toList(),
                        "the snapshotFlow did not see the surface go from Running to Closed",
                    )
                }
            }
        }
    }

    @Test
    fun `snapshotFlow collected outside any composition sees a surface go from Running to Crashed`() {
        val failRequested = mutableStateOf(false)

        onShell(stateSpec { FailWhen(failRequested) }) { shell ->
            shell.useOrFail {
                collectingState(shell.activeSurfaces.single()) { seen ->
                    failRequested.value = true
                    val crash = assertIs<KortexError.SurfaceCrashed>(
                        shell.pump(PUMP_MILLIS).errorOrNull(),
                        "an effect that throws must end the run",
                    )

                    // Not pumped meanwhile, and the shell not yet closed: nothing but kortex itself may tell the flow.
                    LoopThread.waitUntil(COLLECT_MILLIS) { seen.size >= 2 }
                    assertEquals(
                        listOf(SurfaceState.Running, SurfaceState.Crashed(crash.failure)),
                        seen.toList(),
                        "the snapshotFlow did not see the surface go from Running to Crashed",
                    )
                }
            }
        }
    }

    @Test
    fun `a surface whose content fails inside a read-only snapshot reads Crashed`() {
        val snapshot = AtomicReference<Snapshot>()

        onShell(stateSpec { FailingInsideReadOnlySnapshot(snapshot) }) { shell ->
            try {
                shell.useOrFail {
                    val active = shell.activeSurfaces.single()

                    val error = shell.pump(PUMP_MILLIS).errorOrNull()

                    assertIs<SurfaceState.Crashed>(
                        active.state,
                        "a failure recorded inside a read-only snapshot was lost",
                    )
                    val crash = assertIs<KortexError.SurfaceCrashed>(
                        error,
                        "a failure inside a read-only snapshot must end the run",
                    )
                    assertEquals(
                        SurfaceState.Crashed(crash.failure),
                        active.state,
                        "the surface did not read Crashed with the failure that ended the run",
                    )
                }
            } finally {
                snapshot.get()?.dispose()
            }
        }
    }

    /** Connects, creates a shell of [specs] and hands it to [block], which closes it; the connection closes after. */
    private fun <T> onShell(
        vararg specs: SurfaceSpec,
        onCrashSurface: (KortexError.SurfaceCrashed) -> Unit = {},
        block: (KortexShell) -> T,
    ): T {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }
        return display.use {
            val shell = KortexShell.create(display, *specs, onCrashSurface = onCrashSurface)
                .getOrElse { error -> fail("shell creation failed: $error") }
            block(shell)
        }
    }

    /**
     * Collects a `snapshotFlow` over [active]'s state on a coroutine of this test's own, outside any composition, for
     * as long as [block] runs, and cancels it on every path. [block] sees every value the flow has emitted so far.
     */
    private fun collectingState(active: ActiveSurface, block: (seen: List<SurfaceState>) -> Unit) {
        val seen = CopyOnWriteArrayList<SurfaceState>()
        val collector = CoroutineScope(Dispatchers.Default)
        try {
            collector.launch { snapshotFlow { active.state }.collect(seen::add) }
            // A flow has subscribed by its first emission, so any change after it must reach the flow.
            assertTrue(LoopThread.waitUntil(COLLECT_MILLIS) { seen.isNotEmpty() }, "the snapshotFlow never emitted")
            block(seen)
        } finally {
            collector.cancel()
        }
    }

    private fun stateSpec(namespace: String = NAMESPACE, content: @Composable () -> Unit): SurfaceSpec =
        SurfaceSpec(SPECK_CONFIG.copy(namespace = namespace), OutputTarget.CompositorChoice, content)

    @Composable
    private fun ThrowingEffect() {
        LaunchedEffect(Unit) {
            delay(EFFECT_DELAY_MILLIS)
            error(EFFECT_FAILURE)
        }
    }

    @Composable
    private fun FailWhen(requested: MutableState<Boolean>) {
        val fail = requested.value
        LaunchedEffect(fail) { if (fail) error(EFFECT_FAILURE) }
    }

    @Composable
    private fun FailingInsideReadOnlySnapshot(snapshot: AtomicReference<Snapshot>) {
        val scope = rememberCoroutineScope()
        LaunchedEffect(Unit) {
            delay(EFFECT_DELAY_MILLIS)
            val readOnly = Snapshot.takeSnapshot().also(snapshot::set)
            // A child of the remembered scope's plain Job reports its own failure, while its snapshot is still entered.
            scope.launch(readOnly.asContextElement()) { error(SNAPSHOT_FAILURE) }
        }
    }

    @Composable
    private fun ThrowingOnClose() {
        DisposableEffect(Unit) { onDispose { error(CLEANUP_FAILURE) } }
    }

    private companion object {
        const val NAMESPACE = "kortex-state"
        const val SECOND_NAMESPACE = "kortex-state-second"
        const val EFFECT_FAILURE = "an effect threw"
        const val SNAPSHOT_FAILURE = "content threw inside a read-only snapshot"
        const val CLEANUP_FAILURE = "cleanup threw as the shell closed"
        const val SETTLE_MILLIS = 300L
        const val EFFECT_DELAY_MILLIS = 50L
        const val PUMP_MILLIS = 4_000L
        const val COLLECT_MILLIS = 2_000L

        // A speck in the corner, where the pointer is least likely to be.
        val SPECK_CONFIG = SurfaceConfig(
            layer = Layer.Overlay,
            anchor = setOf(Edge.Bottom, Edge.Right),
            width = 8.dp,
            height = 8.dp,
            exclusiveZone = ExclusiveZone.Yield,
        )
    }
}
