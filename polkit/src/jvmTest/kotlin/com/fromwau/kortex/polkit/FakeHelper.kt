package com.fromwau.kortex.polkit

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.BufferedReader
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path

/**
 * polkit's agent helper as a test plays it: a socket the agent connects to, with the test speaking for PAM.
 *
 * The real one runs PAM as root and reports to the real polkitd, so nothing but a live run can use it.
 */
class FakeHelper : AutoCloseable {
    private val folder: Path = Files.createTempDirectory("kortex-helper")

    val path: String = folder.resolve("agent-helper.socket").toString()

    private val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX).apply {
        bind(UnixDomainSocketAddress.of(path))
    }

    /** The next attempt the agent opens. */
    suspend fun accept(): Attempt = runInterruptible(Dispatchers.IO) { Attempt(server.accept()) }

    override fun close() {
        server.close()
        folder.toFile().deleteRecursively()
    }

    class Attempt(private val channel: SocketChannel) : AutoCloseable {
        private val reader: BufferedReader = Channels.newInputStream(channel).bufferedReader()
        private val writer = Channels.newOutputStream(channel)

        /** The next line the agent wrote, or null once it has hung up. */
        suspend fun readLine(): String? = runInterruptible(Dispatchers.IO) { reader.readLine() }

        /** Sends [line] as the helper would, already escaped. */
        suspend fun send(line: String) = runInterruptible(Dispatchers.IO) {
            writer.write("$line\n".encodeToByteArray())
            writer.flush()
        }

        override fun close() {
            channel.close()
        }
    }
}
