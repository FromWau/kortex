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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
        val writer = thread(name = "kortex-pipe-writer") { writePipeAndClose(pipe.writeFd, sent, LONG_TIMEOUT_MILLIS) }
        try {
            val read = readPipeToEnd(pipe.readFd, LONG_TIMEOUT_MILLIS)
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
    fun `a writer that never stops writing ends the read as ReadTimedOut`() {
        val pipe = pipeOrFail()
        val stop = AtomicBoolean(false)
        val writer = thread(name = "kortex-endless-writer") { writeUntilStopped(pipe.writeFd, stop) }
        val read = CompletableFuture.supplyAsync { readPipeToEnd(pipe.readFd, SHORT_TIMEOUT_MILLIS) }
        try {
            val result = try {
                read.get(TEST_BOUND_MILLIS, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                fail("the read was still taking chunks ${TEST_BOUND_MILLIS}ms into a ${SHORT_TIMEOUT_MILLIS}ms timeout")
            }
            assertEquals(Err(ClipboardError.ReadTimedOut), result)
        } finally {
            stop.set(true)
            // Joined before its fd closes; closing that fd is what ends a read that ignores its timeout.
            writer.join(JOIN_MILLIS)
            LibC.close(pipe.writeFd)
            runCatching { read.get(TEST_BOUND_MILLIS, TimeUnit.MILLISECONDS) }
            LibC.close(pipe.readFd)
        }
    }

    @Test
    fun `a read whose time is up takes nothing more from a pipe that still has data`() {
        val pipe = pipeOrFail()
        try {
            Arena.ofConfined().use { arena -> LibC.write(pipe.writeFd, arena.allocateFrom(COPIED)) }
            assertEquals(Err(ClipboardError.ReadTimedOut), readPipeToEnd(pipe.readFd, timeoutMillis = 0L))
            val unread = LibC.poll(intArrayOf(pipe.readFd), intArrayOf(LibC.POLLIN), System.nanoTime()).single()
            assertTrue(unread and LibC.POLLIN != 0, "the read took from the pipe after its time was up")
        } finally {
            LibC.close(pipe.writeFd)
            LibC.close(pipe.readFd)
        }
    }

    @Test
    fun `a write no reader drains gives up at its timeout and closes the fd`() {
        val pipe = pipeOrFail()
        // More than the pipe holds, so the write has to wait on a reader that never reads.
        val write = CompletableFuture.runAsync {
            writePipeAndClose(pipe.writeFd, ByteArray(PIPE_BYTES), SHORT_TIMEOUT_MILLIS)
        }
        try {
            try {
                write.get(TEST_BOUND_MILLIS, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                fail("the write was still waiting ${TEST_BOUND_MILLIS}ms into a ${SHORT_TIMEOUT_MILLIS}ms timeout")
            }
            // Only a closed writer lets the read reach the end of what was written.
            val drained = readPipeToEnd(pipe.readFd, SHORT_TIMEOUT_MILLIS)
            assertTrue(drained is Ok<*>, "the write gave up without closing its fd: $drained")
        } finally {
            // Breaks the pipe under a write still waiting on it, which ends that write.
            LibC.close(pipe.readFd)
            runCatching { write.get(TEST_BOUND_MILLIS, TimeUnit.MILLISECONDS) }
        }
    }

    @Test
    fun `a write whose time is up puts nothing into a pipe that has room`() {
        val pipe = pipeOrFail()
        try {
            writePipeAndClose(pipe.writeFd, COPIED.encodeToByteArray(), timeoutMillis = 0L)
            val drained = readPipeToEnd(pipe.readFd, SHORT_TIMEOUT_MILLIS).map { it.decodeToString() }
            assertEquals(Ok(""), drained, "the write put text into the pipe after its time was up")
        } finally {
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
            val read = readPipeToEnd(pipe.readFd, LONG_TIMEOUT_MILLIS).map { it.decodeToString() }
            assertEquals(Ok(COPIED), read, "the source did not write its text and close the fd")
        } finally {
            LibC.close(pipe.readFd)
        }
    }

    @Test
    fun `a read cancelled while its request waits on the loop still closes the fd it opened`() {
        val pipe = pipeOrFail()
        val loop = LoopQueue(wake = {})
        // Unconfined runs the call up to its hop onto the loop, where it waits until the drain below.
        val call = CoroutineScope(Dispatchers.Unconfined).launch {
            readPipeOpenedOn(loop, SHORT_TIMEOUT_MILLIS) { Ok(pipe.readFd) }
        }
        call.cancel()
        loop.drain()
        // A pipe with no read end left reports POLLERR on its write end, whatever that end was asked to wait for.
        val deadline = System.nanoTime() + TEST_BOUND_MILLIS * NANOS_PER_MILLI
        val readEndClosed = LibC.poll(intArrayOf(pipe.writeFd), intArrayOf(0), deadline).single() and POLLERR != 0
        try {
            assertTrue(readEndClosed, "the read end stayed open after its call was cancelled")
        } finally {
            if (!readEndClosed) LibC.close(pipe.readFd)
            LibC.close(pipe.writeFd)
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

    @Test
    fun `a compositor that offers no clipboard still gets a shell`() {
        withoutDataDeviceManager { display ->
            val shell = KortexShell.create(display).getOrElse { error -> fail("shell creation failed: $error") }
            shell.useOrFail { }
        }
    }

    @Test
    fun `a clipboard the compositor does not offer fails every request as NoClipboard`() {
        withoutDataDeviceManager { display ->
            withClipboard(display) { clipboard ->
                assertEquals(Err(ClipboardError.NoClipboard), runBlocking { clipboard.setText(COPIED) })
                assertEquals(Err(ClipboardError.NoClipboard), runBlocking { clipboard.readText() })
            }
        }
    }

    /** A clipboard on a connection with no surface, so nothing ever gives it focus or an input serial. */
    private fun withUnfocusedClipboard(block: (WaylandClipboard) -> Unit) {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }
        display.use { wayland -> withClipboard(wayland, block) }
    }

    /** A connection that shows no `wl_data_device_manager`, as one to a compositor that never announced it would. */
    private fun withoutDataDeviceManager(block: (WaylandDisplay) -> Unit) {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }
        display.use { wayland ->
            // Forgotten on this side only, as global_remove does; nothing ever binds it.
            wayland.global(DATA_DEVICE_MANAGER)?.let { wayland.removeGlobal(it.name) }
            block(wayland)
        }
    }

    private fun withClipboard(display: WaylandDisplay, block: (WaylandClipboard) -> Unit) {
        // Unconfined runs the clipboard's loop work right here, on the thread that owns this connection.
        val clipboard = WaylandClipboard.bind(display, Dispatchers.Unconfined)
            .getOrElse { error -> fail("binding the clipboard failed: $error") }
        clipboard.use {
            // Whatever the compositor sends a new data device has arrived before the block looks.
            display.roundtrip()
            block(it)
        }
    }

    private fun offerListing(types: List<String>): DataOffer = DataOffer().also { offer ->
        Arena.ofConfined().use { arena -> types.forEach { offer.onOffer(NULL, NULL, arena.allocateFrom(it)) } }
    }

    private fun pipeOrFail(): Pipe = LibC.pipe().getOrElse { error -> fail("creating a pipe failed: $error") }

    /** Writes into [fd] a page at a time until [stop] is set or its reader goes, never blocked on a full pipe. */
    private fun writeUntilStopped(fd: Int, stop: AtomicBoolean) {
        Arena.ofConfined().use { arena ->
            val page = arena.allocate(PAGE_BYTES)
            while (!stop.get()) {
                val deadline = System.nanoTime() + WRITER_POLL_MILLIS * NANOS_PER_MILLI
                if (LibC.poll(intArrayOf(fd), intArrayOf(LibC.POLLOUT), deadline).single() == 0) continue
                if (LibC.write(fd, page) <= 0L) return
                // Paced, so what the read takes before its timeout stays small.
                Thread.sleep(WRITER_PAUSE_MILLIS)
            }
        }
    }

    private companion object {
        val NULL: MemorySegment = MemorySegment.NULL
        const val COPIED = "Grüße aus kortex"

        // Spelled out rather than read off TextMime, so reordering its entries fails here.
        val PASTE_PREFERENCE = listOf("text/plain;charset=utf-8", "text/plain", "UTF8_STRING", "STRING", "TEXT")

        // Many times what a pipe holds, so the read takes it in many chunks.
        const val PIPE_BYTES = 1024 * 1024
        const val BYTE_PATTERN = 251

        const val LONG_TIMEOUT_MILLIS = 2000L
        const val SHORT_TIMEOUT_MILLIS = 200L
        const val TEST_BOUND_MILLIS = 5000L
        const val JOIN_MILLIS = 2000L

        const val PAGE_BYTES = 4096L
        const val WRITER_POLL_MILLIS = 10L
        const val WRITER_PAUSE_MILLIS = 1L
        const val NANOS_PER_MILLI = 1_000_000L
        const val POLLERR = 0x008
        const val DATA_DEVICE_MANAGER = "wl_data_device_manager"
    }
}
