package com.fromwau.kortex.wayland

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.getOrElse
import com.fromwau.kern.result.map
import com.fromwau.kortex.compose.KortexDragSource
import java.io.ByteArrayOutputStream
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ImageInfo

/**
 * The clipboard's parts that need no keyboard focus. None of these tests sets or reads the desktop's selection, not
 * even under a made-up serial: only [ClipboardFocusTest], which runs while the desktop is free, may do that.
 */
class ClipboardTest {
    @Test
    fun `a paste asks for the first text type the offer lists, utf-8 text first and TEXT last`() {
        PASTE_PREFERENCE.indices.forEach { first ->
            // Listed last-first, after a type that is not text, so neither listing order nor the extra type decides.
            val listed = listOf("image/png") + PASTE_PREFERENCE.drop(first).reversed()
            assertEquals(
                PASTE_PREFERENCE[first], preferredTextOf(listed)?.wireName,
                "an offer listing $listed",
            )
        }
    }

    @Test
    fun `a selection offered only as text has no image to paste, and one offered only as an image no text`() {
        withOffer(PASTE_PREFERENCE) { offer ->
            assertNull(offer.preferredImage, "a selection offered only as text had an image to paste")
            assertEquals(TextMime.TextPlainUtf8, offer.preferredText, "a text selection had no text to paste")
        }
        withOffer(IMAGE_OFFER) { offer ->
            assertNull(offer.preferredText, "a selection offered only as an image had text to paste")
            assertEquals(ImageMime.Png, offer.preferredImage, "an image selection had no image to paste")
        }
    }

    @Test
    fun `a copy offers an image as PNG and JPEG, and a text under the five text types`() {
        assertEquals(IMAGE_OFFER, imageClipOrFail().offeredTypes.map { it.wireName }, "an image copy's offer")
        assertEquals(PASTE_PREFERENCE, Clip.Text(COPIED).offeredTypes.map { it.wireName }, "a text copy's offer")
    }

    @Test
    fun `an image copied to the clipboard carries back the same pixels`() {
        val copied = contrastingImage()
        val pasted = decodeImage(imageClipOrFail(copied).png)
            .getOrElse { error -> fail("the copied image did not decode again: $error") }
        assertEquals(copied.width, pasted.width, "the image came back a different width")
        assertEquals(copied.height, pasted.height, "the image came back a different height")
        assertContentEquals(
            copied.toPixelMap().buffer, pasted.toPixelMap().buffer,
            "the image came back with pixels other than the ones copied",
        )
    }

    @Test
    fun `an image past the cap is refused before the selection is touched`() {
        withUnfocusedClipboard { clipboard ->
            // A call that reaches the request fails as NoInputSerial here, so TooLarge says nothing was sent.
            assertEquals(
                Err(ClipboardError.TooLarge), runBlocking { clipboard.setImage(oversizedImage()) },
                "an image past the cap was not refused before the selection was set",
            )
        }
    }

