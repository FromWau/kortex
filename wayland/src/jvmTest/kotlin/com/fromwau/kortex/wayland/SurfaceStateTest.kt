package com.fromwau.kortex.wayland

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.errorOrNull
import com.fromwau.kern.result.getOrElse
import com.fromwau.kortex.compose.KortexSurfaceHandle
import com.fromwau.kortex.compose.LocalKortexSurface
import com.fromwau.kortex.compose.SurfaceState
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay
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

                assertEquals(SurfaceState.Running, firstRead.get(), "content's first read of its handle was not Running")
                assertEquals(SurfaceState.Running, shell.activeSurfaces.single().state, "the ActiveSurface did not read Running")
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
                assertEquals(SurfaceState.Closed, active.state, "the ActiveSurface kept from before did not read Closed")
                assertEquals(
                    SurfaceState.Closed,
                    assertNotNull(handle.get(), "content never saw a LocalKortexSurface").state,
                    "the handle content held did not read Closed",
                )
            }
        }
    }

    @Test
    fun `a CompositorChoice surface the compositor closes reads Closed and the one placed in its stead reads Running`() {
        onShell(stateSpec {}) { shell ->
            shell.useOrFail {
                val original = shell.activeSurfaces.single()

                original.surface.simulateCompositorClose()
                val replaced = shell.pumpOrFail(PUMP_MILLIS) {
                    shell.activeSurfaces.singleOrNull()?.let { it !== original } ?: false
                }

                assertTrue(replaced, "the shell never placed a surface in the stead of the one the compositor closed")
                assertEquals(SurfaceState.Closed, original.state, "the surface the compositor closed did not read Closed")
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
            assertNull(shell.pump(SETTLE_MILLIS).errorOrNull(), "content that only throws in its cleanup must keep running")
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
    private fun ThrowingOnClose() {
        DisposableEffect(Unit) { onDispose { error(CLEANUP_FAILURE) } }
    }

    private companion object {
        const val NAMESPACE = "kortex-state"
        const val SECOND_NAMESPACE = "kortex-state-second"
        const val EFFECT_FAILURE = "an effect threw"
        const val CLEANUP_FAILURE = "cleanup threw as the shell closed"
        const val SETTLE_MILLIS = 300L
        const val EFFECT_DELAY_MILLIS = 50L
        const val PUMP_MILLIS = 4_000L

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
