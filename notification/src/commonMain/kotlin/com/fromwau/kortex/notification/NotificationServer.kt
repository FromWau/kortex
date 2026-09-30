package com.fromwau.kortex.notification

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
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
import com.fromwau.kortex.dbus.asUInt32
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * what answers it. Only one connection on a bus can hold that name, so becoming the server is something
 * that succeeds or fails outright, which is why [start] is the only way to get one of these.
 *
 * Nothing here draws or times anything. A notification carries the [Expiry] its application asked for and
 * stays in [notifications] until somebody calls [close]: a caller that animates one away knows when it is
 * gone and kortex does not, so kortex does not guess.
 */
public class NotificationServer private constructor(
    private val connection: DBusConnection,
    private val information: ServerInformation,
) {
    private val posted = MutableStateFlow<List<Notification>>(emptyList())
    private val ids = AtomicInteger(0)
    private val posting = Mutex()

    /**
     * Every notification currently posted, oldest first.
     *
     * No `Result` around it, which is the one place this module departs from the other providers. The
     * error those carry answers "why is there no data", and here that cannot be a failure: holding one of
     * these means [start] already proved the name was taken and the object exported, so an empty list can
     * only mean nothing has been posted.
     */
    public val notifications: StateFlow<List<Notification>> = posted.asStateFlow()

    /**
     * Takes a notification away and tells its application why.
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

    /** Gives the name back and stops answering, so another daemon can take over. */
    public suspend fun stop(): EmptyResult<NotificationError> {
        connection.unexport(PATH)
        return connection.releaseName(INTERFACE).mapError(NotificationError::BusFailed)
    }

    private suspend fun announce(member: String, args: List<DBusValue>): EmptyResult<NotificationError> =
        connection.emit(PATH, INTERFACE, member, args).mapError(NotificationError::BusFailed)

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

        val existing = replaces.takeIf { it != NO_ID && posted.value.any { posted -> posted.id == it } }
        val id = existing ?: ids.incrementAndGet().toUInt()
        val notification = notificationFrom(id, body)
            ?: return@withLock Err(CallRejected(CallRejected.INVALID_ARGS, "Notify takes $NOTIFY_SIGNATURE"))

        posted.update { current ->
            // In place when it replaces one, so a notification keeps its position rather than jumping to
            // the end of whatever a caller is drawing.
            if (existing == null) current + notification
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

        /**
         * Becomes the notification server, or says who already is.
         *
         * It does not queue. `RequestName` goes out asking not to be, because a shell that has started,
         * looks well, and shows no notification until a daemon nobody is watching happens to exit is the
         * harder of the two to diagnose.
         */
        public suspend fun start(
            connection: DBusConnection,
            information: ServerInformation,
        ): Result<NotificationServer, NotificationError> {
            val requested = connection
                .requestName(INTERFACE)
                .mapError(NotificationError::BusFailed)
                .getOrElse { return Err(it) }
            when (requested) {
                NameRequest.Held, NameRequest.AlreadyHeld -> Unit
                NameRequest.Taken, NameRequest.Unknown -> return Err(whoHasIt(connection))
            }

            val server = NotificationServer(connection, information)
            connection.export(PATH, introspection = INTROSPECTION) { call -> server.handle(call) }
            return Ok(server)
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
