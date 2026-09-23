package com.fromwau.kortex.wayland

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.getOrElse
import com.fromwau.kern.result.map
import java.io.ByteArrayOutputStream
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image as SkiaImage

/**
 * The shell's clipboard: makes a text or an image the selection or clears it, and reads the selection as either.
 *
 * Any thread may call it. Its requests run on [loop], which dispatches onto the thread that owns the connection,
 * and a read of another client's copy waits for it on [Dispatchers.IO], so a slow source never stalls the loop.
 */
internal class WaylandClipboard private constructor(
    private val display: WaylandDisplay,
    private val loop: CoroutineDispatcher,
    // Null when the compositor offers no clipboard.
    private val bound: BoundDevice?,
) : AutoCloseable, TextClipboard {
    // Set on the loop thread; every call checks it on its caller's thread too.
    @Volatile
    private var closed = false

    // Written on the loop thread alone; ownedText reads it from any.
    @Volatile
    private var source: DataSource? = null

    /** The serial the next [setText] or [clear] quotes; loop thread only, and readable so a test can quote it too. */
    var inputSerial: Int? = null
        private set

    // Each of the shell's keyboards that has focus now, since each surface binds its own. Written on the loop thread
    // alone; hasText reads it from any.
    private val focusedKeyboards: MutableSet<Any> = ConcurrentHashMap.newKeySet()

    // The compositor vouches for its offer only while the client has focus, and sends one anew before focus returns.
    private val hasKeyboardFocus: Boolean get() = focusedKeyboards.isNotEmpty()

    override val ownedText: String? get() = source?.ownedText

    override val hasText: Boolean
        get() = ownedText != null || (hasKeyboardFocus && bound?.device?.selectionHasText == true)

    private val ownedImage: Clip.Image? get() = source?.ownedImage

    /** Keeps [serial], of a key, a keyboard enter or a button, for the next [setText] or [clear] to quote. */
    fun recordInputSerial(serial: Int) {
        inputSerial = serial
    }

    /**
     * Keeps whether [keyboard] has focus. The last leave keeps the selection's offer: focus moving between the
     * shell's own surfaces leaves every keyboard before the next one enters, and need not bring a new offer.
     */
    fun recordKeyboardFocus(keyboard: Any, focused: Boolean) {
        if (focused) {
            focusedKeyboards += keyboard
        } else {
            focusedKeyboards -= keyboard
        }
    }

    /** Makes [source] this client's own copy, or none, without telling the compositor: a test's seam onto its text. */
    fun recordOwnedSource(source: DataSource?) {
        this.source = source
    }

    /** Offers [text] under every [TextMime]. */
    override suspend fun setText(text: String): EmptyResult<ClipboardError> {
        checkOpen()
        return withContext(loop) { replaceSelection(Clip.Text(text)) }
    }

    override suspend fun clear(): EmptyResult<ClipboardError> {
        checkOpen()
        return withContext(loop) { replaceSelection(null) }
    }

    /** Answers this client's own copy from memory, and otherwise reads the first [TextMime] offered as UTF-8. */
    override suspend fun readText(): Result<String, ClipboardError> {
        checkOpen()
        ownedText?.let { return Ok(it) }
        return readPipeOpenedOn(loop, TRANSFER_TIMEOUT_MILLIS) { receiveText() }.map { it.decodeToString() }
    }

    /** Offers [image] under every [ImageMime], encoded before the compositor is asked for anything. */
    override suspend fun setImage(image: ImageBitmap): EmptyResult<ClipboardError> {
        checkOpen()
        // Off the loop thread: encoding a large image on it would stall every surface for as long as it runs.
        val clip = withContext(Dispatchers.Default) { Clip.Image.of(image) }.getOrElse { return Err(it) }
        return withContext(loop) { replaceSelection(clip) }
    }

    /** Answers this client's own copy from memory, and otherwise reads the first [ImageMime] offered. */
    override suspend fun readImage(): Result<ImageBitmap, ClipboardError> {
        checkOpen()
        ownedImage?.let { return decodeOffLoop(it.png) }
        val received = readPipeOpenedOn(loop, TRANSFER_TIMEOUT_MILLIS, MAX_IMAGE_BYTES) { receiveImage() }
        return decodeOffLoop(received.getOrElse { return Err(it) })
    }

    // Off the loop thread: decoding a large image on it would stall every surface for as long as it runs.
    private suspend fun decodeOffLoop(encoded: ByteArray): Result<ImageBitmap, ClipboardError> =
        withContext(Dispatchers.Default) { decodeImage(encoded) }

    // No loop runs once the shell has closed: a caller would wait on one forever, and a late pass reach freed proxies.
    private fun checkOpen() = check(!closed) { "the clipboard's shell has closed" }

    // A null clip clears the selection.
    private fun replaceSelection(clip: Clip?): EmptyResult<ClipboardError> {
        checkOpen()
        val bound = bound ?: return Err(ClipboardError.NoClipboard)
        val serial = inputSerial ?: return Err(ClipboardError.NoInputSerial)
        val offered = clip?.let { DataSource.create(bound.manager, it) }
        bound.device.setSelection(offered, serial)
        display.flush()
        // After set_selection, not before: destroying the selection's own source would clear the selection meanwhile.
        source?.destroy()
        source = offered
        return Ok(Unit)
    }

    private fun receiveText(): Result<Int, ClipboardError> =
        receiveSelection(ClipboardError.NoText, DataOffer::preferredText)

    private fun receiveImage(): Result<Int, ClipboardError> =
        receiveSelection(ClipboardError.NoImage, DataOffer::preferredImage)

    /** Opens a pipe on the selection under the type [pick] takes, or fails as [absent] where it lists none. */
    private fun receiveSelection(absent: ClipboardError, pick: (DataOffer) -> Mime?): Result<Int, ClipboardError> {
        checkOpen()
        val bound = bound ?: return Err(ClipboardError.NoClipboard)
        val offer = bound.device.selection?.takeIf { hasKeyboardFocus } ?: return Err(ClipboardError.NoSelection)
        val type = pick(offer) ?: return Err(absent)
        val pipe = LibC.pipe().getOrElse { return Err(ClipboardError.PipeFailed) }
        offer.receive(type, pipe.writeFd)
        // The flush sends libwayland's own duplicate; this end left open would hold the read to its timeout.
        LibC.close(pipe.writeFd)
        display.flush()
        return Ok(pipe.readFd)
    }

    /** Gives back this client's source, the device and its offers, the manager, and the seat last. */
    override fun close() {
        if (closed) return
        closed = true
        source?.destroy()
        source = null
        bound?.release()
    }

    /** The manager, a seat of the clipboard's own, and the data device taken for that seat. */
    private class BoundDevice(val manager: MemorySegment, val seat: Seat, val device: DataDevice) {
        /** Gives back the device and its offers, the manager, and the seat last. */
        fun release() {
            device.release()
            releaseManager(manager)
            // Last, since the device was taken for it.
            seat.release()
        }
    }

    companion object {
        /**
         * Binds the manager and a seat of the clipboard's own, and takes that seat's data device. On a compositor
         * that offers no manager, the clipboard fails every request as [ClipboardError.NoClipboard] instead.
         */
        fun bind(display: WaylandDisplay, loop: CoroutineDispatcher): Result<WaylandClipboard, KortexError> {
            val manager = display
                .require(DATA_DEVICE_MANAGER, LibWayland.dataDeviceManagerInterface, WlVersion.DATA_DEVICE_MANAGER)
                .getOrElse { failure ->
                    if (failure == KortexError.MissingGlobal(DATA_DEVICE_MANAGER)) {
                        return Ok(WaylandClipboard(display, loop, bound = null))
                    }
                    return Err(failure)
                }
            val seat = Seat.bind(display).getOrElse { failure ->
                releaseManager(manager)
                return Err(failure)
            }
            return Ok(WaylandClipboard(display, loop, BoundDevice(manager, seat, DataDevice.create(manager, seat))))
        }

        private fun releaseManager(manager: MemorySegment) {
            LibWayland.marshalIfSince(manager, WL_DATA_DEVICE_MANAGER_RELEASE, WL_DATA_DEVICE_MANAGER_RELEASE_SINCE)
            LibWayland.proxyDestroy(manager)
        }

        private const val DATA_DEVICE_MANAGER = "wl_data_device_manager"
        private const val WL_DATA_DEVICE_MANAGER_RELEASE = 2
        private const val WL_DATA_DEVICE_MANAGER_RELEASE_SINCE = 4
    }
}

