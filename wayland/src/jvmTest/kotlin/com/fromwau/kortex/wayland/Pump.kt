package com.fromwau.kortex.wayland

import com.fromwau.kern.result.getOrElse
import kotlin.test.fail

/** [KortexShell.pump] for a test whose content never throws: a run that ends in an error fails the test. */
internal fun KortexShell.pumpOrFail(timeoutMillis: Long, predicate: () -> Boolean = { false }): Boolean =
    pump(timeoutMillis, predicate).getOrElse { error -> fail("the run ended in $error") }

/** [KortexSurface.pump] for a test whose content never throws: a surface that fails fails the test. */
internal fun KortexSurface.pumpOrFail(timeoutMillis: Long, predicate: () -> Boolean = { false }): Boolean =
    pump(timeoutMillis, predicate).getOrElse { error -> fail("the surface failed with $error") }
