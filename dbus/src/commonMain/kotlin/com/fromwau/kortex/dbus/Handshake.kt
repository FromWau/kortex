package com.fromwau.kortex.dbus

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.getOrElse
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel

/** Authenticates as [uid] with SASL `EXTERNAL`, the text exchange a bus wants before any message. */
internal fun SocketChannel.authenticate(uid: Int): EmptyResult<DBusError> {
    // Not part of the line: the protocol opens with a zero byte, which is what carries credentials on
    // the platforms that attach them to one.
    writeAscii("\u0000").getOrElse { return Err(it) }
    writeAscii("AUTH EXTERNAL ${uid.toString().hexed()}\r\n").getOrElse { return Err(it) }

    val answer = readLine().getOrElse { return Err(it) }
    if (!answer.startsWith("OK")) return Err(DBusError.AuthenticationRejected(answer))

    // Not NEGOTIATE_UNIX_FD: kortex cannot receive a descriptor, so it must not claim it can.
    return writeAscii("BEGIN\r\n")
}

private fun SocketChannel.writeAscii(line: String): EmptyResult<DBusError> = try {
    val bytes = ByteBuffer.wrap(line.toByteArray(Charsets.US_ASCII))
    while (bytes.hasRemaining()) write(bytes)
    Ok(Unit)
} catch (failure: IOException) {
    Err(DBusError.SocketFailed(failure.message.orEmpty()))
}

/** One byte at a time, because over-reading here would eat the binary stream that follows. */
private fun SocketChannel.readLine(): Result<String, DBusError> {
    val line = StringBuilder()
    val one = ByteBuffer.allocate(1)
    while (!line.endsWith("\r\n")) {
        one.clear()
        val read = try {
            read(one)
        } catch (failure: IOException) {
            return Err(DBusError.SocketFailed(failure.message.orEmpty()))
        }
        if (read < 0) return Err(DBusError.AuthenticationRejected(line.toString()))
        line.append(one.flip().get().toInt().toChar())
    }
    return Ok(line.trim().toString())
}

/** The uid as `EXTERNAL` wants it: its decimal spelling, then that hex-encoded. */
private fun String.hexed(): String = toByteArray(Charsets.US_ASCII).joinToString(separator = "") { byte ->
    "%02x".format(byte)
}
