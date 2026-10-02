package com.fromwau.kortex.hyprland

import java.io.ByteArrayOutputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.io.path.deleteIfExists
import kotlin.io.path.deleteRecursively
import kotlin.io.path.ExperimentalPathApi

/**
 * Both of Hyprland's sockets in a temporary folder, answering from [answers] and sending what [emit] is given.
 *
 * It speaks only the framing, which is small enough to state here: a request is whatever arrives before the
 * client stops writing, the answer is written back and the connection closed, and an event is one line.
 * What the answers say is recorded from a real Hyprland in [Recorded].
 */
internal class FakeHyprland : AutoCloseable {
    val folder: Path = Files.createTempDirectory("kortex-hyprland")
    val instance = HyprlandInstance(folder.toString())

    /** What each request is answered with; one missing here is answered the way Hyprland does. */
    val answers = ConcurrentHashMap<String, String>()

    /** Every request received, in order. */
    val requests = CopyOnWriteArrayList<String>()

    @Volatile
    private var held: CountDownLatch? = null

    private val listeners = CopyOnWriteArrayList<SocketChannel>()
    private val requestServer = bind(instance.requests)
    private var eventServer: ServerSocketChannel? = null

    init {
        thread(isDaemon = true, name = "fake-hyprland-requests") { serveRequests() }
        listen()
    }

    /** How many clients are on the event socket now. */
    val listening: Int get() = listeners.size

    /** Sends each of [lines] to every client on the event socket. */
    fun emit(vararg lines: String) {
        val bytes = lines.joinToString("") { "$it\n" }.encodeToByteArray()
        listeners.forEach { client -> client.write(ByteBuffer.wrap(bytes)) }
    }

    /** Holds every answer back until [release], so events can pile up while a read is outstanding. */
    fun hold() {
        held = CountDownLatch(1)
    }

    fun release() {
        held?.countDown()
        held = null
    }

    /** Closes every event client, the way Hyprland does when it goes. The socket itself stays. */
    fun dropListeners() {
        listeners.forEach { it.close() }
        listeners.clear()
    }

    /** Takes the event socket away altogether, file and all. */
    private fun stopListening() {
        dropListeners()
        eventServer?.close()
        Path.of(instance.events).deleteIfExists()
    }

    private fun listen() {
        val server = bind(instance.events)
        eventServer = server
        thread(isDaemon = true, name = "fake-hyprland-events") {
            runCatching { while (true) listeners += server.accept() }
        }
    }

    fun requestsOf(command: String): Int = requests.count { it == command }

    @OptIn(ExperimentalPathApi::class)
    override fun close() {
        release()
        stopListening()
        requestServer.close()
        folder.deleteRecursively()
    }

    private fun serveRequests() {
        runCatching {
            while (true) {
                val client = requestServer.accept()
                thread(isDaemon = true) { answer(client) }
            }
        }
    }

    private fun answer(client: SocketChannel) = client.use {
        val request = ByteArrayOutputStream()
        val buffer = ByteBuffer.allocate(1024)
        while (client.read(buffer.clear()) >= 0) request.write(buffer.array(), 0, buffer.position())
        val command = request.toString(Charsets.UTF_8)
        requests += command
        held?.await()
        client.write(ByteBuffer.wrap((answers[command] ?: "unknown request").encodeToByteArray()))
    }

    private fun bind(path: String): ServerSocketChannel =
        ServerSocketChannel.open(StandardProtocolFamily.UNIX).apply { bind(UnixDomainSocketAddress.of(path)) }
}
