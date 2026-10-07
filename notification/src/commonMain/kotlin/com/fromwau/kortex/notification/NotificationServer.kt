package com.fromwau.kortex.notification

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.errorOrNull
import com.fromwau.kern.result.flatMap
import com.fromwau.kern.result.getOrElse
import com.fromwau.kern.result.getOrNull
import com.fromwau.kern.result.mapError
import com.fromwau.kortex.dbus.Bus
import com.fromwau.kortex.dbus.CallRejected
import com.fromwau.kortex.dbus.DBusConnection
import com.fromwau.kortex.dbus.DBusType
import com.fromwau.kortex.dbus.DBusValue
import com.fromwau.kortex.dbus.Message
import com.fromwau.kortex.dbus.NameRequest
import com.fromwau.kortex.dbus.SessionBus
import com.fromwau.kortex.dbus.asUInt32
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

/**
 * What kortex tells an application about itself through `GetServerInformation`.
 *
 * [capabilities] is the caller's to declare and not kortex's to guess: whether body markup, action icons
 * or inline images can be drawn depends on the content drawing them, and only the caller knows that.
 */
public data class ServerInformation(
    public val name: String,
    public val vendor: String,
    public val version: String,
    public val capabilities: List<String> = emptyList(),
)

/**
 * Kortex as the notification server, rather than a client of one.
 *
 * `org.freedesktop.Notifications` is what `notify-send` calls, and a shell that shows notifications is
 * what answers it. Only one connection on a bus can hold that name. While somebody collects
 * [notifications] this takes it on every connection [bus] comes up on: at once where it is free, and
 * otherwise as soon as its holder lets it go. The name goes back when the last collector leaves.
 *
 * Nothing here draws or times anything. A notification carries the [Expiry] its application asked for and
 * stays in [notifications] until somebody calls [close]: a caller that animates one away knows when it is
 * gone and kortex does not, so kortex does not guess. Posted notifications outlive a bus restart, and so
 * do their ids, so an application that comes back can still close what it posted.
 */
