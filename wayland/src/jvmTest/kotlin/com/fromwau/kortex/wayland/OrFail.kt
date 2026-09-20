package com.fromwau.kortex.wayland

import androidx.compose.runtime.Composable
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.errorOrNull
import com.fromwau.kern.result.getOrElse
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
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
 * The settings the one surface [content] composes asks for, read before any pass could place it: nothing of the
 * surface reaches the compositor, so this is safe even for a surface that would take the keyboard as it maps.
 */
internal fun settingsAskedBy(content: @Composable KortexApplicationScope.() -> Unit): SurfaceSettings {
    var asked: SurfaceSettings? = null
    onApplication(content) { shell ->
        val queued = shell.queuedSettings
        assertEquals(1, queued.size, "the content asked for ${queued.size} surfaces, not one")
        asked = queued.first()
    }
    return assertNotNull(asked, "the content asked for no surface")
}

/** Pumps [shell] until [shell] has placed [count] surfaces, on screen or off; the test fails if it never does. */
internal fun awaitPlaced(
    shell: KortexShell,
    count: Int = 1,
) {
    assertTrue(
        shell.pumpOrFail(PLACED_WITHIN_MILLIS) { shell.shownSurfaces.size == count },
        "the application never placed $count surfaces",
    )
}

/** The crash [report] carries; the test fails with [message] if it is not `Err(Failed(SurfaceCrashed))`. */
internal fun crashIn(
    report: Result<SurfaceEnd, SurfaceError<*>>,
    message: String,
): KortexError.SurfaceCrashed {
    val failed = assertIs<SurfaceError.Failed>(report.errorOrNull(), "$message: $report")
    return assertIs<KortexError.SurfaceCrashed>(failed.error, "$message: $report")
}

private const val PLACED_WITHIN_MILLIS = 4_000L