/**
 * A type a copy offers its content under and a paste asks for, as `wl_data_source.offer` and `wl_data_offer.offer`
 * name them.
 */
internal sealed interface Mime {
    val wireName: String

    companion object {
        // Every type a copy of this client's offers and a paste of it asks for.
        private val all: List<Mime> = TextMime.entries + ImageMime.entries

        /** The entry named [wireName], or null for a type this client neither offers nor asks for. */
        fun fromWireNameOrNull(wireName: String): Mime? = all.firstOrNull { it.wireName == wireName }
    }
}

/** The text types, in the order a paste prefers them. */
internal enum class TextMime(override val wireName: String) : Mime {
    TextPlainUtf8("text/plain;charset=utf-8"),
    TextPlain("text/plain"),
    Utf8StringAtom("UTF8_STRING"),
    StringAtom("STRING"),
    TextAtom("TEXT"),
}

/** The image types, in the order a paste prefers them, each with the format skia encodes it as. */
internal enum class ImageMime(override val wireName: String, val format: EncodedImageFormat) : Mime {
    Png("image/png", EncodedImageFormat.PNG),
    Jpeg("image/jpeg", EncodedImageFormat.JPEG),
}

/** What one copy holds: the types it is offered under, and the bytes it sends under each of them. */
internal sealed interface Clip {
    /** The types a copy of this offers, in the order a paste prefers them. */
    val offeredTypes: List<Mime>

