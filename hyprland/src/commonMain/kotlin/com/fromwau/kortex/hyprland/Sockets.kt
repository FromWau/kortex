package com.fromwau.kortex.hyprland

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.getOrElse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runInterruptible
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path

/** What the event socket says: that it is now listening, or that an event of [Event.name] happened. */
internal sealed interface Tick {
    data object Connected : Tick

    data class Event(val name: String) : Tick
}

/**
 * Sends [command] down the request socket at [path] and reads the whole answer.
 *
 * One connection per request, closed as soon as the answer is read: Hyprland serves this socket
 * synchronously, and a connection left open stalls the compositor until its own timeout.
 */
internal suspend fun request(
    path: String,
    command: String,
): Result<String, HyprlandError> {
    val channel = connect(path).getOrElse { return Err(it) }
    return channel.use {
        attempt(path) {
            channel.write(ByteBuffer.wrap(command.encodeToByteArray()))
            channel.shutdownOutput()
            val answer = ByteArrayOutputStream()
            val buffer = ByteBuffer.allocate(BUFFER_SIZE)
            while (channel.read(buffer.clear()) >= 0) answer.write(buffer.array(), 0, buffer.position())
            answer.toString(Charsets.UTF_8)
        }
    }
}

/**
 * The event socket at [path], as a [Tick.Connected] followed by one [Tick.Event] per line.
 *
 * Ends with an error: [HyprlandError.Disconnected] once Hyprland closes a socket that was live.
 */
internal fun events(path: String): Flow<Result<Tick, HyprlandError>> = flow {
    val channel = connect(path).getOrElse {
        emit(Err(it))
        return@flow
    }
    channel.use {
        emit(Ok(Tick.Connected))
        val lines = Lines()
        val buffer = ByteBuffer.allocate(BUFFER_SIZE)
        while (true) {
            val read = attempt(path) { channel.read(buffer.clear()) }.getOrElse {
                emit(Err(it))
                return@flow
            }
            if (read < 0) {
                emit(Err(HyprlandError.Disconnected))
                return@flow
            }
            lines.add(buffer.array(), read).forEach { line -> emit(Ok(Tick.Event(eventName(line)))) }
        }
    }
}.flowOn(Dispatchers.IO)

/** The event's name, which is everything before `>>`: the data after it is never read, see [Hyprland]. */
internal fun eventName(line: String): String = line.substringBefore(EVENT_SEPARATOR)

/** Whole lines out of a byte stream that splits them wherever a read happens to end. */
internal class Lines {
    private val partial = ByteArrayOutputStream()

    fun add(
        bytes: ByteArray,
        count: Int,
    ): List<String> {
        val complete = mutableListOf<String>()
        for (index in 0 until count) {
            val byte = bytes[index]
            if (byte == NEWLINE) {
                complete += partial.toString(Charsets.UTF_8)
                partial.reset()
            } else {
                partial.write(byte.toInt())
            }
        }
        return complete
    }
}

private suspend fun connect(path: String): Result<SocketChannel, HyprlandError> {
    if (!Files.exists(Path.of(path))) return Err(HyprlandError.NoSocket(path))
    return attempt(path) {
        SocketChannel.open(StandardProtocolFamily.UNIX).apply {
            try {
                connect(UnixDomainSocketAddress.of(path))
            } catch (failure: IOException) {
                close()
                throw failure
            }
        }
    }
}

/** Runs blocking socket work so that cancelling the caller closes the socket rather than waiting on it. */
private suspend fun <T> attempt(
    path: String,
    work: () -> T,
): Result<T, HyprlandError> = try {
    Ok(runInterruptible(Dispatchers.IO) { work() })
} catch (failure: IOException) {
    // An interrupt from cancellation closes the channel and surfaces here as an IOException.
    currentCoroutineContext().ensureActive()
    Err(HyprlandError.Unreachable(path, failure.message ?: failure.javaClass.simpleName))
}

private const val BUFFER_SIZE = 8192
private const val NEWLINE = '\n'.code.toByte()
private const val EVENT_SEPARATOR = ">>"
