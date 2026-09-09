package com.fromwau.kortex.wayland

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.getOrElse
import com.fromwau.kortex.compose.KortexSurfaceHandle
import com.fromwau.kortex.compose.LocalKortexSurface
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Content has no other way to end its own surface's life, so this drives that path against a real
 * compositor: whether a self-close actually reaps the bar, and whether the size content reads tracks a
 * later configure rather than a snapshot taken once at creation.
 */
class SurfaceHandleTest {
    @Test
    fun `close from the composition drops the bar and removes the namespace from hyprctl layers`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }
        val handleRef = AtomicReference<KortexSurfaceHandle>()
        val closeRequested = mutableStateOf(false)

        display.use { wayland ->
            val shell = KortexShell.create(wayland, CONFIG) {
                val surface = LocalKortexSurface.current
                handleRef.set(surface)
                val requested = closeRequested.value
                LaunchedEffect(requested) {
                    // Runs on kortex-frame, the composition's own thread; the test below sets the flag
                    // only once the surface is confirmed visible, so this is the real call site, not a
                    // stand-in invoked from the test thread. Calling it twice proves a double-close
                    // content itself triggers is a no-op too.
                    if (requested) {
                        surface.close()
                        surface.close()
                    }
                }
                Box(Modifier.fillMaxSize())
            }.getOrElse { error -> fail("shell creation failed: $error") }

            shell.use {
                val appeared = shell.pump(PUMP_TIMEOUT_MILLIS) { kortexNamespace() != null }
                assertTrue(appeared, "hyprctl never reported a $NAMESPACE- namespace; nothing to prove close() removes")
                assertNotNull(handleRef.get(), "content never saw a LocalKortexSurface")

                // Nothing on the test thread calls close() here: only this flag flip can make the bar
                // drop below, so a pass proves the composition's own close() on kortex-frame did it.
                closeRequested.value = true

                val dropped = shell.pump(PUMP_TIMEOUT_MILLIS) { shell.activeBars.isEmpty() }
                assertTrue(dropped, "the shell never dropped the bar after content called close()")

                val gone = shell.pump(PUMP_TIMEOUT_MILLIS) { kortexNamespace() == null }
                assertTrue(gone, "hyprctl layers still reports a $NAMESPACE- namespace after close()")

                // A call after teardown (bar removed, dispatcher closed) must be a no-op, not a crash.
                handleRef.get().close()
            }
        }
    }

    @Test
    fun `size reports the configured logical size and follows a later requestSize`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }
        val handleRef = AtomicReference<KortexSurfaceHandle>()

        display.use {
            val bar = KortexBar.create(it, CONFIG)
                .getOrElse { error -> fail("bar creation failed: $error") }

            bar.use {
                bar.setContent {
                    handleRef.set(LocalKortexSurface.current)
                    Box(Modifier.fillMaxSize())
                }
                bar.pump(timeoutMillis = PUMP_TIMEOUT_MILLIS)

                val handle = assertNotNull(handleRef.get(), "content never saw a LocalKortexSurface")
                val geometry = assertNotNull(Screen.geometry(NAMESPACE), "hyprctl did not report $NAMESPACE")
                assertEquals(
                    IntSize(geometry.logicalWidth, geometry.logicalHeight), handle.size,
                    "the handle's size did not match the logical size hyprctl reports for the same surface",
                )

                bar.requestSize(SPAN_ANCHORED_AXIS.dp, RESIZED_HEIGHT.dp)
                    .getOrElse { error -> fail("the resize was rejected before it reached the compositor: $error") }
                val resized = bar.pump(timeoutMillis = PUMP_TIMEOUT_MILLIS) { handle.size.height == RESIZED_HEIGHT }
                assertTrue(resized, "the handle's size never followed a later configure")
            }
        }
    }

    /** [KortexShell] suffixes the configured namespace with the output's id, unlike a bare [KortexBar]. */
    private fun kortexNamespace(): String? =
        Hyprctl.layers().values
            .flatMap { it.levels.values.flatten() }
            .map { it.namespace }
            .firstOrNull { it.startsWith("$NAMESPACE-") }

    private companion object {
        const val NAMESPACE = "kortex"
        const val BAR_HEIGHT = 32
        const val RESIZED_HEIGHT = 64
        const val PUMP_TIMEOUT_MILLIS = 4000L
        const val SPAN_ANCHORED_AXIS = 0

        val CONFIG = SurfaceConfig(
            namespace = NAMESPACE,
            height = BAR_HEIGHT.dp,
            exclusiveZone = ExclusiveZone.Reserve(BAR_HEIGHT.dp),
        )
    }
}
