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
    private val seat: Seat,
    private val manager: MemorySegment,
    private val device: DataDevice,
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
     * @return [ClipboardError.NoInputSerial] until a surface of the shell has had an input event.
     */
    suspend fun setText(text: String): EmptyResult<ClipboardError> = withContext(loop) { offerSelection(text) }

    /**
     * The selection, asked for under the first [TextMime] it is offered as, and read as UTF-8.
     *
     * @return the text, or the [ClipboardError] saying why there is none.
     */
    suspend fun readText(): Result<String, ClipboardError> {
        // Neither hop may be cancelled: each hands an fd on, and a hop cancelled after its work ran would drop it.
        val readFd = withContext(loop + NonCancellable) { receiveSelection() }.getOrElse { return Err(it) }
        return withContext(Dispatchers.IO + NonCancellable) {
            try {
                readPipeToEnd(readFd, READ_TIMEOUT_MILLIS)
            } finally {
                LibC.close(readFd)
            }
        }.map { it.decodeToString() }
    }

    private fun offerSelection(text: String): EmptyResult<ClipboardError> {
        check(!closed) { "setText on a clipboard already given back" }
        val serial = inputSerial ?: return Err(ClipboardError.NoInputSerial)
        val offered = DataSource.create(manager, text)
        device.setSelection(offered, serial)
        display.flush()
        // After set_selection, not before: destroying the selection's own source would clear the selection meanwhile.
        source?.destroy()
        source = offered
        return Ok(Unit)
    }

    private fun receiveSelection(): Result<Int, ClipboardError> {
        check(!closed) { "readText on a clipboard already given back" }
        val offer = device.selection ?: return Err(ClipboardError.NoSelection)
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
        device.release()
        // wl_data_device_manager has no destructor below v4, so its proxy is only ever freed on this side.
        LibWayland.proxyDestroy(manager)
        // Last, since the device was taken for it.
        seat.release()
    }

    companion object {
        /** Binds the manager and a seat of the clipboard's own, and takes that seat's data device. */
        fun bind(display: WaylandDisplay, loop: CoroutineDispatcher): Result<WaylandClipboard, KortexError> {
            val manager = display
                .require("wl_data_device_manager", LibWayland.dataDeviceManagerInterface, WlVersion.DATA_DEVICE_MANAGER)
                .getOrElse { return Err(it) }
            val seat = Seat.bind(display).getOrElse { failure ->
                LibWayland.proxyDestroy(manager)
                return Err(failure)
            }
            return Ok(WaylandClipboard(display, loop, seat, manager, DataDevice.create(manager, seat)))
        }

        private const val READ_TIMEOUT_MILLIS = 1000L
    }
}

/** Why [WaylandClipboard] could not set or read the selection. */
internal sealed interface ClipboardError : IError {
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
 * Reads [fd] until its writer closes it.
 *
 * @return everything read, or [ClipboardError.ReadTimedOut] once [timeoutMillis] have passed before the writer
 *   closed its end, however much it was still sending.
 */
internal fun readPipeToEnd(fd: Int, timeoutMillis: Long): Result<ByteArray, ClipboardError> {
    val deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLI
    val read = ByteArrayOutputStream()
    Arena.ofConfined().use { arena ->
        val chunk = arena.allocate(PIPE_CHUNK_BYTES)
        while (System.nanoTime() < deadline) {
            // Polled first: a read blocks for as long as the writer keeps its end open.
            if (LibC.poll(intArrayOf(fd), intArrayOf(LibC.POLLIN), deadline).single() == 0) continue
            val count = LibC.read(fd, chunk)
            when {
                count == 0L -> return Ok(read.toByteArray())
                count < 0L -> return Err(ClipboardError.PipeFailed)
                else -> read.write(chunk.asSlice(0L, count).toArray(JAVA_BYTE))
            }
        }
    }
    return Err(ClipboardError.ReadTimedOut)
}

/** Writes all of [bytes] into [fd] and closes it; a reader that goes away first only cuts the write short. */
internal fun writePipeAndClose(fd: Int, bytes: ByteArray) {
    try {
        Arena.ofConfined().use { arena ->
            val buffer: MemorySegment = arena.allocateFrom(JAVA_BYTE, *bytes)
            var offset = 0L
            while (offset < buffer.byteSize()) {
                val written = LibC.write(fd, buffer.asSlice(offset))
                if (written <= 0L) return
                offset += written
            }
        }
    } finally {
        LibC.close(fd)
    }
}

private const val NANOS_PER_MILLI = 1_000_000L
private const val PIPE_CHUNK_BYTES = 65_536L
