package com.fromwau.kortex.wayland

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.getOrElse
import java.lang.foreign.MemorySegment
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.future

/**
 * Copies and pastes against the desktop's own clipboard, through `wl-copy` and `wl-paste`, directly, through a
 * focused text field's keys, and as keyboard focus leaves the shell or moves between its surfaces.
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

        val paste = shell.runWlPaste()
        assertEquals(0, paste.exitCode, "wl-paste failed: ${paste.complaint}")
        assertEquals(PASTED, paste.printed, "wl-paste printed something other than the text the clipboard set")
    }

    @Test
    fun `a text the clipboard sets is offered under exactly the five text types`() =
        withFocusedShell { shell, display ->
            val set = shell.retryUntil({ it is Ok }) { shell.clipboard.setText(PASTED) }
            assertEquals(Ok(Unit), set, "the clipboard never set the selection")
            // So the compositor has taken the selection before wl-paste asks it for one.
            display.roundtrip()

            val listed = shell.runWlPaste("--list-types")
            assertEquals(0, listed.exitCode, "wl-paste failed: ${listed.complaint}")
            val types = listed.printed
                .lines()
                .filter { it.isNotEmpty() }
                .toSet()
            assertEquals(OFFERED_TYPES, types, "the selection was not offered under exactly the five text types")
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

    @Test
    fun `the clipboard has text to paste while a text is the selection, and none while an image is`() =
        withFocusedShell { shell, _ ->
            try {
                runWlCopy("--type", "image/png", stdin = PNG_SIGNATURE)
                val image = shell.retryUntil({ it == Err(ClipboardError.NoText) }) { shell.clipboard.readText() }
                assertEquals(Err(ClipboardError.NoText), image, "an image never became the selection this client knew")
                assertFalse(shell.clipboard.hasText, "an image selection had text to paste")

                runWlCopy(COPIED)
                val text = shell.retryUntil({ it == Ok(COPIED) }) { shell.clipboard.readText() }
                assertEquals(Ok(COPIED), text, "the clipboard never read back the text wl-copy set")
                assertTrue(shell.clipboard.hasText, "wl-copy's text gave the clipboard no text to paste")
            } finally {
                runWlCopy("--clear")
            }
        }

    @Test
    fun `a text wl-copy sets is what Ctrl+V pastes into a focused text field`() {
        val field = AtomicReference("")
        val focused = AtomicBoolean(false)
        withFocusedShell(content = { FocusedTextField(field, focused) }) { shell, _ ->
            try {
                shell.awaitFocus(focused)
                runWlCopy(COPIED)
                // Read back first, so the paste below cannot run ahead of the selection reaching this client.
                val read = shell.retryUntil({ it == Ok(COPIED) }) { shell.clipboard.readText() }
                assertEquals(Ok(COPIED), read, "the clipboard never read back the text wl-copy set")

                shell.pressWithCtrl(KEY_V)
                val pasted = shell.pumpOrFail(PROCESS_MILLIS) { field.get() == COPIED }
                assertTrue(pasted, "Ctrl+V left the field holding '${field.get()}'")
            } finally {
                runWlCopy("--clear")
            }
        }
    }

    @Test
    fun `a text wl-copy sets is what Ctrl+V pastes into a focused state-based text field`() {
        val field = AtomicReference("")
        val focused = AtomicBoolean(false)
        withFocusedShell(content = { FocusedStateTextField(field, focused) }) { shell, _ ->
            try {
                shell.awaitFocus(focused)
                runWlCopy(COPIED)
                // Read back first, so the paste below cannot run ahead of the selection reaching this client.
                val read = shell.retryUntil({ it == Ok(COPIED) }) { shell.clipboard.readText() }
                assertEquals(Ok(COPIED), read, "the clipboard never read back the text wl-copy set")

                shell.pressWithCtrl(KEY_V)
                val pasted = shell.pumpOrFail(PROCESS_MILLIS) { field.get() == COPIED }
                assertTrue(pasted, "Ctrl+V left the state-based field holding '${field.get()}'")
            } finally {
                runWlCopy("--clear")
            }
        }
    }

    @Test
    fun `a focused text field's text, selected and copied with Ctrl+C, is what wl-paste prints`() {
        val field = AtomicReference(FIELD_TEXT)
        val focused = AtomicBoolean(false)
        withFocusedShell(content = { FocusedTextField(field, focused) }) { shell, display ->
            shell.awaitFocus(focused)
            val surface = shell.activeSurfaces.single().surface
            val before = surface.renders
            shell.pressWithCtrl(KEY_A)
            // Either field kind holds Ctrl+A's selection after a frame; the value-based one applies it only then.
            assertTrue(shell.pumpOrFail(PROCESS_MILLIS) { surface.renders > before }, "Ctrl+A never drew a frame")
            shell.pressWithCtrl(KEY_C)
            // Compose copies in a coroutine on the shell's loop, which a roundtrip alone never runs.
            assertTrue(
                shell.pumpOrFail(PROCESS_MILLIS) { shell.clipboard.ownedText == FIELD_TEXT },
                "Ctrl+C never made the field's text the clipboard's own",
            )
            // So the compositor has taken the selection before wl-paste asks it for one.
            display.roundtrip()

            val paste = shell.runWlPaste()
            assertEquals(0, paste.exitCode, "wl-paste failed: ${paste.complaint}")
            assertEquals(FIELD_TEXT, paste.printed, "wl-paste printed something other than the field's copied text")
        }
    }

    @Test
    fun `a focused state-based text field's text, selected and copied with Ctrl+C, is what wl-paste prints`() {
        val field = AtomicReference(FIELD_TEXT)
        val focused = AtomicBoolean(false)
        withFocusedShell(content = { FocusedStateTextField(field, focused) }) { shell, display ->
            shell.awaitFocus(focused)
            val surface = shell.activeSurfaces.single().surface
            val before = surface.renders
            shell.pressWithCtrl(KEY_A)
            // Either field kind holds Ctrl+A's selection after a frame; the value-based one applies it only then.
            assertTrue(shell.pumpOrFail(PROCESS_MILLIS) { surface.renders > before }, "Ctrl+A never drew a frame")
            shell.pressWithCtrl(KEY_C)
            // Compose copies in a coroutine on the shell's loop, which a roundtrip alone never runs.
            assertTrue(
                shell.pumpOrFail(PROCESS_MILLIS) { shell.clipboard.ownedText == FIELD_TEXT },
                "Ctrl+C never made the field's text the clipboard's own",
            )
            // So the compositor has taken the selection before wl-paste asks it for one.
            display.roundtrip()

            val paste = shell.runWlPaste()
            assertEquals(0, paste.exitCode, "wl-paste failed: ${paste.complaint}")
            assertEquals(FIELD_TEXT, paste.printed, "wl-paste printed something other than the field's copied text")
        }
    }

    @Test
    fun `a text the clipboard sets stays its own until wl-copy replaces it`() = withFocusedShell { shell, _ ->
        try {
            val set = shell.retryUntil({ it is Ok }) { shell.clipboard.setText(PASTED) }
            assertEquals(Ok(Unit), set, "the clipboard never set the selection")
            assertEquals(PASTED, shell.clipboard.ownedText, "the clipboard did not hold the text it set as its own")

            runWlCopy(COPIED)
            val replaced = shell.pumpOrFail(PROCESS_MILLIS) { shell.clipboard.ownedText == null }
            assertTrue(replaced, "the clipboard still held its own text after wl-copy replaced the selection")
        } finally {
            runWlCopy("--clear")
        }
    }

    @Test
    fun `a selection the clipboard clears leaves wl-paste nothing to print`() = withFocusedShell { shell, display ->
        try {
            // Another client's selection, so only a clear the compositor carries out can empty it.
            runWlCopy(COPIED)
            val read = shell.retryUntil({ it == Ok(COPIED) }) { shell.clipboard.readText() }
            assertEquals(Ok(COPIED), read, "the clipboard never read back the text wl-copy set")

            val cleared = shell.awaitCall { shell.clipboard.clear() }
            assertEquals(Ok(Unit), cleared, "the clipboard did not clear the selection")
            // So the compositor has dropped the selection before wl-paste asks it for one.
            display.roundtrip()

            val paste = shell.runWlPaste()
            assertEquals("", paste.printed, "wl-paste printed a selection the clipboard had cleared")
            assertNotEquals(0, paste.exitCode, "wl-paste found a selection the clipboard had cleared")
        } finally {
            runWlCopy("--clear")
        }
    }

    @Test
    fun `a text the clipboard sets stops being its own as it clears the selection`() = withFocusedShell { shell, _ ->
        val set = shell.retryUntil({ it is Ok }) { shell.clipboard.setText(PASTED) }
        assertEquals(Ok(Unit), set, "the clipboard never set the selection")
        assertEquals(PASTED, shell.clipboard.ownedText, "the clipboard did not hold the text it set as its own")

        val cleared = shell.awaitCall { shell.clipboard.clear() }
        assertEquals(Ok(Unit), cleared, "the clipboard did not clear the selection")
        assertNull(shell.clipboard.ownedText, "the clipboard still held its own text after clearing the selection")
    }

    @Test
    fun `another client's text reads as NoSelection once the last of the shell's keyboards loses focus`() =
        withFocusedShell { shell, _ ->
            try {
                runWlCopy(COPIED)
                val read = shell.retryUntil({ it == Ok(COPIED) }) { shell.clipboard.readText() }
                assertEquals(Ok(COPIED), read, "the clipboard never read back the text wl-copy set")

                // A second surface's keyboard, leaving first: then only the enter the compositor sent holds focus.
                val sibling = Any()
                shell.clipboard.recordKeyboardFocus(sibling, focused = true)
                shell.clipboard.recordKeyboardFocus(sibling, focused = false)
                assertEquals(
                    Ok(COPIED), shell.awaitCall { shell.clipboard.readText() },
                    "focus leaving one keyboard lost the selection while the surface's own keyboard still had focus",
                )

                shell.makeUpKeyboardLeave()
                assertEquals(
                    Err(ClipboardError.NoSelection), shell.awaitCall { shell.clipboard.readText() },
                    "an unfocused read of another client's text did not read as NoSelection",
                )
                assertFalse(shell.clipboard.hasText, "another client's text was still text to paste without focus")
            } finally {
                runWlCopy("--clear")
            }
        }

    @Test
    fun `another client's text reads back after keyboard focus moves to another of the shell's surfaces`() =
        withFocusedShell { shell, _ ->
            try {
                runWlCopy(COPIED)
                val read = shell.retryUntil({ it == Ok(COPIED) }) { shell.clipboard.readText() }
                assertEquals(Ok(COPIED), read, "the clipboard never read back the text wl-copy set")

                // As a move to a second surface: the enter follows every leave, and need not bring a new selection.
                shell.makeUpKeyboardLeave()
                shell.clipboard.recordKeyboardFocus(Any(), focused = true)
                assertEquals(
                    Ok(COPIED), shell.awaitCall { shell.clipboard.readText() },
                    "focus moving between the shell's own surfaces lost another client's text",
                )
            } finally {
                runWlCopy("--clear")
            }
        }

    private fun withFocusedShell(
        content: @Composable () -> Unit = { Box(Modifier.fillMaxSize()) },
        block: (KortexShell, WaylandDisplay) -> Unit,
    ) {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }
        display.use { wayland ->
            val focus = SurfaceSpec(FOCUS_CONFIG, OutputTarget.CompositorChoice, content)
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

    /** Pumps until the surface has the keyboard and its text field has taken focus in the composition. */
    private fun KortexShell.awaitFocus(focused: AtomicBoolean) {
        val ready = pumpOrFail(RETRY_MILLIS) { focused.get() && clipboard.inputSerial != null }
        assertTrue(ready, "the text field never had keyboard focus")
    }

    /**
     * Presses [code] with Ctrl held on the focused surface's own keyboard, as the compositor reports keys. Every
     * event quotes the latest real serial, since the clipboard quotes whatever serial the last key carried.
     */
    private fun KortexShell.pressWithCtrl(code: Int) {
        val keyboard = assertNotNull(
            activeSurfaces.single().surface.keyboardInput,
            "the focused surface has no keyboard",
        )
        assertTrue(keyboard.hasKeymap, "the compositor never delivered a keymap")
        val serial = assertNotNull(clipboard.inputSerial, "no input event has reached the shell's surface")
        keyboard.onModifiers(NULL, NULL, serial, CTRL_MASK, 0, 0, 0)
        keyboard.onKey(NULL, NULL, serial, 0, code, PRESSED)
        keyboard.onKey(NULL, NULL, serial, 0, code, RELEASED)
        keyboard.onModifiers(NULL, NULL, serial, 0, 0, 0, 0)
    }

    /** Makes up a leave on the focused surface's own keyboard, on this side only: the compositor keeps it focused. */
    private fun KortexShell.makeUpKeyboardLeave() {
        val keyboard = assertNotNull(
            activeSurfaces.single().surface.keyboardInput,
            "the focused surface has no keyboard",
        )
        val serial = assertNotNull(clipboard.inputSerial, "no input event has reached the shell's surface")
        keyboard.onLeave(NULL, NULL, serial, NULL)
    }

    /** Runs `wl-paste` with [args] while pumping: it may read this client's own source, which only a pump serves. */
    private fun KortexShell.runWlPaste(vararg args: String = arrayOf("--no-newline")): Pasted {
        val paste = ProcessBuilder("wl-paste", *args).start()
        try {
            assertTrue(pumpOrFail(PROCESS_MILLIS) { !paste.isAlive }, "wl-paste never exited")
            return Pasted(
                exitCode = paste.exitValue(),
                printed = paste.inputStream.readAllBytes().decodeToString(),
                complaint = paste.errorStream.readAllBytes().decodeToString(),
            )
        } finally {
            paste.destroyForcibly()
        }
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

    /** A text field that takes focus in its composition, starting from [field] and mirroring every edit into it. */
    @Composable
    private fun FocusedTextField(field: AtomicReference<String>, focused: AtomicBoolean) {
        // Compose state drives the field: an edit kept only in the AtomicReference would never recompose it.
        var text by remember { mutableStateOf(field.get()) }
        val requester = remember { FocusRequester() }
        BasicTextField(
            value = text,
            onValueChange = {
                text = it
                field.set(it)
            },
            modifier = Modifier
                .focusRequester(requester)
                .onFocusChanged { focused.set(it.isFocused) },
        )
        LaunchedEffect(Unit) { requester.requestFocus() }
    }

    /** [FocusedTextField], built on `BasicTextField(TextFieldState)` instead of the value-based overload. */
    @Composable
    private fun FocusedStateTextField(field: AtomicReference<String>, focused: AtomicBoolean) {
        val state = remember { TextFieldState(field.get()) }
        val requester = remember { FocusRequester() }
        LaunchedEffect(state) { snapshotFlow { state.text.toString() }.collect(field::set) }
        BasicTextField(
            state = state,
            modifier = Modifier
                .focusRequester(requester)
                .onFocusChanged { focused.set(it.isFocused) },
        )
        LaunchedEffect(Unit) { requester.requestFocus() }
    }

    /** What `wl-paste` exited with, printed, and wrote to stderr. */
    private class Pasted(val exitCode: Int, val printed: String, val complaint: String)

    private companion object {
        const val NAMESPACE = "kortex-clipboard-focus"
        const val SPECK_SIZE = 8
        const val COPIED = "Grüße aus kortex"
        const val PASTED = "kortex hat kopiert"
        const val MARKER = "kortex vor dem Bild"
        const val FIELD_TEXT = "kortex im Textfeld"

        // A PNG file's signature: bytes that are no text, under the type wl-copy is told they are.
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

        // Spelled out rather than read off TextMime, so dropping one of its entries fails here.
        val OFFERED_TYPES = setOf("text/plain;charset=utf-8", "text/plain", "UTF8_STRING", "STRING", "TEXT")

        const val RETRY_MILLIS = 5000L
        const val RETRY_INTERVAL_MILLIS = 100L

        // Longer than a read's own timeout, so a read that times out still returns inside it.
        const val CALL_MILLIS = 4000L
        const val PROCESS_MILLIS = 4000L
        const val NANOS_PER_MILLI = 1_000_000L

        val NULL: MemorySegment = MemorySegment.NULL
        const val PRESSED = 1
        const val RELEASED = 0

        // Control's bit in wl_keyboard.modifiers, the one KeyboardInput reads.
        const val CTRL_MASK = 1 shl 2

        // linux/input-event-codes.h
        const val KEY_A = 30
        const val KEY_C = 46
        const val KEY_V = 47

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
