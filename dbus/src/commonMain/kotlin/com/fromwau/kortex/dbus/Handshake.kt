package com.fromwau.kortex.dbus

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.getOrElse
import com.fromwau.kern.result.mapError
import com.fromwau.kortex.socket.SocketError
import com.fromwau.kortex.socket.UnixSocket

/** Authenticates as [uid] with SASL `EXTERNAL`, the text exchange a bus wants before any message. */
internal suspend fun UnixSocket.authenticate(uid: Int): EmptyResult<DBusError> {
    // Not part of the line: the protocol opens with a zero byte, which is what carries credentials on
    // the platforms that attach them to one.
    writeAscii("\u0000").getOrElse { return Err(it) }
    writeAscii("AUTH EXTERNAL ${uid.toString().hexed()}\r\n").getOrElse { return Err(it) }

    val answer = readLine(terminator = "\r\n").getOrElse { failure ->
        return Err(
            when (failure) {
                // Hanging up instead of answering is a refusal, whatever the bus had started to say.
                SocketError.Closed -> DBusError.AuthenticationRejected("")
                else -> failure.asDBusError()
            },
        )
    }
    if (!answer.startsWith("OK")) return Err(DBusError.AuthenticationRejected(answer))

    // Not NEGOTIATE_UNIX_FD: kortex cannot receive a descriptor, so it must not claim it can.
    return writeAscii("BEGIN\r\n")
}

private suspend fun UnixSocket.writeAscii(line: String): EmptyResult<DBusError> =
    write(line.toByteArray(Charsets.US_ASCII)).mapError { it.asDBusError() }

/** The uid as `EXTERNAL` wants it: its decimal spelling, then that hex-encoded. */
private fun String.hexed(): String = toByteArray(Charsets.US_ASCII).joinToString(separator = "") { byte ->
    "%02x".format(byte)
}
