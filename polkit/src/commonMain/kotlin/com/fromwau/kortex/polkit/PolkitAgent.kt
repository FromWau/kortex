package com.fromwau.kortex.polkit

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.flatMap
import com.fromwau.kern.result.fold
import com.fromwau.kern.result.getOrElse
import com.fromwau.kern.result.getOrNull
import com.fromwau.kern.result.map
import com.fromwau.kern.result.mapError
import com.fromwau.kortex.auth.AuthError
import com.fromwau.kortex.auth.AuthState
import com.fromwau.kortex.dbus.CallRejected
import com.fromwau.kortex.dbus.DBusConnection
import com.fromwau.kortex.dbus.DBusError
import com.fromwau.kortex.dbus.DBusType
import com.fromwau.kortex.dbus.DBusValue
import com.fromwau.kortex.dbus.Message
import com.fromwau.kortex.dbus.SystemBus
import com.fromwau.kortex.dbus.asDictionary
import com.fromwau.kortex.dbus.asFields
import com.fromwau.kortex.dbus.asItems
import com.fromwau.kortex.dbus.asText
import com.fromwau.kortex.dbus.asUInt32
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * This session's polkit agent: the one that asks for a password when something needs one.
 *
 * polkitd never takes a password itself. When an action needs one it asks the agent registered for the
 * session the request came from, and the agent has the user answer polkit's own helper, which checks the
 * password as root and tells polkitd how it went.
 *
 * ```kotlin
 * PolkitAgent.serve(SystemBus(scope), scope).collect { served ->
 *     served.getOrNull()?.firstOrNull()?.let { request -> showPrompt(request) }
 * }
 * ```
 */
public object PolkitAgent {
    /** Where polkit's helper listens, which systemd starts as root for each connection. */
    public const val HELPER_SOCKET: String = "/run/polkit/agent-helper.socket"

    /**
     * Registers as the agent for this user's graphical session on every connection [bus] comes up on, and
     * holds the requests polkitd sends while it is.
     *
     * The value is every request not yet over, oldest first. Each one leaves the list once its conversation
     * ends, and polkitd is told how it went.
     *
     * The agent serves while the flow is collected. Once nobody collects it, every open request is
     * cancelled and the agent unregisters, so another one can take the session.
     *
     * @param helper where polkit's helper listens; tests point it at a helper of their own.
     */
    public fun serve(
        bus: SystemBus,
        scope: CoroutineScope,
        helper: String = HELPER_SOCKET,
    ): StateFlow<Result<List<PolkitRequest>, PolkitError>> = bus
        .following(::unavailable) { connection -> servingOn(connection, helper) }
        .stateIn(scope, SharingStarted.WhileSubscribed(), Err(PolkitError.NotConnected))

    private fun servingOn(
        connection: DBusConnection,
        helper: String,
    ): Flow<Result<List<PolkitRequest>, PolkitError>> = channelFlow {
        val session = displaySession(connection).getOrElse { failure ->
            send(Err(failure))
            return@channelFlow
        }
        val agent = Agent(connection, helper, scope = this)
        var registered = false
        // Exported before registering, so polkitd never calls an agent that is not there yet.
        connection.export(AGENT_PATH, introspection = INTROSPECTION) { call -> agent.handle(call) }
        try {
            register(connection, session).getOrElse { failure ->
                send(Err(failure))
                return@channelFlow
            }
            registered = true
            agent.requests.collect { requests -> send(Ok(requests)) }
        } finally {
            withContext(NonCancellable) {
                agent.cancelAll()
                if (registered) unregister(connection, session)
                connection.unexport(AGENT_PATH)
            }
        }
    }

    /** The id logind gives this user's graphical session, which is the one polkitd will call this agent for. */
    private suspend fun displaySession(connection: DBusConnection): Result<String, PolkitError> = connection
        .property(LOGIND, LOGIND_SELF, LOGIND_USER, "Display")
        .mapError(PolkitError::BusFailed)
        .flatMap { display ->
            val id = display.asFields?.firstOrNull()?.asText
            if (id.isNullOrEmpty()) Err(PolkitError.NoDisplaySession) else Ok(id)
        }

