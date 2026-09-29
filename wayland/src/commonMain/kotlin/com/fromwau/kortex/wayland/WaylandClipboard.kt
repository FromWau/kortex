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
import com.fromwau.kortex.compose.KortexDragSource
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

    /** The serial the next [startDrag] quotes; loop thread only, and readable so a test can quote it too. */
    var grabSerial: Int? = null
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

    /** The source of the drag this client is carrying out, and null until one starts and once it has ended. */
    val dragSource: DataSource? get() = bound?.device?.dragged

    /** Keeps [serial], of a key, a keyboard enter or a button, for the next [setText] or [clear] to quote. */
    fun recordInputSerial(serial: Int) {
        inputSerial = serial
    }

    /**
     * Keeps [serial], of the pointer button press that took the implicit grab, for the next [startDrag] to
     * quote. `wl_data_device.start_drag`'s own argument is "serial number of the implicit grab on the
     * origin", and a compositor that checks it refuses every other input serial.
     */
    fun recordPointerGrab(serial: Int) {
        grabSerial = serial
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

    /**
     * Keeps [find], which answers where a drag over one of the shell's surfaces goes. The data device is the
     * clipboard's, and every drag arrives on it whichever surface it is over.
     */
    fun recordDragDestinations(find: (surface: Long) -> DragDestination?) {
        bound?.device?.dragDestinations = find
    }

    /**
     * Drags [clip] out of [origin], one of the shell's own surfaces, offering it as a copy and nothing else; the
     * loop thread alone calls it, since it marshals where it is called rather than hopping as the requests below do.
     *
     * @return `Ok` once the compositor has been asked, [ClipboardError.NoInputSerial] where no pointer button
     *   press has reached the shell for the drag to name its grab by, or [ClipboardError.NoClipboard] on a
     *   compositor that offers none.
     */
    fun startDrag(drag: DragOut): EmptyResult<ClipboardError> {
        checkOpen()
        val bound = bound ?: return Err(ClipboardError.NoClipboard)
        // The grab's own serial, never the last input's: start_drag names one grab and the last key is not it.
        val serial = grabSerial ?: return Err(ClipboardError.NoInputSerial)
        // Named rather than passed inline: set_actions is part of making the source and must precede start_drag.
        val source = DataSource.createForDrag(bound.manager, drag.clip, drag.actions)
        source.onDragCompleted = drag.onEnded
        bound.device.startDrag(source, drag.origin, serial)
        display.flush()
        return Ok(Unit)
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

    /**
     * Reads the `text/uri-list` on the clipboard.
     *
     * Nothing answers from memory the way [readText] and [readImage] do: no [Clip] offers a file list, so
     * this client's own copy is never one.
     */
    override suspend fun readUris(): Result<List<String>, ClipboardError> {
        checkOpen()
        return readPipeOpenedOn(loop, TRANSFER_TIMEOUT_MILLIS) { receiveUris() }.map(::decodeUriList)
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

    private fun receiveUris(): Result<Int, ClipboardError> =
        receiveSelection(ClipboardError.NoUris, DataOffer::preferredUriList)

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
                .require(
                    WaylandInterface.DataDeviceManager,
                    LibWayland.dataDeviceManagerInterface,
                    WlVersion.DATA_DEVICE_MANAGER,
                )
                .getOrElse { failure ->
                    if (failure == KortexError.MissingGlobal(WaylandInterface.DataDeviceManager)) {
                        return Ok(WaylandClipboard(display, loop, bound = null))
                    }
                    return Err(failure)
                }
            val seat = Seat.bind(display).getOrElse { failure ->
                releaseManager(manager)
                return Err(failure)
            }
            val device = DataDevice.create(display, loop, manager, seat)
            return Ok(WaylandClipboard(display, loop, BoundDevice(manager, seat, device)))
        }

        private fun releaseManager(manager: MemorySegment) {
            LibWayland.marshal(manager, WL_DATA_DEVICE_MANAGER_RELEASE)
            LibWayland.proxyDestroy(manager)
        }

        private const val WL_DATA_DEVICE_MANAGER_RELEASE = 2
    }
}

/**
 * A type a copy offers its content under and a paste asks for, as `wl_data_source.offer` and `wl_data_offer.offer`
 * name them.
 */
internal sealed interface Mime {
    val wireName: String

    companion object {
        /** Every type a paste or a drop of this client's asks for, most preferred first. */
        val all: List<Mime> =
            UriListMime.entries + PortalMime.entries + TextMime.entries + ImageMime.entries

        /** The entry named [wireName], or null for a type this client neither offers nor asks for. */
        fun fromWireNameOrNull(wireName: String): Mime? = all.firstOrNull { it.wireName == wireName }
    }
}

