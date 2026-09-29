package com.fromwau.kortex.wayland

import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.IntSize
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * That [onScene], which every scene test in this module is written on, runs content's work off a queue the
 * way the shipping host does rather than inline.
 *
 * A scene built on `Dispatchers.Unconfined` reports that it needs no dispatch at all, so a coroutine
 * content launches runs at the point that launched it. Nothing fails and nothing can: a test whose content
 * depends on a frame having happened passes without one, and then passes for the wrong reason against a
 * host where the frame is the only thing that runs it.
 *
 * Launching into the scope content itself holds is the discriminator, since Compose starts a
 * `LaunchedEffect` body undispatched and that one runs inline whatever the context is.
 *
 * Needs no compositor: [onScene] builds a raster and a scene and nothing else.
 */
class DrivenSceneTest {
    @Test
    fun `work content launches waits for a tick rather than running where it was launched`() {
        val ran = AtomicBoolean(false)

        onScene(IntSize(SIDE, SIDE)) { scene, _, tick ->
            var contentScope: CoroutineScope? = null
            scene.setContent { contentScope = rememberCoroutineScope() }
            tick(FIRST_FRAME)

            val scope = assertNotNull(contentScope, "the content never composed, so it holds no scope to launch on")
            scope.launch { ran.set(true) }
            assertFalse(
                ran.get(),
                "work content launched ran where it was launched, so this scene's frame context queues nothing",
            )

            tick(SECOND_FRAME)
            assertTrue(
                ran.get(),
                "work content launched had still not run after a tick, which runs the queue and then a frame",
            )
        }
    }

    private companion object {
        const val SIDE = 64
        const val FIRST_FRAME = 0L
        const val SECOND_FRAME = 1L
    }
}
