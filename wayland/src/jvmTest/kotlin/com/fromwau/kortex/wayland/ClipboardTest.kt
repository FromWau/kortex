package com.fromwau.kortex.wayland

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.getOrElse
import com.fromwau.kern.result.map
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.fail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * The clipboard's parts that need no keyboard focus: which text type a paste asks for, the pipe a transfer
 * runs through, and the typed failures of a clipboard that no surface has ever focused.
 */
class ClipboardTest {
    @Test
    fun `a paste asks for the first text type the offer lists, utf-8 text first and TEXT last`() {
        PASTE_PREFERENCE.indices.forEach { first ->
            // Listed last-first, after a type that is not text, so neither listing order nor the extra type decides.
            val listed = listOf("image/png") + PASTE_PREFERENCE.drop(first).reversed()
            assertEquals(
                PASTE_PREFERENCE[first], offerListing(listed).preferredText?.wireName,
                "an offer listing $listed",
            )
        }
    }

    @Test
    fun `a pipe is read to its end once its writer closes it`() {
        val pipe = pipeOrFail()
        val sent = ByteArray(PIPE_BYTES) { index -> (index % BYTE_PATTERN).toByte() }
        // On a thread of its own: more than a pipe holds, so it has to be written while it is read.
        val writer = thread(name = "kortex-pipe-writer") { writePipeAndClose(pipe.writeFd, sent) }
        try {
            val read = readPipeToEnd(pipe.readFd, READ_TIMEOUT_MILLIS)
                .getOrElse { error -> fail("the read failed with $error") }
            assertContentEquals(sent, read, "the read did not return every byte the writer sent")
        } finally {
            // Before the join: a read that stopped early would otherwise leave the writer blocked on a full pipe.
            LibC.close(pipe.readFd)
            writer.join(JOIN_MILLIS)
        }
    }

    @Test
    fun `a writer that never closes its end ends the read as ReadTimedOut`() {
        val pipe = pipeOrFail()
        Arena.ofConfined().use { arena -> LibC.write(pipe.writeFd, arena.allocateFrom(COPIED)) }
        val read = CompletableFuture.supplyAsync { readPipeToEnd(pipe.readFd, SHORT_TIMEOUT_MILLIS) }
        try {
            val result = try {
                read.get(TEST_BOUND_MILLIS, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                fail("the read was still waiting ${TEST_BOUND_MILLIS}ms into a ${SHORT_TIMEOUT_MILLIS}ms timeout")
            }
            assertEquals(Err(ClipboardError.ReadTimedOut), result)
        } finally {
            // The writer first: closing it is what ends a read that ignores its timeout, before its fd goes.
            LibC.close(pipe.writeFd)
            runCatching { read.get(TEST_BOUND_MILLIS, TimeUnit.MILLISECONDS) }
            LibC.close(pipe.readFd)
        }
    }

    @Test
    fun `a source's send writes its text to the fd and closes it`() {
        val pipe = pipeOrFail()
        try {
            Arena.ofConfined().use { arena ->
                val mimeType = arena.allocateFrom(TextMime.TextPlainUtf8.wireName)
                DataSource(COPIED).onSend(NULL, NULL, mimeType, pipe.writeFd)
            }
            // Only a closed fd ends the read before its timeout, so Ok also says the source closed it.
            val read = readPipeToEnd(pipe.readFd, READ_TIMEOUT_MILLIS).map { it.decodeToString() }
            assertEquals(Ok(COPIED), read, "the source did not write its text and close the fd")
        } finally {
            LibC.close(pipe.readFd)
        }
    }

    @Test
    fun `setting the selection before any input event fails as NoInputSerial`() {
        withUnfocusedClipboard { clipboard ->
            assertEquals(Err(ClipboardError.NoInputSerial), runBlocking { clipboard.setText(COPIED) })
        }
    }

    @Test
    fun `a clipboard that has never had keyboard focus has no selection to read`() {
        withUnfocusedClipboard { clipboard ->
            assertEquals(Err(ClipboardError.NoSelection), runBlocking { clipboard.readText() })
        }
    }

    /** A clipboard on a connection with no surface, so nothing ever gives it focus or an input serial. */
    private fun withUnfocusedClipboard(block: (WaylandClipboard) -> Unit) {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }
        display.use { wayland ->
            // Unconfined runs the clipboard's loop work right here, on the thread that owns this connection.
            val clipboard = WaylandClipboard.bind(wayland, Dispatchers.Unconfined)
                .getOrElse { error -> fail("binding the clipboard failed: $error") }
            clipboard.use {
                // Whatever the compositor sends a new data device has arrived before the block looks.
                wayland.roundtrip()
                block(it)
            }
        }
    }

    private fun offerListing(types: List<String>): DataOffer = DataOffer().also { offer ->
        Arena.ofConfined().use { arena -> types.forEach { offer.onOffer(NULL, NULL, arena.allocateFrom(it)) } }
    }

    private fun pipeOrFail(): Pipe = LibC.pipe().getOrElse { error -> fail("creating a pipe failed: $error") }

    private companion object {
        val NULL: MemorySegment = MemorySegment.NULL
        const val COPIED = "Grüße aus kortex"

        // Spelled out rather than read off TextMime, so reordering its entries fails here.
        val PASTE_PREFERENCE = listOf("text/plain;charset=utf-8", "text/plain", "UTF8_STRING", "STRING", "TEXT")

        // Many times what a pipe holds, so the read takes it in many chunks.
        const val PIPE_BYTES = 1024 * 1024
        const val BYTE_PATTERN = 251

        const val READ_TIMEOUT_MILLIS = 2000L
        const val SHORT_TIMEOUT_MILLIS = 200L
        const val TEST_BOUND_MILLIS = 5000L
        const val JOIN_MILLIS = 2000L
    }
}
