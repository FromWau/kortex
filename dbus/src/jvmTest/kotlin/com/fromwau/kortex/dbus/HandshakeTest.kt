package com.fromwau.kortex.dbus

import com.fromwau.kern.result.Err
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/** The login a bus wants before any message, against a socket that answers as the test says. */
class HandshakeTest {
    @Test
    fun `a bus that refuses the login says so with its own words`() = runBlocking<Unit> {
        val outcome = withTimeout(5.seconds) { openAgainst("REJECTED EXTERNAL\r\n") }

        assertEquals(Err(DBusError.AuthenticationRejected("REJECTED EXTERNAL")), outcome)
    }

    @Test
    fun `a bus that hangs up mid-login is a refusal too`() = runBlocking<Unit> {
        val outcome = withTimeout(5.seconds) { openAgainst("") }

        assertEquals(Err(DBusError.AuthenticationRejected("")), outcome)
    }

    /** Opens a connection to a socket that reads the `AUTH` line, answers [answer] and hangs up. */
    private suspend fun openAgainst(answer: String) = withContext(Dispatchers.IO) {
        val dir = Files.createTempDirectory("kortex-handshake")
        val path = dir.resolve("bus")
        try {
            ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { server ->
                server.bind(UnixDomainSocketAddress.of(path))
                val bus = async {
                    server.accept().use { client ->
                        val login = ByteBuffer.allocate(LOGIN_BYTES)
                        while (!String(login.array(), 0, login.position(), Charsets.US_ASCII).endsWith("\r\n")) {
                            if (client.read(login) < 0) break
                        }
                        client.write(ByteBuffer.wrap(answer.toByteArray(Charsets.US_ASCII)))
                    }
                }
                DBusConnection.open(path.toString()).also { bus.await() }
            }
        } finally {
            path.deleteIfExists()
            dir.deleteIfExists()
        }
    }

    private companion object {
        /** Room for the zero byte and the `AUTH EXTERNAL` line, which is all a client sends first. */
        const val LOGIN_BYTES = 256
    }
}