    /** What this sends under [type], or null for a type it does not offer. */
    fun bytesFor(type: Mime): ByteArray?

    /** A text, sent as the same UTF-8 under every [TextMime]. */
    class Text(val text: String) : Clip {
        private val utf8 = text.encodeToByteArray()

        override val offeredTypes: List<Mime> = TextMime.entries

        override fun bytesFor(type: Mime): ByteArray? = utf8.takeIf { type is TextMime }
    }

    /**
     * An image, encoded for every [ImageMime] as the copy is made rather than as each send runs, since a receiver
     * waits on the pipe for as long as a send takes.
     */
    class Image private constructor(private val encoded: Map<ImageMime, ByteArray>) : Clip {
        /** What a paste of this client's own copy decodes again. */
        val png: ByteArray get() = encoded.getValue(ImageMime.Png)

        override val offeredTypes: List<Mime> = ImageMime.entries

        override fun bytesFor(type: Mime): ByteArray? = encoded[type]

        companion object {
            /**
             * [image] encoded under every [ImageMime], or [ClipboardError.TooLarge] where one of those encodings
             * is larger than [MAX_IMAGE_BYTES]. Blocking: it encodes on the calling thread.
             */
            fun of(image: ImageBitmap): Result<Image, ClipboardError> {
                val encoded = ImageMime.entries.associateWith { type ->
                    encodeImage(image, type).getOrElse { return Err(it) }
                }
                return Ok(Image(encoded))
            }
        }
    }
}

/** [image] as [type] carries it, or [ClipboardError.TooLarge] where that is more than [MAX_IMAGE_BYTES]. */
private fun encodeImage(image: ImageBitmap, type: ImageMime): Result<ByteArray, ClipboardError> {
    val encoded = SkiaImage.makeFromBitmap(image.asSkiaBitmap()).use { raster ->
        val data = raster.encodeToData(type.format)
        checkNotNull(data) { "skia encoded no ${type.wireName} for a ${image.width} by ${image.height} image" }
            .use { it.bytes }
    }
    return if (encoded.size > MAX_IMAGE_BYTES) Err(ClipboardError.TooLarge) else Ok(encoded)
}

/** [encoded] as an image, or [ClipboardError.NoImage] where it is in no format skia decodes. */
internal fun decodeImage(encoded: ByteArray): Result<ImageBitmap, ClipboardError> = try {
    SkiaImage.makeFromEncoded(encoded).use { Ok(it.toComposeImageBitmap()) }
} catch (_: IllegalArgumentException) {
    Err(ClipboardError.NoImage)
}

/**
 * Opens a pipe's read end with [open] on [loop], then reads from it on [Dispatchers.IO] until its writer closes it,
 * [timeoutMillis] passes, or it grows past [maxBytes], and closes it.
 *
 * Not cancellable: a cancelled caller gets its cancellation once this returns, at most [timeoutMillis] after [loop]
 * ran [open].
 */