/**
 * The list-of-files types, which only a drag brings in: no [Clip] offers one, so nothing this client copies is
 * advertised as a list of files. Ahead of the text types in [Mime.all], because an application offering both
 * sends the same files under each and only this one says that they are files.
 */
internal enum class UriListMime(override val wireName: String) : Mime {
    TextUriList("text/uri-list"),
}

/**
 * The sandboxed-transfer types, which carry no files but a key to fetch them by.
 *
 * An application in a sandbox offers one because the paths it knows mean nothing outside its own filesystem.
 * After [UriListMime] in [Mime.all] rather than before it: a list of files is the one kortex can hand over
 * itself, and a key is one the reader has to take to the portal over D-Bus before it means anything.
 */
internal enum class PortalMime(override val wireName: String) : Mime {
    FileTransfer("application/vnd.portal.filetransfer"),
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

    /**
     * What this sends under [type], one of [offeredTypes], or why it has nothing to send under it. Blocking
     * where it encodes, so never called on the loop thread.
     */
    fun bytesFor(type: Mime): Result<ByteArray, ClipboardError>

    /** A text, sent as the same UTF-8 under every [TextMime]. */
    class Text(val text: String) : Clip {
        private val utf8 = text.encodeToByteArray()

        override val offeredTypes: List<Mime> = TextMime.entries

        override fun bytesFor(type: Mime): Result<ByteArray, ClipboardError> =
            if (type is TextMime) Ok(utf8) else Err(ClipboardError.NoText)
    }

    /**
     * An image, encoded for every [ImageMime] as the copy is made rather than as each send runs, since a receiver
     * waits on the pipe for as long as a send takes.
     */
    class Image private constructor(private val encoded: Map<ImageMime, ByteArray>) : Clip {
        /** What a paste of this client's own copy decodes again. */
        val png: ByteArray get() = encoded.getValue(ImageMime.Png)

        override val offeredTypes: List<Mime> = ImageMime.entries

        override fun bytesFor(type: Mime): Result<ByteArray, ClipboardError> {
            val bytes = encoded[type] ?: return Err(ClipboardError.NoImage)
            return Ok(bytes)
        }

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

    /** Files, sent as the RFC 2483 list every [UriListMime] carries. */
    class Uris(uris: List<String>) : Clip {
        private val encoded = encodeUriList(uris)

        override val offeredTypes: List<Mime> = UriListMime.entries

        override fun bytesFor(type: Mime): Result<ByteArray, ClipboardError> =
            if (type is UriListMime) Ok(encoded) else Err(ClipboardError.NoUris)
    }

    /**
     * An image a drag offers, encoded only once a transfer asks for it.
     *
     * `wl_data_device.start_drag` has to reach the compositor while the button that took the implicit grab is
     * still down, and encoding ahead of it is long enough for the button to come up. The send that asks for
     * these bytes runs off the loop thread already, so the encode costs nothing where it lands instead.
     */
    class DeferredImage(private val image: ImageBitmap) : Clip {
        override val offeredTypes: List<Mime> = ImageMime.entries

        override fun bytesFor(type: Mime): Result<ByteArray, ClipboardError> =
            if (type is ImageMime) encodeImage(image, type) else Err(ClipboardError.NoImage)
    }
}

/** What a drag of this offers, encoding nothing here: an image is encoded as each transfer asks for it. */
internal fun KortexDragSource.asClip(): Clip = when (this) {
    is KortexDragSource.Text -> Clip.Text(text)
    is KortexDragSource.Image -> Clip.DeferredImage(image)
    is KortexDragSource.Files -> Clip.Uris(uris)
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

/**
 * [encoded] as the transfer key it carries, with the NUL some senders terminate it with taken off.
 *
 * The key is an opaque token to everything here: only the portal that issued it knows what it names.
 */
internal fun decodePortalKey(encoded: ByteArray): String = encoded.decodeToString().trimEnd('\u0000').trim()

/**
 * [uris] as RFC 2483 writes them: one per line, each line ended by a CRLF, including the last.
 *
 * The terminator on the last line is what makes this and [decodeUriList] each other's inverse, since a
 * decode drops the empty line a trailing CRLF leaves behind.
 */
internal fun encodeUriList(uris: List<String>): ByteArray =
    uris.joinToString(separator = "") { "$it\r\n" }.encodeToByteArray()

/**
 * [encoded] as the URIs it lists, per RFC 2483: lines separated by CRLF, of which the blank ones and those
 * beginning with `#` are not URIs.
 *
 * Each URI is handed on as it arrived, percent-encoding and scheme and all, since only the application that
 * sent it knows what anything other than a `file` points at.
 */
internal fun decodeUriList(encoded: ByteArray): List<String> = encoded
    .decodeToString()
    .lineSequence()
    .map(String::trim)
    .filter { it.isNotEmpty() && !it.startsWith("#") }
    .toList()

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