public class NotificationServer(
    private val bus: SessionBus,
    private val information: ServerInformation,
    scope: CoroutineScope,
) {
    private val posted = MutableStateFlow<List<Notification>>(emptyList())
    private val ids = AtomicInteger(0)
    private val posting = Mutex()

    /** The connection holding the name right now, which is the one signals about a notification go out on. */
    private val holding = MutableStateFlow<DBusConnection?>(null)

    /**
     * Every notification currently posted, oldest first, while this is the server.
     *
     * Otherwise why not: [NotificationError.AlreadyServed] while another process holds the name,
     * [NotificationError.BusDown] while there is no bus, and [NotificationError.NotConnected] before the
     * first connection is up.
     */
    public val notifications: StateFlow<Result<List<Notification>, NotificationError>> = bus
        .following(::unavailable) { connection -> servingOn(connection) }
        .stateIn(scope, SharingStarted.WhileSubscribed(), Err(NotificationError.NotConnected))

    /**
     * Takes a notification away and tells its application why.
     *
     * It is taken away even when the application cannot be told, which is what the error then says.
     *
     * @return [NotificationError.NoSuchNotification] where nothing with that id is posted, which is what a
     *   caller closing the same one twice gets.
     */
    public suspend fun close(id: UInt, reason: CloseReason): EmptyResult<NotificationError> {
        if (!remove(id)) return Err(NotificationError.NoSuchNotification(id))
        return announce("NotificationClosed", listOf(DBusValue.U32(id), DBusValue.U32(reason.wireValue)))
    }

    /**
     * Tells the application the user chose one of its actions.
     *
     * Unless the notification asked to be [Notification.isResident], it is closed as [CloseReason.Dismissed]
     * afterwards, which is what the specification means by the server removing it once an action is invoked.
     */
    public suspend fun invoke(id: UInt, action: String): EmptyResult<NotificationError> {
        val notification = posted.value.firstOrNull { it.id == id }
            ?: return Err(NotificationError.NoSuchNotification(id))

        announce("ActionInvoked", listOf(DBusValue.U32(id), DBusValue.Text(action)))
            .getOrElse { return Err(it) }
        if (notification.isResident) return Ok(Unit)
        return close(id, CloseReason.Dismissed)
    }

    /**
     * Holds the name on [connection] for as long as this pass runs, and the posted notifications once it
     * does. Once held, this stops watching for it, and the name is given back when the pass ends.
     */
    private fun servingOn(connection: DBusConnection): Flow<Result<List<Notification>, NotificationError>> =
        channelFlow {
            connection.chancesToClaim(INTERFACE)
                .map { chance -> chance.mapError(NotificationError::BusFailed).flatMap { claim(connection) } }
                .onEach { claimed -> if (claimed is Err) send(claimed) }
                .firstOrNull { it is Ok }
                ?: return@channelFlow

            connection.export(PATH, introspection = INTROSPECTION) { call -> handle(call) }
            holding.value = connection
            posted.collect { send(Ok(it)) }
        }.onCompletion {
            if (holding.compareAndSet(connection, null)) {
                withContext(NonCancellable) {
                    connection.unexport(PATH)
                    connection.releaseName(INTERFACE)
                }
            }
        }

    private suspend fun announce(member: String, args: List<DBusValue>): EmptyResult<NotificationError> {
        val connection = holding.value
            ?: return Err(notifications.value.errorOrNull() ?: NotificationError.NotConnected)
        return connection.emit(PATH, INTERFACE, member, args).mapError(NotificationError::BusFailed)
    }
    private fun remove(id: UInt): Boolean {
        var removed = false
        posted.update { current ->
            removed = current.any { it.id == id }
            current.filterNot { it.id == id }
        }
        return removed
    }

    private suspend fun handle(call: Message.Call): Result<List<DBusValue>, CallRejected> = when (call.member) {
        "Notify" -> notify(call.body)
        "CloseNotification" -> withdraw(call.body)
        "GetCapabilities" -> Ok(
            listOf(DBusValue.Sequence(DBusType.Basic.Text, information.capabilities.map(DBusValue::Text))),
        )

        "GetServerInformation" -> Ok(
            listOf(
                DBusValue.Text(information.name),
                DBusValue.Text(information.vendor),
                DBusValue.Text(information.version),
                DBusValue.Text(SPEC_VERSION),
            ),
        )

        else -> Err(CallRejected.unknownMethod(call))
    }

    /**
     * One `Notify`, answered with the id the application should use to refer to it.
     *
     * Under a lock because deciding the id and putting the notification in the list have to happen
     * together: two applications posting at once must not be handed the same id, and a replacement must
     * land on the notification it was told to replace rather than on one that arrived in between.
     */
    private suspend fun notify(body: List<DBusValue>): Result<List<DBusValue>, CallRejected> = posting.withLock {
        val replaces = body.getOrNull(1)?.asUInt32
            ?: return@withLock Err(CallRejected(CallRejected.INVALID_ARGS, "Notify takes a u as its second argument"))

        val replaced = replaces
            .takeIf { it != NO_ID }
            ?.let { asked -> posted.value.firstOrNull { it.id == asked } }
        val id = replaced?.id ?: ids.incrementAndGet().toUInt()
        val notification = notificationFrom(id, body, revision = replaced?.revision?.plus(1u) ?: FIRST_REVISION)
            ?: return@withLock Err(CallRejected(CallRejected.INVALID_ARGS, "Notify takes $NOTIFY_SIGNATURE"))

        posted.update { current ->
            // In place when it replaces one, so a notification keeps its position rather than jumping to
            // the end of whatever a caller is drawing.
            if (replaced == null) current + notification
            else current.map { if (it.id == id) notification else it }
        }
        Ok(listOf(DBusValue.U32(id)))
    }

    private suspend fun withdraw(body: List<DBusValue>): Result<List<DBusValue>, CallRejected> {
        val id = body.firstOrNull()?.asUInt32
            ?: return Err(CallRejected(CallRejected.INVALID_ARGS, "CloseNotification takes a u"))

        // Not an error to the application when it is already gone: it asked for the notification to be
        // closed and it is, which is all CloseNotification promises.
        close(id, CloseReason.Withdrawn)
        return Ok(emptyList())
    }

    public companion object {
        /** The well-known name only one connection on a bus may hold. */
        public const val INTERFACE: String = "org.freedesktop.Notifications"

        public const val PATH: String = "/org/freedesktop/Notifications"

        /** What this implements, and what `GetServerInformation` reports as its last field. */
        public const val SPEC_VERSION: String = "1.2"

        private const val NO_ID: UInt = 0u
        private const val NOTIFY_SIGNATURE = "susssasa{sv}i"

        /** The name for [connection], or who holds it instead. */
        private suspend fun claim(connection: DBusConnection): EmptyResult<NotificationError> {
            val requested = connection
                .requestName(INTERFACE)
                .mapError(NotificationError::BusFailed)
                .getOrElse { return Err(it) }
            return when (requested) {
                NameRequest.Held, NameRequest.AlreadyHeld -> Ok(Unit)
                NameRequest.Taken, NameRequest.Unknown -> Err(whoHasIt(connection))
            }
        }

        /**
         * Turns "the name is taken" into "dunst is already running".
         *
         * Two more round trips and one file read, on a path that has already failed, for the difference
         * between a diagnosable message and a riddle.
         */
        private suspend fun whoHasIt(connection: DBusConnection): NotificationError.AlreadyServed {
            val owner = connection.nameOwner(INTERFACE).getOrNull().orEmpty()
            val pid = connection
                .call(
                    destination = Bus.NAME,
                    path = Bus.PATH,
                    iface = Bus.INTERFACE,
                    member = "GetConnectionUnixProcessID",
                    args = listOf(DBusValue.Text(INTERFACE)),
                )
                .getOrNull()
                ?.let { body -> body.firstOrNull()?.asUInt32?.toInt() }

            return NotificationError.AlreadyServed(owner, pid, pid?.let(::processName))
        }

        /** What `/proc/<pid>/comm` calls it, which is the whole point of having looked up the pid. */
        private fun processName(pid: Int): String? = try {
            Files.readString(Path.of("/proc/$pid/comm")).trim().ifEmpty { null }
        } catch (_: IOException) {
            null
        }

        private val INTROSPECTION = """
            <node>
              <interface name="$INTERFACE">
                <method name="Notify">
                  <arg direction="in" type="s" name="app_name"/>
                  <arg direction="in" type="u" name="replaces_id"/>
                  <arg direction="in" type="s" name="app_icon"/>
                  <arg direction="in" type="s" name="summary"/>
                  <arg direction="in" type="s" name="body"/>
                  <arg direction="in" type="as" name="actions"/>
                  <arg direction="in" type="a{sv}" name="hints"/>
                  <arg direction="in" type="i" name="expire_timeout"/>
                  <arg direction="out" type="u" name="id"/>
                </method>
                <method name="CloseNotification">
                  <arg direction="in" type="u" name="id"/>
                </method>
                <method name="GetCapabilities">
                  <arg direction="out" type="as" name="capabilities"/>
                </method>
                <method name="GetServerInformation">
                  <arg direction="out" type="s" name="name"/>
                  <arg direction="out" type="s" name="vendor"/>
                  <arg direction="out" type="s" name="version"/>
                  <arg direction="out" type="s" name="spec_version"/>
                </method>
                <signal name="NotificationClosed">
                  <arg type="u" name="id"/>
                  <arg type="u" name="reason"/>
                </signal>
                <signal name="ActionInvoked">
                  <arg type="u" name="id"/>
                  <arg type="s" name="action_key"/>
                </signal>
              </interface>
            </node>
        """.trimIndent()
    }
}