internal suspend fun readPipeOpenedOn(
    loop: CoroutineDispatcher,
    timeoutMillis: Long,
    maxBytes: Long = MAX_TEXT_BYTES,
    open: () -> Result<Int, ClipboardError>,
): Result<ByteArray, ClipboardError> = withContext(NonCancellable) {
    // Around both hops: a dispatcher-changing hop discards its result, fd and all, on resuming a cancelled caller.
    val readFd = withContext(loop) { open() }.getOrElse { return@withContext Err(it) }
    withContext(Dispatchers.IO) {
        try {
            readPipeToEnd(readFd, timeoutMillis, maxBytes)
        } finally {
            LibC.close(readFd)
        }
    }
}

/**
 * Reads [fd] until its writer closes it.
 *
 * @return everything read, [ClipboardError.TooLarge] once more than [maxBytes] has arrived, or
 *   [ClipboardError.ReadTimedOut] once [timeoutMillis] have passed before the writer closed its end, however much
 *   it was still sending.
 */
internal fun readPipeToEnd(
    fd: Int,
    timeoutMillis: Long,
    maxBytes: Long = MAX_TEXT_BYTES,
): Result<ByteArray, ClipboardError> {
    val deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLI
    val read = ByteArrayOutputStream()
    Arena.ofConfined().use { arena ->
        val chunk = arena.allocate(READ_CHUNK_BYTES)
        while (System.nanoTime() < deadline) {
            // Polled first: a read blocks for as long as the writer keeps its end open.
            if (LibC.poll(intArrayOf(fd), intArrayOf(LibC.POLLIN), deadline).single() == 0) continue
            val count = LibC.read(fd, chunk)
            when {
                count == 0L -> return Ok(read.toByteArray())
                count < 0L -> return Err(ClipboardError.PipeFailed)
                else -> read.write(chunk.asSlice(0L, count).toArray(JAVA_BYTE))
            }
            if (read.size() > maxBytes) return Err(ClipboardError.TooLarge)
        }
    }
    return Err(ClipboardError.ReadTimedOut)
}

/**
 * Writes [bytes] into [fd] and closes it. A reader that goes away, or takes nothing for [timeoutMillis], gets the
 * text cut short; a reader still taking bytes, however slowly, gets all of it.
 */
internal fun writePipeAndClose(fd: Int, bytes: ByteArray, timeoutMillis: Long) {
    var deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLI
    try {
        Arena.ofConfined().use { arena ->
            val buffer: MemorySegment = arena.allocateFrom(JAVA_BYTE, *bytes)
            var offset = 0L
            while (offset < buffer.byteSize() && System.nanoTime() < deadline) {
                if (LibC.poll(intArrayOf(fd), intArrayOf(LibC.POLLOUT), deadline).single() == 0) continue
                val chunk = buffer.asSlice(offset, minOf(WRITE_CHUNK_BYTES, buffer.byteSize() - offset))
                val written = LibC.write(fd, chunk)
                if (written <= 0L) return
                offset += written
                // An idle bound, not a deadline over the whole transfer: progress against it resets it.
                deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLI
            }
        }
    } finally {
        LibC.close(fd)
    }
}

/** Bounds a transfer: the read gives up this long after it began; the write gives up this long after its last byte. */
internal const val TRANSFER_TIMEOUT_MILLIS = 1000L

/** The most a read keeps of one text before giving up as [ClipboardError.TooLarge]. */
internal const val MAX_TEXT_BYTES = 16L * 1024 * 1024

/**
 * The most an image takes, on the way out as on the way in: its own number, since [MAX_TEXT_BYTES] was sized for
 * text and a screenshot of a 4K screen is about 33 MiB before anything compresses it. It bounds the transfer, not
 * the image once decoded.
 */
internal const val MAX_IMAGE_BYTES = 64L * 1024 * 1024

private const val NANOS_PER_MILLI = 1_000_000L
private const val READ_CHUNK_BYTES = 65_536L

// A pipe polls writable while a page of it is free, so a write no larger than a page never blocks.
private const val WRITE_CHUNK_BYTES = 4_096L
