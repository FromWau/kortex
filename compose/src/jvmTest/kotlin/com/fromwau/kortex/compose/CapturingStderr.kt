package com.fromwau.kortex.compose

import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * Runs [block] with `System.err` captured, and returns what was printed there meanwhile.
 *
 * A copy of the one in `:wayland`'s `LoopThread.kt`, because `:wayland` depends on `:compose` and a test helper
 * cannot travel back the other way.
 */
internal fun capturingStderr(block: () -> Unit): String {
    val captured = ByteArrayOutputStream()
    val real = System.err
    System.setErr(PrintStream(captured))
    try {
        block()
    } finally {
        System.setErr(real)
    }
    return captured.toString()
}
