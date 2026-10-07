package com.fromwau.kortex.socket

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertSuccess
import com.fromwau.kern.result.errorOrNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** [UnixSocket] against a listening socket of the test's own, which writes exactly what each test says. */
class UnixSocketTest {
    private val dir: Path = Files.createTempDirectory("kortex-socket")
    private val path: Path = dir.resolve("peer")
    private val server: ServerSocketChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX).apply {
        bind(UnixDomainSocketAddress.of(path))
    }

    private val opened = mutableListOf<UnixSocket>()

    /** Where a read that may never return runs, so a test that fails cannot wait on it forever. */
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @AfterTest
    fun tearDown() {
        background.cancel()
        opened.forEach { it.close() }
        server.close()
        path.deleteIfExists()
        dir.deleteIfExists()
    }

    @Test
    fun `no socket file says which path it looked at`() = runBlocking<Unit> {
        val missing = dir.resolve("absent").toString()

        assertEquals(Err(SocketError.NotFound(missing)), UnixSocket.connect(missing))
    }

    @Test
    fun `a socket file nobody listens on any more is a failure, not a missing socket`() = runBlocking<Unit> {
        server.close()

        assertIs<SocketError.Failed>(UnixSocket.connect(path.toString()).errorOrNull())
    }

    @Test
    fun `a line split across two reads comes out whole`() = runBlocking<Unit> {
        val (socket, peer) = connected()

        peer.send("workspa")
        val line = async { socket.readLine() }
        delay(SEPARATE_READS)
        peer.send("ce>>2\nnext")

        assertEquals(Ok("workspace>>2"), line.await())
    }

    @Test
    fun `a character split across two reads is not mangled`() = runBlocking<Unit> {
        val (socket, peer) = connected()
        val bytes = "title>>a,◐ x\n".encodeToByteArray()
        val cut = bytes.indexOfFirst { it == 0xE2.toByte() } + 1

        peer.send(bytes.copyOfRange(0, cut))
        val line = async { socket.readLine() }
        delay(SEPARATE_READS)
        peer.send(bytes.copyOfRange(cut, bytes.size))

        assertEquals(Ok("title>>a,◐ x"), line.await())
    }

    @Test
    fun `what arrives after a line is kept for the next read`() = runBlocking<Unit> {
        val (socket, peer) = connected()

        peer.send("OK 1234\r\n".encodeToByteArray() + byteArrayOf(1, 2, 3))

        assertEquals(Ok("OK 1234"), socket.readLine(terminator = "\r\n"))
        assertContentEquals(byteArrayOf(1, 2, 3), withTimeout(SETTLE) { socket.readExactly(3) }.assertSuccess())
    }

    @Test
    fun `a peer that hangs up before the bytes asked for is a closed socket`() = runBlocking<Unit> {
        val (socket, peer) = connected()

        peer.send(byteArrayOf(1, 2))
        peer.close()

        assertEquals(Err(SocketError.Closed), socket.readExactly(3))
    }

    @Test
    fun `a request is written whole, and the answer read to its end`() = runBlocking<Unit> {
        val (socket, peer) = connected()
        val heard = async(Dispatchers.IO) { peer.readToEnd() }

        socket.write("j/monitors".encodeToByteArray()).assertSuccess()
        socket.finishWriting().assertSuccess()
        assertEquals("j/monitors", heard.await())
        peer.send("[]")
        peer.close()

        assertEquals("[]", socket.readToEnd().assertSuccess().decodeToString())
    }

    @Test
    fun `lines go on until the peer hangs up, and then say so`() = runBlocking<Unit> {
        val (socket, peer) = connected()

        peer.send("a\nb\n")
        peer.close()

        assertEquals(listOf(Ok("a"), Ok("b"), Err(SocketError.Closed)), socket.lines().toList())
    }

    @Test
    fun `closing the socket ends a read that is waiting on it`() = runBlocking<Unit> {
        val (socket, _) = connected()
        val waiting = CompletableDeferred<Unit>()

        val line = async {
            waiting.complete(Unit)
            socket.readLine()
        }
        waiting.await()
        delay(SEPARATE_READS)
        socket.close()

        assertEquals(Err(SocketError.Closed), withTimeout(SETTLE) { line.await() })
    }

    @Test
    fun `cancelling a read closes the socket, so no later read starts mid-stream`() = runBlocking<Unit> {
        val (socket, _) = connected()

        val reader = background.launch { socket.readLine() }
        delay(SEPARATE_READS)
        withTimeout(SETTLE) { reader.cancelAndJoin() }

        assertEquals(Err(SocketError.Closed), withTimeout(SETTLE) { socket.readLine() })
    }

    /** A socket connected to this test's listener, and the listener's end of it. */
    private suspend fun connected(): Pair<UnixSocket, Peer> {
        val accepted = runBlocking(Dispatchers.IO) {
            val peer = async { server.accept() }
            UnixSocket.connect(path.toString()).assertSuccess().also { opened += it } to peer.await()
        }
        return accepted.first to Peer(accepted.second)
    }

    private class Peer(private val channel: SocketChannel) {
        fun send(text: String) = send(text.encodeToByteArray())

        fun send(bytes: ByteArray) {
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
        }

        fun readToEnd(): String {
            val all = ByteArrayOutputStream()
            val buffer = ByteBuffer.allocate(256)
            while (channel.read(buffer.clear()) >= 0) all.write(buffer.array(), 0, buffer.position())
            return all.toString(Charsets.UTF_8)
        }

        fun close() = channel.close()
    }

    private companion object {
        /** Long enough that what the peer sends next lands in a read of its own. */
        val SEPARATE_READS = 100.milliseconds
        val SETTLE = 5.seconds
    }
}
