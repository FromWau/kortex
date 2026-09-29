package com.fromwau.kortex.wayland

import androidx.compose.runtime.Composable
import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.errorOrNull
import com.fromwau.kern.result.getOrElse
import com.fromwau.kortex.compose.KortexPlatform
import java.lang.foreign.MemorySegment
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/** [KortexShell.createApplication] for a test whose application starts cleanly: an error fails the test. */
internal fun KortexShell.Companion.createApplicationOrFail(
    display: WaylandDisplay,
    content: @Composable KortexApplicationScope.() -> Unit,
): KortexShell =
    createApplication(display, content = content).getOrElse { error -> fail("the application failed to start: $error") }

/** [KortexShell.pump] for a test whose content never throws: a run that ends in an error fails the test. */
internal fun KortexShell.pumpOrFail(timeoutMillis: Long, predicate: () -> Boolean = { false }): Boolean =
    pump(timeoutMillis, predicate).getOrElse { error -> fail("the run ended in $error") }

/** Runs exactly one loop pass: [KortexShell.pump] with no time left runs one before it returns. */
internal fun KortexShell.passOrFail() {
    pumpOrFail(timeoutMillis = 0)
}

/** [KortexSurface.pump] for a test whose content never throws: a surface that fails fails the test. */
internal fun KortexSurface.pumpOrFail(timeoutMillis: Long, predicate: () -> Boolean = { false }): Boolean =
    pump(timeoutMillis, predicate).getOrElse { error -> fail("the surface failed with $error") }

/** Runs [block] and closes the shell, as `use` would; a close that fails fails the test unless [block] did. */
internal inline fun <T> KortexShell.useOrFail(block: (KortexShell) -> T): T {
    val value = try {
        block(this)
    } catch (failure: Throwable) {
        close()
        throw failure
    }
    close().getOrElse { error -> fail("closing the shell failed: $error") }
    return value
}

/** Starts an application of [content] and hands it to [block]; the application and connection close after. */
internal fun onApplication(
    content: @Composable KortexApplicationScope.() -> Unit,
    block: (KortexShell) -> Unit,
) {
    val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }
    display.use { KortexShell.createApplicationOrFail(display, content).useOrFail(block) }
}

/**
 * A surface of [config] with a scene attached to it, the pair a shell builds around one surface call; both are the
 * caller's to close, the surface first. A surface that cannot be created or attached fails the test.
 *
 * [onStartDrag] is what a drag out of it is answered by. Its default is the refusal a surface with no clipboard
 * behind it gives, so a drag test written here and never handed a real one fails rather than passing on silence.
 */
internal fun bareSurface(
    display: WaylandDisplay,
    config: SurfaceConfig,
    output: MemorySegment = MemorySegment.NULL,
    platform: KortexPlatform = KortexPlatform.None,
    onStartDrag: (clip: Clip, origin: MemorySegment) -> EmptyResult<ClipboardError> = ::refuseDrag,
): Pair<KortexSurface, SurfaceScene> {
    val loop = LoopQueue(display::wake)
    val scene = SurfaceScene(config.namespace, loop, platform, onCrash = {})
    val surface = KortexSurface
        .createOnLayer(display, config, output = output, loopQueue = loop, onStartDrag = onStartDrag)
        .getOrElse { error -> fail("the surface was not created: $error") }
    surface.attach(scene).getOrElse { error ->
        surface.close()
        scene.close()
        fail("the scene was not attached to the surface: $error")
    }
    return surface to scene
}

/** Runs [block] on a [bareSurface] of [config] and closes the pair after, whatever [block] does. */
internal fun onBareSurface(
    display: WaylandDisplay,
    config: SurfaceConfig,
    output: MemorySegment = MemorySegment.NULL,
    platform: KortexPlatform = KortexPlatform.None,
    onStartDrag: (clip: Clip, origin: MemorySegment) -> EmptyResult<ClipboardError> = ::refuseDrag,
    block: (surface: KortexSurface, scene: SurfaceScene) -> Unit,
) {
    val (surface, scene) = bareSurface(display, config, output, platform, onStartDrag)
    try {
        block(surface, scene)
    } finally {
        // Before the scene: its seat keeps delivering into a composition the close is about to dispose.
        surface.close()
        scene.close()
    }
}

/**
 * The settings the one surface [content] composes asks for, read before any pass could place it: nothing of the
 * surface reaches the compositor, so this is safe even for a surface that would take the keyboard as it maps.
 */
internal fun settingsAskedBy(content: @Composable KortexApplicationScope.() -> Unit): LayerSettings {
    var asked: SurfaceSettings? = null
    onApplication(content) { shell ->
        val queued = shell.queuedSettings
        assertEquals(1, queued.size, "the content asked for ${queued.size} surfaces, not one")
        asked = queued.first()
    }
    val kind = asked?.let { it::class.simpleName } ?: "nothing"
    return assertIs<LayerSettings>(asked, "the content asked for $kind, not a layer surface")
}

/** Pumps [shell] until [shell] has placed [count] surfaces; the test fails if it never does. */
internal fun awaitPlaced(
    shell: KortexShell,
    count: Int = 1,
) {
    assertTrue(
        shell.pumpOrFail(PLACED_WITHIN_MILLIS) { shell.shownSurfaces.size == count },
        "the application never placed $count surfaces",
    )
}

/** Whether the surface [this] watches has ended, which a pump waits for. */
internal val SurfaceState.hasEnded: Boolean get() = status is SurfaceStatus.Ended

/** Fails with [message] unless the surface [this] watches has ended, with [ending]. */
internal fun SurfaceState.assertEnded(
    ending: Result<SurfaceEnd, KortexError>,
    message: String,
) {
    assertEquals(SurfaceStatus.Ended(ending), status, message)
}

/** The crash the surface [this] watches ended with; the test fails with [message] if it ended any other way. */
internal fun SurfaceState.crashOrFail(message: String): KortexError.SurfaceCrashed {
    val ended = assertIs<SurfaceStatus.Ended>(status, "$message: $status")
    return assertIs<KortexError.SurfaceCrashed>(ended.result.errorOrNull(), "$message: ${ended.result}")
}

private const val PLACED_WITHIN_MILLIS = 4_000L
