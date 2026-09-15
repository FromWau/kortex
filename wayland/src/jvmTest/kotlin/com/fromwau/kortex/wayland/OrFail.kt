package com.fromwau.kortex.wayland

import androidx.compose.runtime.Composable
import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.errorOrNull
import com.fromwau.kern.result.getOrElse
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

/** Pumps [shell] until [count] of its Shows' surfaces are on screen; the test fails if they never are. */
internal fun awaitPlaced(
    shell: KortexShell,
    count: Int = 1,
) {
    assertTrue(
        shell.pumpOrFail(PLACED_WITHIN_MILLIS) { shell.shownSurfaces.size == count },
        "the application never had $count surfaces on screen",
    )
}

/** The crash [report] carries; the test fails with [message] if it is not `Err(Failed(SurfaceCrashed))`. */
internal fun crashIn(
    report: EmptyResult<SurfaceError<*>>,
    message: String,
): KortexError.SurfaceCrashed {
    val failed = assertIs<SurfaceError.Failed>(report.errorOrNull(), "$message: $report")
    return assertIs<KortexError.SurfaceCrashed>(failed.error, "$message: $report")
}

private const val PLACED_WITHIN_MILLIS = 4_000L
