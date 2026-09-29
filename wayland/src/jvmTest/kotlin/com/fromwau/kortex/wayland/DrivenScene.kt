package com.fromwau.kortex.wayland

import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.fromwau.kern.result.EmptyResult
import com.fromwau.kortex.compose.ContentFailure
import com.fromwau.kortex.compose.KortexPlatform
import com.fromwau.kortex.compose.KortexScene
import org.jetbrains.skia.Surface

/**
 * One pass of the loop driving a scene: the work content queued, then a frame at [frameTimeNanos].
 *
 * The order is `KortexShell.serviceSurfaces`'s own, which runs `loopQueue.runPass()` before it services a
 * surface. A render without the pass before it draws a composition whose effects have not run.
 */
internal typealias SceneTick = (frameTimeNanos: Long) -> EmptyResult<ContentFailure>

/**
 * Runs [block] on a scene of [size], a raster to render it into and a [SceneTick] to drive it; all three go
 * when it returns.
 *
 * Built the way [SurfaceScene] builds one: the frame context is a real [LoopQueue] under a [SurfaceWork],
 * and the thread that calls [block] is the only one that ever runs what lands there, as a shell's loop
 * thread is. A scene handed `Dispatchers.Unconfined` needs no dispatch at all, so everything content queues
 * runs at whatever point queued it: an ordering the shipping host never takes, and one in which a test
 * cannot tell that it depends on a frame having happened.
 */
internal fun onScene(
    size: IntSize,
    platform: KortexPlatform = KortexPlatform.None,
    block: (scene: KortexScene, raster: Surface, tick: SceneTick) -> Unit,
) {
    // Nothing to wake: the caller below is this loop's only thread, and it runs a pass whenever it ticks.
    val loop = LoopQueue { }
    val work = SurfaceWork()

    Surface.makeRasterN32Premul(size.width, size.height).use { raster ->
        try {
            KortexScene(
                size = size,
                density = Density(1f),
                layoutDirection = LayoutDirection.Ltr,
                frameContext = loop + work,
                onInvalidate = {},
                platform = platform,
            ).use { scene ->
                block(scene, raster) { frameTimeNanos ->
                    loop.runPass()
                    scene.render(raster.canvas.asComposeCanvas(), frameTimeNanos)
                }
            }
        } finally {
            // As SurfaceScene's own close does: the recomposer leaves Compose's global snapshot observers
            // only once its cancelled run loop resumes, and that resumption is queued work like any other.
            loop.drain(work)
            work.close()
        }
    }
}
