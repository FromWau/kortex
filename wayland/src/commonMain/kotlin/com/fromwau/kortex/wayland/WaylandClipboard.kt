package com.fromwau.kortex.wayland

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.IError
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.getOrElse
import com.fromwau.kern.result.map
import java.io.ByteArrayOutputStream
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.JAVA_BYTE
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * The shell's clipboard: makes a text the selection, and reads the selection as text.
 *
 * Any thread may call it. Its requests run on [loop], which dispatches onto the thread that owns the connection,
 * and a read waits for its text on [Dispatchers.IO]: the text may come from this very client, whose loop has to
 * stay free to send it.
 */
internal class WaylandClipboard private constructor(
    private val display: WaylandDisplay,
    private val loop: CoroutineDispatcher,
    // Null when the compositor offers no clipboard.
    private val bound: BoundDevice?,
) : AutoCloseable {
    // Each of these is the loop thread's alone.
    private var inputSerial: Int? = null
    private var source: DataSource? = null
    private var closed = false

    /** Keeps [serial], of a key, a keyboard enter or a button, for the next [setText] to quote. */
    fun recordInputSerial(serial: Int) {
        inputSerial = serial
    }

    /**
     * Makes [text] the selection, offered under every [TextMime].
     *
     * @return [ClipboardError.NoInputSerial] until a surface of the shell has had an input event, or
     *   [ClipboardError.NoClipboard] when the compositor offers none.
     */
    suspend fun setText(text: String): EmptyResult<ClipboardError> = withContext(loop) { offerSelection(text) }

    /**
     * The selection, asked for under the first [TextMime] it is offered as, and read as UTF-8.
     *
     * Not cancellable: a cancelled caller sees its cancellation only once this returns its [Result], at most
     * [TRANSFER_TIMEOUT_MILLIS] later, at its own next suspension point.
     *
     * @return the text, or the [ClipboardError] saying why there is none.
     */
    suspend fun readText(): Result<String, ClipboardError> =
        readPipeOpenedOn(loop, TRANSFER_TIMEOUT_MILLIS) { receiveSelection() }.map { it.decodeToString() }

    private fun offerSelection(text: String): EmptyResult<ClipboardError> {
        check(!closed) { "setText on a clipboard already given back" }
        val bound = bound ?: return Err(ClipboardError.NoClipboard)
        val serial = inputSerial ?: return Err(ClipboardError.NoInputSerial)
        val offered = DataSource.create(bound.manager, text)
        bound.device.setSelection(offered, serial)
        display.flush()
        // After set_selection, not before: destroying the selection's own source would clear the selection meanwhile.
        source?.destroy()
        source = offered
        return Ok(Unit)
    }

    private fun receiveSelection(): Result<Int, ClipboardError> {
        check(!closed) { "readText on a clipboard already given back" }
        val bound = bound ?: return Err(ClipboardError.NoClipboard)
        val offer = bound.device.selection ?: return Err(ClipboardError.NoSelection)
        val type = offer.preferredText ?: return Err(ClipboardError.NoText)
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
            // wl_data_device_manager has no destructor below v4, so its proxy is only ever freed on this side.
            LibWayland.proxyDestroy(manager)
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
                LibWayland.proxyDestroy(manager)
                return Err(failure)
            }
            return Ok(WaylandClipboard(display, loop, BoundDevice(manager, seat, DataDevice.create(manager, seat))))
        }

        private const val DATA_DEVICE_MANAGER = "wl_data_device_manager"
    }
}

/** Why [WaylandClipboard] could not set or read the selection. */
internal sealed interface ClipboardError : IError {
    /** The compositor offers no clipboard: it never announced `wl_data_device_manager`. */
    data object NoClipboard : ClipboardError

    /** Nothing is selected, or this client has not been told what is: only keyboard focus brings that. */
    data object NoSelection : ClipboardError

    /** The selection is offered under no [TextMime]. */
    data object NoText : ClipboardError

    /** No surface has had an input event yet, and setting the selection quotes one's serial. */
    data object NoInputSerial : ClipboardError

    /** The pipe the text travels through could not be made, or failed while it was read. */
    data object PipeFailed : ClipboardError

    /** The selection's owner had not finished writing it when the read's timeout ran out. */
    data object ReadTimedOut : ClipboardError

    /** The selection passed [MAX_SELECTION_BYTES] before its writer closed its end. */
    data object TooLarge : ClipboardError
}

/**
 * The types a copy offers its text under and a paste asks for, as `wl_data_source.offer` and
 * `wl_data_offer.offer` name them, declared in the order a paste prefers them.
 */
internal enum class TextMime(val wireName: String) {
    TextPlainUtf8("text/plain;charset=utf-8"),
    TextPlain("text/plain"),
    Utf8StringAtom("UTF8_STRING"),
    StringAtom("STRING"),
    TextAtom("TEXT"),
    ;

    companion object {
        /** The entry named [wireName], or null for a type that is not text. */
        fun fromWireNameOrNull(wireName: String): TextMime? = entries.firstOrNull { it.wireName == wireName }
    }
}

/**
 * Opens a pipe's read end with [open] on [loop], then reads from it on [Dispatchers.IO] until its writer closes it,
 * [timeoutMillis] passes, or it grows past [MAX_SELECTION_BYTES], and closes it.
 *
 * Not cancellable: a cancelled caller gets its cancellation once this returns, at most [timeoutMillis] after [loop]
 * ran [open].
 */
internal suspend fun readPipeOpenedOn(
    loop: CoroutineDispatcher,
    timeoutMillis: Long,
    open: () -> Result<Int, ClipboardError>,
): Result<ByteArray, ClipboardError> = withContext(NonCancellable) {
    // Around both hops: a dispatcher-changing hop discards its result, fd and all, on resuming a cancelled caller.
    val readFd = withContext(loop) { open() }.getOrElse { return@withContext Err(it) }
    withContext(Dispatchers.IO) {
        try {
            readPipeToEnd(readFd, timeoutMillis)
        } finally {
            LibC.close(readFd)
        }
    }
}

/**
 * Reads [fd] until its writer closes it.
 *
 * @return everything read, [ClipboardError.TooLarge] once more than [MAX_SELECTION_BYTES] has arrived, or
 *   [ClipboardError.ReadTimedOut] once [timeoutMillis] have passed before the writer closed its end, however much
 *   it was still sending.
 */
internal fun readPipeToEnd(fd: Int, timeoutMillis: Long): Result<ByteArray, ClipboardError> {
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
            if (read.size() > MAX_SELECTION_BYTES) return Err(ClipboardError.TooLarge)
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

/** The most a read keeps of one selection before giving up as [ClipboardError.TooLarge]. */
internal const val MAX_SELECTION_BYTES = 16L * 1024 * 1024

private const val NANOS_PER_MILLI = 1_000_000L
private const val READ_CHUNK_BYTES = 65_536L

// A pipe polls writable while a page of it is free, so a write no larger than a page never blocks.
private const val WRITE_CHUNK_BYTES = 4_096L
