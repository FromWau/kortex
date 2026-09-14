package com.fromwau.kortex.wayland

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.getOrElse
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.future

/**
 * Copies and pastes against the desktop's own clipboard, through `wl-copy` and `wl-paste`.
 *
 * The compositor hands a client the selection only while one of its surfaces has keyboard focus, so each
 * test puts up a small [KeyboardInteractivity.Exclusive] surface, which takes the keyboard from whatever the
 * user is typing into. Each test also replaces whatever the desktop's clipboard held.
 */
class ClipboardFocusTest {
    @Test
    fun `a text wl-copy sets is read back`() = withFocusedShell { shell, _ ->
        try {
            runWlCopy(COPIED)
            val read = shell.retryUntil({ it == Ok(COPIED) }) { shell.clipboard.readText() }
            assertEquals(Ok(COPIED), read, "the clipboard never read back the text wl-copy set")
        } finally {
            runWlCopy("--clear")
        }
    }

    @Test
    fun `a text the clipboard sets is what wl-paste prints`() = withFocusedShell { shell, display ->
        // Retried: setting quotes the serial of the keyboard's enter, which arrives once the surface has focus.
        val set = shell.retryUntil({ it is Ok }) { shell.clipboard.setText(PASTED) }
        assertEquals(Ok(Unit), set, "the clipboard never set the selection")
        // So the compositor has taken the selection before wl-paste asks it for one.
        display.roundtrip()

        val paste = ProcessBuilder("wl-paste", "--no-newline").start()
        try {
            // Pumped rather than waited on: wl-paste reads from this client's own source, which only a pump serves.
            assertTrue(shell.pumpOrFail(PROCESS_MILLIS) { !paste.isAlive }, "wl-paste never exited")
            val printed = paste.inputStream.readAllBytes().decodeToString()
            val complaint = paste.errorStream.readAllBytes().decodeToString()
            assertEquals(0, paste.exitValue(), "wl-paste failed: $complaint")
            assertEquals(PASTED, printed, "wl-paste printed something other than the text the clipboard set")
        } finally {
            paste.destroyForcibly()
        }
    }

    @Test
    fun `a selection offered only as an image reads back as NoText`() = withFocusedShell { shell, _ ->
        try {
            // A text first, read back: an image the desktop held before the test cannot then pass for this one.
            runWlCopy(MARKER)
            val marked = shell.retryUntil({ it == Ok(MARKER) }) { shell.clipboard.readText() }
            assertEquals(Ok(MARKER), marked, "the clipboard never read back the marker wl-copy set")
            runWlCopy("--type", "image/png", stdin = PNG_SIGNATURE)
            val read = shell.retryUntil({ it == Err(ClipboardError.NoText) }) { shell.clipboard.readText() }
            assertEquals(Err(ClipboardError.NoText), read, "a selection with no text type did not read as NoText")
        } finally {
            runWlCopy("--clear")
        }
    }

    private fun withFocusedShell(block: (KortexShell, WaylandDisplay) -> Unit) {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }
        display.use { wayland ->
            val focus = SurfaceSpec(FOCUS_CONFIG, OutputTarget.CompositorChoice) { Box(Modifier.fillMaxSize()) }
            val shell = KortexShell.create(wayland, focus).getOrElse { error -> fail("shell creation failed: $error") }
            shell.useOrFail { block(it, wayland) }
        }
    }

    /**
     * Calls [call] until [accept] takes what it returns, pumping in between, for at most [RETRY_MILLIS], and
     * returns the last result either way. Focus, and the selection it brings, land a few events after the
     * shell places its surface.
     */
    private fun <T> KortexShell.retryUntil(accept: (T) -> Boolean, call: suspend () -> T): T {
        val deadline = System.nanoTime() + RETRY_MILLIS * NANOS_PER_MILLI
        while (true) {
            val result = awaitCall(call)
            if (accept(result) || System.nanoTime() >= deadline) return result
            pumpOrFail(RETRY_INTERVAL_MILLIS)
        }
    }

    /** Starts [call] and pumps until it returns: the loop work it waits on runs only inside a pump. */
    private fun <T> KortexShell.awaitCall(call: suspend () -> T): T {
        val result = CoroutineScope(Dispatchers.Unconfined).future { call() }
        assertTrue(pumpOrFail(CALL_MILLIS) { result.isDone }, "the call never returned while the shell was pumped")
        return result.get()
    }

    /** Runs `wl-copy` until it has handed the selection to the copy of itself it forks to serve it. */
    private fun runWlCopy(vararg args: String, stdin: ByteArray = ByteArray(0)) {
        // Only stderr stays open in the fork that serves the selection: wl-copy already nulls stdin and stdout first.
        val copy = ProcessBuilder("wl-copy", *args)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        try {
            copy.outputStream.use { it.write(stdin) }
            assertTrue(copy.waitFor(PROCESS_MILLIS, TimeUnit.MILLISECONDS), "wl-copy ${args.toList()} never exited")
            assertEquals(0, copy.exitValue(), "wl-copy ${args.toList()} failed")
        } finally {
            // Ends only a wl-copy that never exited; the copy it forked is a process of its own.
            copy.destroyForcibly()
        }
    }

    private companion object {
        const val NAMESPACE = "kortex-clipboard-focus"
        const val SPECK_SIZE = 8
        const val COPIED = "Grüße aus kortex"
        const val PASTED = "kortex hat kopiert"
        const val MARKER = "kortex vor dem Bild"

        // A PNG file's signature: bytes that are no text, under the type wl-copy is told they are.
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

        const val RETRY_MILLIS = 5000L
        const val RETRY_INTERVAL_MILLIS = 100L

        // Longer than a read's own timeout, so a read that times out still returns inside it.
        const val CALL_MILLIS = 4000L
        const val PROCESS_MILLIS = 4000L
        const val NANOS_PER_MILLI = 1_000_000L

        val FOCUS_CONFIG = SurfaceConfig(
            namespace = NAMESPACE,
            layer = Layer.Overlay,
            anchor = setOf(Edge.Bottom, Edge.Right),
            width = SPECK_SIZE.dp,
            height = SPECK_SIZE.dp,
            exclusiveZone = ExclusiveZone.Yield,
            keyboard = KeyboardInteractivity.Exclusive,
        )
    }
}
