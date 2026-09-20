package com.fromwau.kortex.wayland

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
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
 * compositor: whether a self-close actually reaps the surface, and whether the size content reads
 * tracks a later configure rather than a snapshot taken once at creation.
 */
class SurfaceHandleTest {
    @Test
    fun `close from the composition drops the surface and removes the namespace from hyprctl layers`() {
        val handleRef = AtomicReference<KortexSurfaceHandle>()
        val closeRequested = mutableStateOf(false)
        val content: @Composable KortexApplicationScope.() -> Unit = {
            TestSurface<Nothing>(NAMESPACE) {
                val surface = LocalKortexSurface.current
                handleRef.set(surface)
                val requested = closeRequested.value
                LaunchedEffect(requested) {
                    // The composition's own call site, run by the loop that `pump` drives; the test below sets
                    // the flag only once the surface is confirmed visible. Calling it twice proves a
                    // double-close content itself triggers is a no-op too.
                    if (requested) {
                        surface.close()
                        surface.close()
                    }
                }
            }
        }

        onApplication(content) { shell ->
            awaitPlaced(shell)
            assertNotNull(handleRef.get(), "content never saw a LocalKortexSurface")

            // Nothing in this test calls close() directly: only this flag flip can make the surface drop
            // below, so a pass proves the composition's own close() did it.
            closeRequested.value = true

            val dropped = shell.pumpOrFail(PUMP_TIMEOUT_MILLIS) { shell.shownSurfaces.isEmpty() }
            assertTrue(dropped, "the application never dropped the surface after content called close()")

            val gone = shell.pumpOrFail(PUMP_TIMEOUT_MILLIS) { Screen.geometry(NAMESPACE) == null }
            assertTrue(gone, "hyprctl layers still reports $NAMESPACE after close()")

            // A call after teardown (surface removed and closed) must be a no-op, not a crash.
            handleRef.get().close()
        }
    }

    @Test
    fun `size reports the configured logical size and follows a later requestSize`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }
        val handleRef = AtomicReference<KortexSurfaceHandle>()

        display.use {
            val surface = KortexSurface.create(it, CONFIG)
                .getOrElse { error -> fail("surface creation failed: $error") }

            surface.use {
                surface.setContent {
                    handleRef.set(LocalKortexSurface.current)
                    Box(Modifier.fillMaxSize())
                }
                surface.pumpOrFail(timeoutMillis = PUMP_TIMEOUT_MILLIS)

                val handle = assertNotNull(handleRef.get(), "content never saw a LocalKortexSurface")
                val geometry = assertNotNull(Screen.geometry(NAMESPACE), "hyprctl did not report $NAMESPACE")
                assertEquals(
                    IntSize(geometry.logicalWidth, geometry.logicalHeight), handle.size,
                    "the handle's size did not match the logical size hyprctl reports for the same surface",
                )

                surface.requestSize(SPAN_ANCHORED_AXIS.dp, RESIZED_HEIGHT.dp)
                    .getOrElse { error -> fail("the resize was rejected before it reached the compositor: $error") }
                val resized =
                    surface.pumpOrFail(timeoutMillis = PUMP_TIMEOUT_MILLIS) { handle.size.height == RESIZED_HEIGHT }
                assertTrue(resized, "the handle's size never followed a later configure")
            }
        }
    }

    private companion object {
        const val NAMESPACE = "kortex"
        const val SURFACE_HEIGHT = 32
        const val RESIZED_HEIGHT = 64
        const val PUMP_TIMEOUT_MILLIS = 4000L
        const val SPAN_ANCHORED_AXIS = 0

        val CONFIG = SurfaceConfig.panel(edge = Edge.Top, thickness = SURFACE_HEIGHT.dp).copy(namespace = NAMESPACE)
    }
}
