package com.fromwau.kortex.hyprland

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.flatMap
import com.fromwau.kern.result.getOrElse
import com.fromwau.kern.result.map
import com.fromwau.kern.result.mapError
import com.fromwau.kortex.socket.SocketError
import com.fromwau.kortex.socket.UnixSocket
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/** What the event socket says: that it is now listening, or that an event of [Event.name] happened. */
internal sealed interface Tick {
    data object Connected : Tick

    /** [data] is everything after `>>`, whose fields are joined by commas a name or title may also hold. */
    data class Event(
        val name: String,
        val data: String,
    ) : Tick
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
    val socket = UnixSocket.connect(path).getOrElse { return Err(it.asHyprlandError(path)) }
    return socket.use {
        socket
            .write(command.encodeToByteArray())
            .flatMap { socket.finishWriting() }
            .flatMap { socket.readToEnd() }
            .map { answer -> answer.decodeToString() }
            .mapError { it.asHyprlandError(path) }
    }
}

/**
 * The event socket at [path], as a [Tick.Connected] followed by one [Tick.Event] per line.
 *
 * Ends with an error: [HyprlandError.Disconnected] once Hyprland closes a socket that was live.
 */
internal fun events(path: String): Flow<Result<Tick, HyprlandError>> = flow {
    val socket = UnixSocket.connect(path).getOrElse {
        emit(Err(it.asHyprlandError(path)))
        return@flow
    }
    socket.use {
        emit(Ok(Tick.Connected))
        emitAll(
            socket.lines().map { line ->
                line
                    .map { Tick.Event(eventName(it), eventData(it)) }
                    .mapError { it.asHyprlandError(path) }
            },
        )
    }
}

/** The event's name, which is everything before `>>`. */
internal fun eventName(line: String): String = line.substringBefore(EVENT_SEPARATOR)

/** The event's data, which is everything after `>>`. */
internal fun eventData(line: String): String = line.substringAfter(EVENT_SEPARATOR, "")

private fun SocketError.asHyprlandError(path: String): HyprlandError = when (this) {
    is SocketError.NotFound -> HyprlandError.NoSocket(path)
    SocketError.Closed -> HyprlandError.Disconnected
    is SocketError.Failed -> HyprlandError.Unreachable(path, detail)
}

private const val EVENT_SEPARATOR = ">>"