    /**
     * A drag of an image no transfer can carry still starts, and answers the transfer rather than the drag: the
     * encode that finds the size out runs at the send, since `start_drag` has to leave while the button that
     * took its grab is still down. A copy of the same image is refused outright, before the selection is set.
     */
    @Test
    fun `a dragged image past the cap still offers its types, and has nothing to send under them`() {

        val dragged = KortexDragSource.Image(oversizedImage()).asClip()

        assertEquals(IMAGE_OFFER, dragged.offeredTypes.map { it.wireName }, "a dragged image's offer")
        assertEquals(
            Err(ClipboardError.TooLarge), dragged.bytesFor(ImageMime.Png),
            "a dragged image past the cap had bytes to send under PNG after all",
        )
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
    fun `a reader pacing slower than the write's idle timeout still receives every byte`() {
        val pipe = pipeOrFail()
        val sent = ByteArray(PIPE_BYTES) { index -> (index % BYTE_PATTERN).toByte() }
        val stop = AtomicBoolean(false)
        // Paced comfortably under the timeout, so the whole transfer outlasts it without any one gap reaching it.
        val write = CompletableFuture.runAsync { writePipeAndClose(pipe.writeFd, sent, SHORT_TIMEOUT_MILLIS) }
        val read = CompletableFuture.supplyAsync { readPaced(pipe.readFd, stop, READER_PAUSE_MILLIS) }
        try {
            try {
                write.get(TEST_BOUND_MILLIS, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                fail("the write never finished for a reader still draining, ${TEST_BOUND_MILLIS}ms in")
            }
            val received = try {
                read.get(TEST_BOUND_MILLIS, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                fail("the paced reader never reached the end of the write")
            }
            assertContentEquals(sent, received, "a reader pacing under the timeout did not receive every byte")
        } finally {
            stop.set(true)
            runCatching { write.get(JOIN_MILLIS, TimeUnit.MILLISECONDS) }
            runCatching { read.get(JOIN_MILLIS, TimeUnit.MILLISECONDS) }
            LibC.close(pipe.readFd)
        }
    }

    @Test
    fun `a selection bigger than the cap reads back as TooLarge`() {
        val pipe = pipeOrFail()
        val stop = AtomicBoolean(false)
        val writer = thread(name = "kortex-oversized-writer") {
            writeUntilStopped(pipe.writeFd, stop, pauseMillis = 0L)
        }
        val read = CompletableFuture.supplyAsync { readPipeToEnd(pipe.readFd, LONG_TIMEOUT_MILLIS) }
        try {
            val result = try {
                read.get(TEST_BOUND_MILLIS, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                fail("the read never gave up on a selection past the cap, ${TEST_BOUND_MILLIS}ms in")
            }
            assertEquals(Err(ClipboardError.TooLarge), result)
        } finally {
            stop.set(true)
            writer.join(JOIN_MILLIS)
            LibC.close(pipe.writeFd)
            LibC.close(pipe.readFd)
        }
    }

    @Test
    fun `a source's send writes its text to the fd and closes it`() {
        val pipe = pipeOrFail()
        var writerClosed = false
        try {
            Arena.ofShared().use { arena ->
                val mimeType = arena.allocateFrom(TextMime.TextPlainUtf8.wireName)
                DataSource(Clip.Text(COPIED), arena).onSend(NULL, NULL, mimeType, pipe.writeFd)
            }
            // Only a closed fd ends the read before its timeout, so Ok also says the source closed it.
            val read = readPipeToEnd(pipe.readFd, LONG_TIMEOUT_MILLIS).map { it.decodeToString() }
            writerClosed = read is Ok<*>
            assertEquals(Ok(COPIED), read, "the source did not write its text and close the fd")
        } finally {
            // A read that never saw the end means the source kept the fd, which is then this test's to close.
            if (!writerClosed) LibC.close(pipe.writeFd)
            LibC.close(pipe.readFd)
        }
    }

    @Test
    fun `a source's text stops being this client's own once the compositor cancels it`() {
        Arena.ofShared().use { arena ->
            val source = DataSource(Clip.Text(COPIED), arena)
            assertEquals(COPIED, source.ownedText, "a source nothing has replaced did not hold its own text")
            source.onCancelled(NULL, NULL)
            assertNull(source.ownedText, "a cancelled source still held its text as this client's own")
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
    fun `clearing the selection before any input event fails as NoInputSerial`() {
        withUnfocusedClipboard { clipboard ->
            assertEquals(Err(ClipboardError.NoInputSerial), runBlocking { clipboard.clear() })
        }
    }

    @Test
    fun `a clipboard that has never had keyboard focus has no selection to read`() {
        withUnfocusedClipboard { clipboard ->
            assertEquals(Err(ClipboardError.NoSelection), runBlocking { clipboard.readText() })
        }
    }

    @Test
    fun `a clipboard that has never had keyboard focus has no text to paste`() {
        withUnfocusedClipboard { clipboard -> assertFalse(clipboard.hasText, "an unfocused clipboard claimed text") }
    }

    @Test
    fun `a clipboard that owns the selection has text to paste before the compositor ever grants it`() {
        withOwnCopy { clipboard -> assertTrue(clipboard.hasText, "kortex's own copy was not text to paste") }
    }

    @Test
    fun `kortex's own copy still has text to paste once keyboard focus has left`() {
        withOwnCopy { clipboard ->
            val keyboard = Any()
            clipboard.recordKeyboardFocus(keyboard, focused = true)
            clipboard.recordKeyboardFocus(keyboard, focused = false)
            assertTrue(clipboard.hasText, "kortex's own copy stopped being text to paste as focus left")
        }
    }

    @Test
    fun `kortex's own copy reads back as its text while no surface has keyboard focus`() {
        withOwnCopy { clipboard ->
            assertEquals(Ok(COPIED), runBlocking { clipboard.readText() }, "an unfocused read of kortex's own copy")
        }
    }

    @Test
    fun `kortex's own copy reads back as its text while a surface has keyboard focus`() {
        withOwnCopy { clipboard ->
            clipboard.recordKeyboardFocus(Any(), focused = true)
            assertEquals(Ok(COPIED), runBlocking { clipboard.readText() }, "a focused read of kortex's own copy")
        }
    }

    @Test
    fun `the clipboard quotes the latest input serial it is handed`() {
        withUnfocusedClipboard { clipboard ->
            // Nothing here sets the selection, so neither made-up serial reaches the compositor.
            clipboard.recordInputSerial(EARLIER_SERIAL)
            clipboard.recordInputSerial(LATEST_SERIAL)
            assertEquals(LATEST_SERIAL, clipboard.inputSerial, "the clipboard kept a serial other than the latest")
        }
    }

    @Test
    fun `a clipboard the compositor does not offer never has text to paste`() {
        withoutDataDeviceManager { display ->
            withClipboard(display) { clipboard ->
                assertFalse(clipboard.hasText, "a clipboard not offered claimed text")
            }
        }
    }

    @Test
    fun `a compositor that offers no clipboard still gets a shell`() {
        withoutDataDeviceManager { display ->
            KortexShell.createApplicationOrFail(display) { }.useOrFail { }
        }
    }

    @Test
    fun `a clipboard the compositor does not offer fails every request as NoClipboard`() {
        withoutDataDeviceManager { display ->
            withClipboard(display) { clipboard ->
                assertEquals(Err(ClipboardError.NoClipboard), runBlocking { clipboard.setText(COPIED) })
                assertEquals(Err(ClipboardError.NoClipboard), runBlocking { clipboard.readText() })
                assertEquals(Err(ClipboardError.NoClipboard), runBlocking { clipboard.clear() })
            }
        }
    }

    @Test
    fun `the clipboard asks for the newest wl_data_device_manager its interface declares`() {
        assertEquals(
            LibWayland.interfaceVersion(LibWayland.dataDeviceManagerInterface), WlVersion.DATA_DEVICE_MANAGER,
            "the clipboard asks for an older wl_data_device_manager than its interface declares",
        )
    }

    /** A clipboard on a connection with no surface, so nothing ever gives it focus or an input serial. */
    private fun withUnfocusedClipboard(block: (WaylandClipboard) -> Unit) {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }
        display.use { wayland -> withClipboard(wayland, block) }
    }

    /** An unfocused clipboard whose own copy is [COPIED], made so without telling the compositor. */
    private fun withOwnCopy(block: (WaylandClipboard) -> Unit) {
        withUnfocusedClipboard { clipboard ->
            Arena.ofShared().use { arena ->
                // recordOwnedSource reaches the own-copy state setText would leave, without setText's wire call.
                clipboard.recordOwnedSource(DataSource(Clip.Text(COPIED), arena))
                try {
                    block(clipboard)
                } finally {
                    // Even on failure: the clipboard's close would destroy this source, which was never a real proxy.
                    clipboard.recordOwnedSource(null)
                }
            }
        }
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

    /** What a paste asks an offer listing [types] for. */
    private fun preferredTextOf(types: List<String>): TextMime? = withOffer(types) { it.preferredText }

    /** Runs [block] on an offer listing [types], as the compositor introduces one. */
    private fun <T> withOffer(types: List<String>, block: (DataOffer) -> T): T = Arena.ofShared().use { arena ->
        val offer = DataOffer(arena)
        types.forEach { offer.onOffer(NULL, NULL, arena.allocateFrom(it)) }
        block(offer)
    }

    /** [image] encoded for a copy, failing the test rather than returning why it could not be. */
    private fun imageClipOrFail(image: ImageBitmap = contrastingImage()): Clip.Image =
        Clip.Image.of(image).getOrElse { error -> fail("the image did not encode for a copy: $error") }

    /** A small image whose neighbouring pixels are as far apart as colours get, so a lossy encoding shows. */
    private fun contrastingImage(): ImageBitmap = opaqueImage(SWATCH_SIDE, SWATCH_SIDE) { pixels ->
        for (pixel in 0 until SWATCH_SIDE * SWATCH_SIDE) {
            val base = pixel * BYTES_PER_PIXEL
            val even = (pixel + pixel / SWATCH_SIDE) % 2 == 0
            pixels[base] = if (even) 0 else FULL_CHANNEL
            pixels[base + 1] = if (even) FULL_CHANNEL else 0
            pixels[base + 2] = if (even) 0 else FULL_CHANNEL
            pixels[base + 3] = FULL_CHANNEL
        }
    }

    /** An image whose PNG is past [MAX_IMAGE_BYTES]: noise, which nothing compresses far. */
    private fun oversizedImage(): ImageBitmap = opaqueImage(OVERSIZED_WIDTH, OVERSIZED_HEIGHT) { pixels ->
        Random(NOISE_SEED).nextBytes(pixels)
    }

    /** An opaque image of [width] by [height] whose pixels [fill] writes, as skia stores them. */
    private fun opaqueImage(width: Int, height: Int, fill: (ByteArray) -> Unit): ImageBitmap {
        val info = ImageInfo.makeN32(width, height, ColorAlphaType.OPAQUE)
        val pixels = ByteArray(info.computeMinByteSize())
        fill(pixels)
        val bitmap = Bitmap()
        assertTrue(bitmap.installPixels(info, pixels, info.minRowBytes), "the bitmap did not take its pixels")
        // Immutable so that encoding shares its pixels instead of copying them, which for a large image is megabytes.
        bitmap.setImmutable()
        return bitmap.asComposeImageBitmap()
    }

    private fun pipeOrFail(): Pipe = LibC.pipe().getOrElse { error -> fail("creating a pipe failed: $error") }

    /**
     * Writes into [fd] a page at a time until [stop] is set or its reader goes, never blocked on a full pipe.
     * Paced by [pauseMillis] between pages, so what a timed-out read takes stays small; 0 for full throughput.
     */
    private fun writeUntilStopped(fd: Int, stop: AtomicBoolean, pauseMillis: Long = WRITER_PAUSE_MILLIS) {
        Arena.ofConfined().use { arena ->
            val page = arena.allocate(PAGE_BYTES)
            while (!stop.get()) {
                val deadline = System.nanoTime() + POLL_WAIT_MILLIS * NANOS_PER_MILLI
                if (LibC.poll(intArrayOf(fd), intArrayOf(LibC.POLLOUT), deadline).single() == 0) continue
                if (LibC.write(fd, page) <= 0L) return
                if (pauseMillis > 0) Thread.sleep(pauseMillis)
            }
        }
    }

    /** Reads [fd] a chunk at a time, pausing [pauseMillis] before each, until [stop] is set or its writer closes it. */
    private fun readPaced(fd: Int, stop: AtomicBoolean, pauseMillis: Long): ByteArray {
        val received = ByteArrayOutputStream()
        Arena.ofConfined().use { arena ->
            val chunk = arena.allocate(READER_CHUNK_BYTES)
            while (!stop.get()) {
                Thread.sleep(pauseMillis)
                val deadline = System.nanoTime() + POLL_WAIT_MILLIS * NANOS_PER_MILLI
                if (LibC.poll(intArrayOf(fd), intArrayOf(LibC.POLLIN), deadline).single() == 0) continue
                val count = LibC.read(fd, chunk)
                if (count <= 0L) return received.toByteArray()
                received.write(chunk.asSlice(0L, count).toArray(JAVA_BYTE))
            }
        }
        return received.toByteArray()
    }

    private companion object {
        val NULL: MemorySegment = MemorySegment.NULL
        const val COPIED = "Grüße aus kortex"

        // Spelled out rather than read off TextMime, so reordering its entries fails here.
        val PASTE_PREFERENCE = listOf("text/plain;charset=utf-8", "text/plain", "UTF8_STRING", "STRING", "TEXT")

        // Spelled out rather than read off ImageMime, so reordering or dropping an entry fails here.
        val IMAGE_OFFER = listOf("image/png", "image/jpeg")

        const val SWATCH_SIDE = 8
        const val BYTES_PER_PIXEL = 4
        val FULL_CHANNEL = 0xFF.toByte()

        // Noise this big encodes to well past the 64 MiB cap, without a bitmap any larger than that needs.
        const val OVERSIZED_WIDTH = 4096
        const val OVERSIZED_HEIGHT = 6144
        const val NOISE_SEED = 20_260_923

        // Many times what a pipe holds, so the read takes it in many chunks.
        const val PIPE_BYTES = 1024 * 1024
        const val BYTE_PATTERN = 251

        const val LONG_TIMEOUT_MILLIS = 2000L
        const val SHORT_TIMEOUT_MILLIS = 200L
        const val TEST_BOUND_MILLIS = 5000L
        const val JOIN_MILLIS = 2000L

        const val PAGE_BYTES = 4096L
        const val POLL_WAIT_MILLIS = 10L
        const val WRITER_PAUSE_MILLIS = 1L

        const val READER_CHUNK_BYTES = 65_536L

        // Well under SHORT_TIMEOUT_MILLIS, so no gap between reads reaches the write's idle bound.
        const val READER_PAUSE_MILLIS = 50L

        const val NANOS_PER_MILLI = 1_000_000L
        const val POLLERR = 0x008
        const val DATA_DEVICE_MANAGER = "wl_data_device_manager"
        const val EARLIER_SERIAL = 3
        const val LATEST_SERIAL = 7
    }
}