    private suspend fun register(
        connection: DBusConnection,
        session: String,
    ): EmptyResult<PolkitError> = connection
        .call(
            destination = POLKIT,
            path = AUTHORITY_PATH,
            iface = AUTHORITY,
            member = "RegisterAuthenticationAgent",
            args = listOf(subject(session), DBusValue.Text(locale()), DBusValue.Text(AGENT_PATH)),
        )
        .map { }
        .mapError { failure ->
            when (failure) {
                is DBusError.CallFailed -> PolkitError.Refused(failure.name, failure.message)
                else -> PolkitError.BusFailed(failure)
            }
        }

    /** Best effort: on a connection that is going away, polkitd forgets the agent anyway. */
    private suspend fun unregister(
        connection: DBusConnection,
        session: String,
    ) {
        connection.call(
            destination = POLKIT,
            path = AUTHORITY_PATH,
            iface = AUTHORITY,
            member = "UnregisterAuthenticationAgent",
            args = listOf(subject(session), DBusValue.Text(AGENT_PATH)),
        )
    }

    private fun subject(session: String): DBusValue = DBusValue.Struct(
        listOf(
            DBusValue.Text("unix-session"),
            DBusValue.Sequence(
                DBusType.Pair(DBusType.Basic.Text, DBusType.Variant),
                listOf(DBusValue.Pair(DBusValue.Text("session-id"), DBusValue.Variant(DBusValue.Text(session)))),
            ),
        ),
    )

    /** The language polkitd words its messages in, as the locale variables name it. */
    private fun locale(): String = System.getenv("LC_ALL")?.ifEmpty { null }
        ?: System.getenv("LC_MESSAGES")?.ifEmpty { null }
        ?: System.getenv("LANG")?.ifEmpty { null }
        ?: "C"

    /** The agent object on one connection, and the requests it is holding. */
    private class Agent(
        private val connection: DBusConnection,
        private val helper: String,
        private val scope: CoroutineScope,
    ) {
        val requests = MutableStateFlow<List<PolkitRequest>>(emptyList())

        suspend fun handle(call: Message.Call): Result<List<DBusValue>, CallRejected> {
            // Anyone on the system bus can call this object, and only polkitd may make it ask for a password.
            val polkitd = connection.nameOwner(POLKIT).getOrNull()
            if (polkitd == null || call.sender != polkitd) {
                return Err(CallRejected(ACCESS_DENIED, "only polkitd may call an authentication agent"))
            }
            return when {
                call.iface == AGENT && call.member == "BeginAuthentication" -> begin(call)
                call.iface == AGENT && call.member == "CancelAuthentication" -> cancel(call)
                else -> Err(CallRejected.unknownMethod(call))
            }
        }

        suspend fun cancelAll() {
            requests.value.forEach { request -> request.conversation.cancel() }
        }

        /** Holds a request until its conversation ends, which is when polkitd expects the reply. */
        private suspend fun begin(call: Message.Call): Result<List<DBusValue>, CallRejected> {
            val body = call.body
            val action = body.getOrNull(0)?.asText
            val message = body.getOrNull(1)?.asText
            val icon = body.getOrNull(2)?.asText
            val details = body.getOrNull(3)?.asDictionary
            val cookie = body.getOrNull(4)?.asText
            val identities = body.getOrNull(5)?.asItems
            if (action == null || message == null || icon == null || details == null || cookie == null ||
                identities == null
            ) {
                return Err(CallRejected(CallRejected.INVALID_ARGS, "BeginAuthentication wants (sssa{ss}sa(sa{sv}))"))
            }

            val user = chooseUser(identities.mapNotNull(::uidOf))
                ?: return Err(CallRejected(FAILED, "no identity polkitd offered is a user this agent can name"))
            val conversation = HelperConversation(helper, user, cookie, scope)
            val request = PolkitRequest(
                action = action,
                message = message,
                icon = icon.ifEmpty { null },
                details = details.mapValues { (_, value) -> value.asText.orEmpty() },
                user = user,
                conversation = conversation,
                cookie = cookie,
            )
            requests.update { it + request }
            conversation.start()

            val ended = conversation.state.filterIsInstance<AuthState.Ended>().first()
            requests.update { it - request }
            return ended.outcome.fold({ Ok(emptyList()) }, { failure -> Err(failure.asRejection()) })
        }

        private suspend fun cancel(call: Message.Call): Result<List<DBusValue>, CallRejected> {
            val cookie = call.body.firstOrNull()?.asText
                ?: return Err(CallRejected(CallRejected.INVALID_ARGS, "CancelAuthentication wants a cookie"))
            requests.value.filter { it.cookie == cookie }.forEach { request -> request.conversation.cancel() }
            return Ok(emptyList())
        }
    }

