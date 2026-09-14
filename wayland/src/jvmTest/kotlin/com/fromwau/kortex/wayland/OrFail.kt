package com.fromwau.kortex.wayland

import androidx.compose.runtime.Composable
import com.fromwau.kern.result.getOrElse
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
