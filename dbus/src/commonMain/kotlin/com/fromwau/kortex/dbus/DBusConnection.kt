package com.fromwau.kortex.dbus

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.fold
import com.fromwau.kern.result.getOrElse
import com.fromwau.kern.result.map
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The bus's own name, object and interface, which every connection talks to before anything else. */
public object Bus {
    public const val NAME: String = "org.freedesktop.DBus"
    public const val PATH: String = "/org/freedesktop/DBus"
    public const val INTERFACE: String = "org.freedesktop.DBus"
    public const val PROPERTIES: String = "org.freedesktop.DBus.Properties"
}

/**
 * A connection to a message bus.
 *
 * One coroutine owns the socket and reads every message off it, so a caller of [call] waits on the serial
 * it sent rather than on the socket, and several calls can be outstanding at once.
 *
 * A signal reaches [signals] only while the bus has been told to route it, which is what [addMatch] does;
 * a rule nobody has asked for costs the bus nothing and delivers nothing.
 */
public class DBusConnection private constructor(
    private val channel: SocketChannel,
    private val replyTimeout: Duration,
) : AutoCloseable {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineName("kortex-dbus"),
    )
    private val serials = AtomicInteger(0)
    private val pending = ConcurrentHashMap<UInt, CompletableDeferred<Result<Message, DBusError>>>()
    private val writing = Mutex()
    private val received = MutableSharedFlow<Message.Signal>(
        extraBufferCapacity = SIGNAL_BUFFER,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    private var name: String? = null
    private val exported = ConcurrentHashMap<String, Exported>()

    /**
     * Why the connection ended, once it has.
     *
     * Set before any waiting call is failed with it, so nothing can observe a dead connection with no
     * reason attached.
     */
    @Volatile
    private var death: DBusError? = null

    /** The unique name the bus gave this connection, such as `:1.31`. */
    public val uniqueName: String get() = checkNotNull(name) { "the connection was used before Hello answered" }

    /**
     * Every signal the bus has routed here, whatever asked for it.
     *
     * A [SharedFlow] rather than a plain one so that a collector can act on `onSubscription`: nothing is
     * replayed, so a subscriber that reads its starting state before it is subscribed loses whatever
     * arrived in between.
     */
    public val allSignals: SharedFlow<Message.Signal> get() = received.asSharedFlow()

    /** The signals [rule] asked for, picked out of everything the bus routes to this one socket. */
    public fun signals(rule: MatchRule): Flow<Message.Signal> = received.filter(rule::matches)

    /**
     * Calls [member] and waits for the reply.
     *
     * @return the reply's body, [DBusError.CallFailed] where the peer answered with an error rather than a
     *   value, or [DBusError.ReplyTimedOut] where nothing answered inside the budget.
     */
    public suspend fun call(
        destination: String,
        path: String,
        iface: String?,
        member: String,
        args: List<DBusValue> = emptyList(),
        timeout: Duration = replyTimeout,
    ): Result<List<DBusValue>, DBusError> {
        // Asked before sending: the socket may still take the write long after anything stopped reading it,
        // and a call nobody can answer should say so rather than spend its timeout finding out.
        death?.let { return Err(it) }

        val serial = nextSerial()
        val waiting = CompletableDeferred<Result<Message, DBusError>>()
        pending[serial] = waiting

        val request = Message.Call(
            serial = serial,
            path = path,
            member = member,
            iface = iface,
            destination = destination,
            body = args,
        )

        return try {
            send(request).getOrElse { return Err(it) }
            val reply = withTimeout(timeout) { waiting.await() }.getOrElse { return Err(it) }
            when (reply) {
                is Message.Return -> Ok(reply.body)
                is Message.Failure -> Err(DBusError.CallFailed(reply.name, reply.body.firstOrNull()?.asText))
                // Nothing else is ever put into a pending reply, so this is unreachable rather than a case.
                is Message.Call, is Message.Signal -> Err(DBusError.Disconnected)
            }
        } catch (_: TimeoutCancellationException) {
            Err(DBusError.ReplyTimedOut)
        } finally {
            pending.remove(serial)
        }
    }

    /**
     * Answers calls made to [path] with [handler], so this connection can be called as well as call.
     *
     * `org.freedesktop.DBus.Peer` is answered for every path without reaching [handler], since it is about
     * the connection rather than the object. `Introspectable.Introspect` is answered from [introspection]
     * where one is given, and only for this exact path: a caller walking down from the root sees nothing,
     * which costs the plumbing of intermediate nodes and buys a peer that already knows the path nothing.
     */
    public fun export(path: String, introspection: String? = null, handler: ObjectHandler) {
        exported[path] = Exported(introspection, handler)
    }

    /** Stops answering calls to [path]; one already being handled still finishes. */
    public fun unexport(path: String) {
        exported.remove(path)
    }

    /** Broadcasts a signal from an object this connection exports. */
    public suspend fun emit(
        path: String,
        iface: String,
        member: String,
        args: List<DBusValue> = emptyList(),
    ): EmptyResult<DBusError> = send(
        Message.Signal(
            serial = nextSerial(),
            path = path,
            iface = iface,
            member = member,
            body = args,
        ),
    )

    /** Sends [member] without waiting, for a call whose reply carries nothing worth having. */
    public suspend fun post(
        destination: String,
        path: String,
        iface: String?,
        member: String,
        args: List<DBusValue> = emptyList(),
    ): EmptyResult<DBusError> = send(
        Message.Call(
            serial = nextSerial(),
            path = path,
            member = member,
            iface = iface,
            destination = destination,
            body = args,
            expectsReply = false,
        ),
    )

    /** Asks the bus to start routing the signals [rule] describes. */
    public suspend fun addMatch(rule: MatchRule): EmptyResult<DBusError> = bus("AddMatch", rule).map { }

    /** Asks the bus to stop, which a caller owes for every [addMatch] once nobody is watching. */
    public suspend fun removeMatch(rule: MatchRule): EmptyResult<DBusError> = bus("RemoveMatch", rule).map { }

    /**
     * Takes [name] exclusively, so that peers can address this connection by it.
     *
     * @return the outcome the bus reported, which is [NameRequest.Taken] where somebody already holds the
     *   name. It never queues: a shell that started, looks well, and answers nothing until a daemon nobody
     *   is watching happens to exit is the harder of the two to diagnose.
     */
    public suspend fun requestName(name: String): Result<NameRequest, DBusError> = call(
        destination = Bus.NAME,
        path = Bus.PATH,
        iface = Bus.INTERFACE,
        member = "RequestName",
        args = listOf(DBusValue.Text(name), DBusValue.U32(NameRequest.DO_NOT_QUEUE)),
    ).map { body -> NameRequest.of((body.firstOrNull() as? DBusValue.U32)?.value) }

    /** Gives [name] back, so another connection can take it. */
    public suspend fun releaseName(name: String): EmptyResult<DBusError> = call(
        destination = Bus.NAME,
        path = Bus.PATH,
        iface = Bus.INTERFACE,
        member = "ReleaseName",
        args = listOf(DBusValue.Text(name)),
    ).map { }

    /** Who owns [name] right now, or [DBusError.CallFailed] where nobody does. */
    public suspend fun nameOwner(name: String): Result<String, DBusError> = call(
        destination = Bus.NAME,
        path = Bus.PATH,
        iface = Bus.INTERFACE,
        member = "GetNameOwner",
        args = listOf(DBusValue.Text(name)),
    ).map { body -> body.firstOrNull()?.asText.orEmpty() }

    /** Reads one property, already unwrapped from the variant `Get` answers in. */
    public suspend fun property(
        destination: String,
        path: String,
        iface: String,
        name: String,
    ): Result<DBusValue, DBusError> = call(
        destination = destination,
        path = path,
        iface = Bus.PROPERTIES,
        member = "Get",
        args = listOf(DBusValue.Text(iface), DBusValue.Text(name)),
    ).map { body -> body.single().unwrapped }

    /** Every property of [iface] at once, which is one round trip instead of one per property. */
    public suspend fun properties(
        destination: String,
        path: String,
        iface: String,
    ): Result<Map<String, DBusValue>, DBusError> = call(
        destination = destination,
        path = path,
        iface = Bus.PROPERTIES,
        member = "GetAll",
        args = listOf(DBusValue.Text(iface)),
    ).map { body -> body.singleOrNull()?.asDictionary.orEmpty() }

    override fun close() {
        // The channel first: a blocking read does not notice a cancelled coroutine, and closing the socket
        // is what makes it return so the pump can end.
        runCatching { channel.close() }
        finish(DBusError.Disconnected)
        scope.cancel()
    }

    private suspend fun bus(member: String, rule: MatchRule): Result<List<DBusValue>, DBusError> = call(
        destination = Bus.NAME,
        path = Bus.PATH,
        iface = Bus.INTERFACE,
        member = member,
        args = listOf(DBusValue.Text(rule.asExpression)),
    )

    private fun nextSerial(): UInt = serials.incrementAndGet().toUInt()

    private suspend fun send(message: Message): EmptyResult<DBusError> {
        val raw = message.encode().getOrElse { return Err(it) }
        return writing.withLock {
            withContext(Dispatchers.IO) {
                try {
                    val bytes = ByteBuffer.wrap(raw)
                    while (bytes.hasRemaining()) channel.write(bytes)
                    Ok(Unit)
                } catch (_: ClosedChannelException) {
                    Err(death ?: DBusError.Disconnected)
                } catch (failure: IOException) {
                    Err(DBusError.SocketFailed(failure.message.orEmpty()))
                }
            }
        }
    }

    private fun pump() {
        scope.launch {
            try {
                while (true) {
                    val message = readMessage().getOrElse { error ->
                        finish(error)
                        return@launch
                    }
                    deliver(message)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                // Errors included. Whatever ends this coroutine, nothing will read the socket again, and a
                // connection that is deaf without being dead fails no waiter and refuses no new call.
                finish(DBusError.ReaderFailed(failure.toString()))
            }
        }
    }

    private suspend fun deliver(message: Message) {
        when (message) {
            is Message.Return -> pending[message.replySerial]?.complete(Ok(message))
            is Message.Failure -> pending[message.replySerial]?.complete(Ok(message))
            is Message.Signal -> received.emit(message)
            // Answered off the pump: a handler that takes its time would otherwise stop the socket being
            // read, and replies are matched by serial, so answering out of order is what the bus expects.
            is Message.Call -> scope.launch { answer(message) }
        }
    }

    private suspend fun answer(call: Message.Call) {
        val outcome = standardAnswer(call) ?: exported[call.path]
            ?.handler
            ?.handle(call)
            ?: Err(CallRejected(CallRejected.UNKNOWN_OBJECT, "nothing is exported at ${call.path}"))

        // A caller that said it wants no reply gets none, not even an error: the flag is a promise not to
        // wait, and answering anyway leaves a message nobody will ever match to a serial.
        if (!call.expectsReply) return

        val reply = outcome.fold(
            { body ->
                Message.Return(
                    serial = nextSerial(),
                    replySerial = call.serial,
                    destination = call.sender,
                    body = body,
                )
            },
            { rejected ->
                Message.Failure(
                    serial = nextSerial(),
                    replySerial = call.serial,
                    name = rejected.name,
                    destination = call.sender,
                    body = listOf(DBusValue.Text(rejected.message)),
                )
            },
        )
        send(reply)
    }

    /**
     * The members every connection answers, whatever it exports.
     *
     * Null where the call is not one of them, which is the signal to hand it to the object's own handler.
     */
    private fun standardAnswer(call: Message.Call): Result<List<DBusValue>, CallRejected>? = when {
        call.iface == PEER && call.member == "Ping" -> Ok(emptyList())
        call.iface == PEER && call.member == "GetMachineId" -> machineId()
        call.iface == INTROSPECTABLE && call.member == "Introspect" -> introspect(call.path)
        else -> null
    }

    private fun introspect(path: String): Result<List<DBusValue>, CallRejected>? = exported[path]
        ?.introspection
        ?.let { xml -> Ok(listOf(DBusValue.Text(xml))) }

    private fun machineId(): Result<List<DBusValue>, CallRejected> = try {
        Ok(listOf(DBusValue.Text(Files.readString(MACHINE_ID).trim())))
    } catch (failure: IOException) {
        Err(CallRejected(CallRejected.UNKNOWN_METHOD, "this machine has no id to give: ${failure.message}"))
    }

    /** Fails everything still waiting, with the reason the connection ended rather than a stand-in. */
    private fun finish(error: DBusError) {
        val cause = death ?: error.also { death = it }
        pending.values.forEach { waiting -> waiting.complete(Err(cause)) }
        pending.clear()
    }

    private fun readMessage(): Result<Message, DBusError> {
        val header = readFully(FIXED_HEADER_LENGTH).getOrElse { return Err(it) }
        val length = lengthOf(header).getOrElse { return Err(it) }
        val rest = readFully(length - FIXED_HEADER_LENGTH).getOrElse { return Err(it) }
        return Message.decode(header + rest)
    }

    private fun readFully(count: Int): Result<ByteArray, DBusError> {
        val bytes = ByteBuffer.allocate(count)
        while (bytes.hasRemaining()) {
            val read = try {
                channel.read(bytes)
            } catch (_: ClosedChannelException) {
                return Err(DBusError.Disconnected)
            } catch (failure: IOException) {
                return Err(DBusError.SocketFailed(failure.message.orEmpty()))
            }
            if (read < 0) return Err(DBusError.Disconnected)
        }
        return Ok(bytes.array())
    }

    public companion object {
        /**
         * How many signals may be in flight before the socket stops being read.
         *
         * Signals are not dropped when this fills: a subscriber's job is to move one into its own flow and
         * nothing else, so a subscriber slow enough to fill this is stalling the connection and should say
         * so by stalling rather than by quietly losing a status change.
         */
        private const val SIGNAL_BUFFER = 256

        private const val PEER: String = "org.freedesktop.DBus.Peer"
        private const val INTROSPECTABLE: String = "org.freedesktop.DBus.Introspectable"

        /** Where the id every peer reports through `GetMachineId` lives. */
        private val MACHINE_ID: Path = Path.of("/etc/machine-id")

        /** What dbus-daemon itself waits for a reply, so kortex does not give up before the bus would. */
        private val DEFAULT_REPLY_TIMEOUT = 25.seconds

        /** Opens the session bus, authenticates, and learns this connection's own name. */
        public suspend fun session(
            replyTimeout: Duration = DEFAULT_REPLY_TIMEOUT,
        ): Result<DBusConnection, DBusError> {
            val address = BusAddress.fromEnvironment().getOrElse { return Err(it) }
            return open(address.path, replyTimeout)
        }

        /** The same, against a socket named outright, which is how a test points it at one of its own. */
        public suspend fun open(
            path: String,
            replyTimeout: Duration = DEFAULT_REPLY_TIMEOUT,
        ): Result<DBusConnection, DBusError> = withContext(Dispatchers.IO) {
            val uid = currentUid().getOrElse { return@withContext Err(it) }
            val channel = try {
                SocketChannel.open(StandardProtocolFamily.UNIX).also {
                    it.connect(UnixDomainSocketAddress.of(path))
                }
            } catch (failure: IOException) {
                return@withContext Err(DBusError.SocketFailed(failure.message.orEmpty()))
            }

            val connection = DBusConnection(channel, replyTimeout)
            connection.handshake(uid)
                .getOrElse {
                    channel.close()
                    return@withContext Err(it)
                }
            connection.pump()
            connection.hello().getOrElse {
                connection.close()
                return@withContext Err(it)
            }
            Ok(connection)
        }
    }

    private fun handshake(uid: Int): EmptyResult<DBusError> {
        // Not part of the line: the protocol opens with a zero byte, which is what carries credentials on
        // the platforms that attach them to one.
        writeAscii("\u0000").getOrElse { return Err(it) }
        writeAscii("AUTH EXTERNAL ${uid.toString().hexed()}\r\n").getOrElse { return Err(it) }

        val answer = readLine().getOrElse { return Err(it) }
        if (!answer.startsWith("OK")) return Err(DBusError.AuthenticationRejected(answer))

        // Not NEGOTIATE_UNIX_FD: kortex cannot receive a descriptor, so it must not claim it can.
        return writeAscii("BEGIN\r\n")
    }

    private suspend fun hello(): EmptyResult<DBusError> = call(
        destination = Bus.NAME,
        path = Bus.PATH,
        iface = Bus.INTERFACE,
        member = "Hello",
    ).map { body -> name = body.firstOrNull()?.asText }

    private fun writeAscii(line: String): EmptyResult<DBusError> = try {
        val bytes = ByteBuffer.wrap(line.toByteArray(Charsets.US_ASCII))
        while (bytes.hasRemaining()) channel.write(bytes)
        Ok(Unit)
    } catch (failure: IOException) {
        Err(DBusError.SocketFailed(failure.message.orEmpty()))
    }

    /** One byte at a time, because over-reading here would eat the binary stream that follows. */
    private fun readLine(): Result<String, DBusError> {
        val line = StringBuilder()
        val one = ByteBuffer.allocate(1)
        while (!line.endsWith("\r\n")) {
            one.clear()
            val read = try {
                channel.read(one)
            } catch (failure: IOException) {
                return Err(DBusError.SocketFailed(failure.message.orEmpty()))
            }
            if (read < 0) return Err(DBusError.AuthenticationRejected(line.toString()))
            line.append(one.flip().get().toInt().toChar())
        }
        return Ok(line.trim().toString())
    }
}

/** What the bus answered a `RequestName` with. */
public enum class NameRequest {
    /** The name is this connection's now. */
    Held,

    /** This connection already held it, so nothing changed. */
    AlreadyHeld,

    /** Somebody else holds it, and kortex asked not to be queued behind them. */
    Taken,

    /** The bus answered a code it does not define. */
    Unknown,
    ;

    internal companion object {
        /** Ask for the name and give up at once rather than waiting behind whoever holds it. */
        const val DO_NOT_QUEUE: UInt = 4u

        private const val PRIMARY_OWNER = 1u
        private const val EXISTS = 3u
        private const val ALREADY_OWNER = 4u

        fun of(code: UInt?): NameRequest = when (code) {
            PRIMARY_OWNER -> Held
            ALREADY_OWNER -> AlreadyHeld
            EXISTS -> Taken
            else -> Unknown
        }
    }
}

/** The uid as `EXTERNAL` wants it: its decimal spelling, then that hex-encoded. */
private fun String.hexed(): String = toByteArray(Charsets.US_ASCII).joinToString(separator = "") { byte ->
    "%02x".format(byte)
}
