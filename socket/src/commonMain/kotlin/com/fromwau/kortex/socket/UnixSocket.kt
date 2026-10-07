package com.fromwau.kortex.socket

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.getOrElse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path

/**
 * A connected Unix domain socket, read and written as bytes, lines or the whole stream.
 *
 * ```kotlin
 * UnixSocket.connect(path).getOrElse { return Err(it) }.use { socket ->
 *     socket.write("status\n".encodeToByteArray())
 *     socket.readLine()
 * }
 * ```
 *
 * One read may run at a time, and one write, but a read and a write may run at once.
 *
 * Cancelling a read closes the socket, since a read cut short leaves the stream at a place nobody knows.
 * A write always runs to its end and only then gives way to a cancellation, so nothing is ever sent half.
 */
public class UnixSocket private constructor(private val channel: SocketChannel) : AutoCloseable {
    /** Bytes read off the socket that no read has handed out yet, kept ready to be read from. */
    private val unread: ByteBuffer = ByteBuffer.allocate(BUFFER_SIZE).flip()

    public suspend fun write(bytes: ByteArray): EmptyResult<SocketError> = withContext(Dispatchers.IO) {
        failures {
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
        }
    }

    /** Says nothing more will be written, which is how some peers learn a request is whole. */
    public suspend fun finishWriting(): EmptyResult<SocketError> = withContext(Dispatchers.IO) {
        failures { channel.shutdownOutput() }
    }

    /** Exactly [count] bytes, or [SocketError.Closed] where the other end hangs up first. */
    public suspend fun readExactly(count: Int): Result<ByteArray, SocketError> = reading {
        val bytes = ByteArray(count)
        val taken = minOf(count, unread.remaining())
        unread.get(bytes, 0, taken)
        val rest = ByteBuffer.wrap(bytes, taken, count - taken)
        while (rest.hasRemaining()) {
            if (channel.read(rest) < 0) return@reading Err(SocketError.Closed)
        }
        Ok(bytes)
    }

    /**
     * The next line as UTF-8, without its [terminator], or [SocketError.Closed] where the other end hangs up
     * before one ends.
     *
     * Whatever arrives after the line stays here for the next read, so a protocol that turns from lines to
     * bytes can call [readExactly] next and miss nothing.
     */
    public suspend fun readLine(terminator: String = "\n"): Result<String, SocketError> {
        val end = terminator.encodeToByteArray()
        return reading { nextLine(end) }
    }

    /** Everything until the other end stops writing. */
    public suspend fun readToEnd(): Result<ByteArray, SocketError> = reading {
        val all = ByteArrayOutputStream()
        do {
            all.write(unread.array(), unread.arrayOffset() + unread.position(), unread.remaining())
            unread.position(unread.limit())
        } while (fill())
        Ok(all.toByteArray())
    }

    /** Every line until the other end hangs up, which ends the flow with [SocketError.Closed]. */
    public fun lines(terminator: String = "\n"): Flow<Result<String, SocketError>> = flow {
        while (true) {
            val line = readLine(terminator).getOrElse {
                emit(Err(it))
                return@flow
            }
            emit(Ok(line))
        }
    }

    /** Closes the socket, which also ends a read waiting on it with [SocketError.Closed]. */
    override fun close() {
        runCatching { channel.close() }
    }

    private fun nextLine(end: ByteArray): Result<String, SocketError> {
        val line = ByteArrayOutputStream()
        var last: Byte? = null
        // Compared in full only on the terminator's last byte, rather than copying the line out per byte.
        while (last != end.last() || !line.endsWith(end)) {
            if (!unread.hasRemaining() && !fill()) return Err(SocketError.Closed)
            last = unread.get()
            line.write(last.toInt())
        }
        return Ok(String(line.toByteArray(), 0, line.size() - end.size, Charsets.UTF_8))
    }

    /** Reads more into [unread], and says false where the other end has hung up. */
    private fun fill(): Boolean {
        unread.compact()
        try {
            return channel.read(unread) >= 0
        } finally {
            unread.flip()
        }
    }

    private suspend fun <T> reading(work: () -> Result<T, SocketError>): Result<T, SocketError> = try {
        runInterruptible(Dispatchers.IO) { work() }
    } catch (failure: IOException) {
        // A cancelled read is interrupted, which closes the channel and arrives here as an IOException.
        currentCoroutineContext().ensureActive()
        Err(failure.asSocketError())
    }

    public companion object {
        /**
         * Connects to the socket at [path].
         *
         * @return [SocketError.NotFound] where there is no socket file at [path], and [SocketError.Failed]
         *   where there is one but nothing accepts on it, as is left behind when its server dies.
         */
        public suspend fun connect(path: String): Result<UnixSocket, SocketError> {
            if (!Files.exists(Path.of(path))) return Err(SocketError.NotFound(path))
            return try {
                val channel = runInterruptible(Dispatchers.IO) {
                    SocketChannel.open(StandardProtocolFamily.UNIX).apply {
                        try {
                            connect(UnixDomainSocketAddress.of(path))
                        } catch (failure: IOException) {
                            close()
                            throw failure
                        }
                    }
                }
                Ok(UnixSocket(channel))
            } catch (failure: IOException) {
                currentCoroutineContext().ensureActive()
                Err(failure.asSocketError())
            }
        }
    }
}

private const val BUFFER_SIZE = 8192

private inline fun failures(work: () -> Unit): EmptyResult<SocketError> = try {
    work()
    Ok(Unit)
} catch (failure: IOException) {
    Err(failure.asSocketError())
}

private fun IOException.asSocketError(): SocketError = when (this) {
    is ClosedChannelException -> SocketError.Closed
    else -> SocketError.Failed(message ?: javaClass.simpleName)
}

private fun ByteArrayOutputStream.endsWith(end: ByteArray): Boolean {
    if (size() < end.size) return false
    val bytes = toByteArray()
    return end.indices.all { bytes[bytes.size - end.size + it] == end[it] }
}