    /** The uid of a `unix-user` identity; polkitd has already expanded groups into their users. */
    private fun uidOf(identity: DBusValue): UInt? {
        val fields = identity.asFields ?: return null
        if (fields.getOrNull(0)?.asText != "unix-user") return null
        return fields.getOrNull(1)?.asDictionary?.get("uid")?.asUInt32
    }

    /** This process's own user where polkitd offers it, and otherwise the first it offers that has a name. */
    private suspend fun chooseUser(uids: List<UInt>): String? {
        val own = ownUid()
        val ordered = uids.filter { it == own } + uids.filter { it != own }
        return ordered.firstNotNullOfOrNull { uid -> userName(uid) }
    }

    private fun ownUid(): UInt? = try {
        (Files.getAttribute(Path.of("/proc/self"), "unix:uid") as Int).toUInt()
    } catch (_: IOException) {
        null
    }

    /** Asked of `getent` rather than read from `/etc/passwd`, so users from systemd-homed or LDAP have names too. */
    private suspend fun userName(uid: UInt): String? = withContext(Dispatchers.IO) {
        try {
            val lookup = ProcessBuilder("getent", "passwd", uid.toString()).start()
            val entry = lookup.inputReader().use { it.readLine() }
            lookup.waitFor()
            entry?.substringBefore(':')?.ifEmpty { null }
        } catch (_: IOException) {
            null
        }
    }

    private fun AuthError.asRejection(): CallRejected = when (this) {
        AuthError.Cancelled -> CallRejected(CANCELLED, "the user dismissed the authentication")
        is AuthError.Unreachable -> CallRejected(FAILED, "polkit's helper could not be reached: $cause")
        is AuthError.Broken -> CallRejected(FAILED, "polkit's helper sent a line it should not: $received")
    }

    private const val AGENT_PATH = "/com/fromwau/kortex/PolkitAgent"
    private const val AGENT = "org.freedesktop.PolicyKit1.AuthenticationAgent"
    private const val POLKIT = "org.freedesktop.PolicyKit1"
    private const val AUTHORITY = "org.freedesktop.PolicyKit1.Authority"
    private const val AUTHORITY_PATH = "/org/freedesktop/PolicyKit1/Authority"
    private const val CANCELLED = "org.freedesktop.PolicyKit1.Error.Cancelled"
    private const val FAILED = "org.freedesktop.PolicyKit1.Error.Failed"
    private const val ACCESS_DENIED = "org.freedesktop.DBus.Error.AccessDenied"
    private const val LOGIND = "org.freedesktop.login1"
    private const val LOGIND_SELF = "/org/freedesktop/login1/user/self"
    private const val LOGIND_USER = "org.freedesktop.login1.User"

    private val INTROSPECTION = """
        <node>
          <interface name="$AGENT">
            <method name="BeginAuthentication">
              <arg direction="in" type="s" name="action_id"/>
              <arg direction="in" type="s" name="message"/>
              <arg direction="in" type="s" name="icon_name"/>
              <arg direction="in" type="a{ss}" name="details"/>
              <arg direction="in" type="s" name="cookie"/>
              <arg direction="in" type="a(sa{sv})" name="identities"/>
            </method>
            <method name="CancelAuthentication">
              <arg direction="in" type="s" name="cookie"/>
            </method>
          </interface>
        </node>
    """.trimIndent()
}
