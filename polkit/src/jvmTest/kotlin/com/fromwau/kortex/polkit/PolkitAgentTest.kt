package com.fromwau.kortex.polkit

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import com.fromwau.kern.result.errorOrNull
import com.fromwau.kern.result.getOrNull
import com.fromwau.kortex.auth.AuthState
import com.fromwau.kortex.dbus.Bus
import com.fromwau.kortex.dbus.CallRejected
import com.fromwau.kortex.dbus.DBusConnection
import com.fromwau.kortex.dbus.DBusError
import com.fromwau.kortex.dbus.DBusType
import com.fromwau.kortex.dbus.DBusValue
import com.fromwau.kortex.dbus.PrivateBus
import com.fromwau.kortex.dbus.SystemBus
import com.fromwau.kortex.dbus.asDictionary
import com.fromwau.kortex.dbus.asFields
import com.fromwau.kortex.dbus.asText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/** The agent on a bus of the test's own, with polkitd, logind and the helper all played by the test. */
class PolkitAgentTest {
    private val bus = PrivateBus()
    private val helper = FakeHelper()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val opened = mutableListOf<DBusConnection>()

    /** Every call polkitd's authority took, as member and sender and body. */
    private val authorityCalls = MutableStateFlow<List<Message>>(emptyList())

    @AfterTest
    fun tearDown() {
        scope.cancel()
        opened.forEach { it.close() }
        helper.close()
        bus.close()
    }

    @Test
    fun `it registers for the session logind names, at its own path`() = runBlocking<Unit> {
        logind(display = "2")
        polkitd()
        served()

        val registration = authorityCalls.value.single { it.member == "RegisterAuthenticationAgent" }
        val subject = registration.body[0].asFields!!
        assertEquals("unix-session", subject[0].asText)
        assertEquals("2", subject[1].asDictionary!!["session-id"]?.asText)
        assertEquals(AGENT_PATH, registration.body[2].asText)
    }

    @Test
    fun `a request answered right is reported to polkitd, and leaves the list`() = runBlocking<Unit> {
        logind(display = "2")
        val polkitd = polkitd()
        val requests = served()

        val reply = polkitd.begin()
        val request = withTimeout(SETTLE) { requests.first { it.getOrNull()?.isNotEmpty() == true } }
            .assertSuccess()
            .single()
        assertEquals("org.freedesktop.systemd1.manage-units", request.action)
        assertEquals("Authentication is required to restart a unit.", request.message)
        assertEquals("system-run", request.icon)
        assertEquals(mapOf("unit" to "sshd.service"), request.details)
        assertEquals(ownName(), request.user.value)

        helper.accept().use { attempt ->
            assertEquals(ownName(), attempt.readLine())
            assertEquals(COOKIE, attempt.readLine())
            attempt.send("PAM_PROMPT_ECHO_OFF Password: ")
            withTimeout(SETTLE) { request.conversation.state.first { it is AuthState.Asking } }
            request.conversation.answer("hunter2".toCharArray()).assertSuccess()
            attempt.readLine()
            attempt.send("SUCCESS")
        }

        assertEquals(Ok(emptyList()), withTimeout(SETTLE) { reply.await() })
        withTimeout(SETTLE) { requests.first { it == Ok(emptyList<PolkitRequest>()) } }
    }

    @Test
    fun `a request the user dismisses is reported as cancelled`() = runBlocking<Unit> {
        logind(display = "2")
        val polkitd = polkitd()
        val requests = served()

        val reply = polkitd.begin()
        val request = withTimeout(SETTLE) { requests.first { it.getOrNull()?.isNotEmpty() == true } }
            .assertSuccess()
            .single()
        request.conversation.cancel()

        val failure = withTimeout(SETTLE) { reply.await() }.assertError<DBusError.CallFailed>()
        assertEquals("org.freedesktop.PolicyKit1.Error.Cancelled", failure.name)
    }

    @Test
    fun `polkitd withdrawing a request ends it`() = runBlocking<Unit> {
        logind(display = "2")
        val polkitd = polkitd()
        val requests = served()

        val reply = polkitd.begin()
        val request = withTimeout(SETTLE) { requests.first { it.getOrNull()?.isNotEmpty() == true } }
            .assertSuccess()
            .single()
        polkitd.connection.call(
            destination = agentName(),
            path = AGENT_PATH,
            iface = AGENT,
            member = "CancelAuthentication",
            args = listOf(DBusValue.Text(COOKIE)),
        ).assertSuccess()

        withTimeout(SETTLE) { reply.await() }.assertError<DBusError.CallFailed>()
        assertIs<AuthState.Ended>(request.conversation.state.value)
        withTimeout(SETTLE) { requests.first { it == Ok(emptyList<PolkitRequest>()) } }
    }

    @Test
    fun `every user polkitd offers is listed, this process's own first`() = runBlocking<Unit> {
        logind(display = "2")
        val polkitd = polkitd()
        val requests = served()

        polkitd.begin(uids = listOf(ROOT, ownUid()))
        val request = withTimeout(SETTLE) { requests.first { it.getOrNull()?.isNotEmpty() == true } }
            .assertSuccess()
            .single()

        assertEquals(listOf(ownName(), "root"), request.users)
        assertEquals(ownName(), request.user.value)
    }

    @Test
    fun `switching user asks the helper again, as that user`() = runBlocking<Unit> {
        logind(display = "2")
        val polkitd = polkitd()
        val requests = served()

        polkitd.begin(uids = listOf(ownUid(), ROOT))
        val request = withTimeout(SETTLE) { requests.first { it.getOrNull()?.isNotEmpty() == true } }
            .assertSuccess()
            .single()
        helper.accept().use { first ->
            assertEquals(ownName(), first.readLine())
            first.readLine()
            first.send("PAM_PROMPT_ECHO_OFF Password: ")
            withTimeout(SETTLE) { request.conversation.state.first { it is AuthState.Asking } }

            request.switchUser("root").assertSuccess()

            assertNull(withTimeout(SETTLE) { first.readLine() }, "the first attempt was not hung up on")
        }
        helper.accept().use { second ->
            assertEquals("root", second.readLine())
            assertEquals(COOKIE, second.readLine())
        }
        assertEquals("root", request.user.value)
    }

    @Test
    fun `a user polkitd did not offer is refused`() = runBlocking<Unit> {
        logind(display = "2")
        val polkitd = polkitd()
        val requests = served()

        polkitd.begin()
        val request = withTimeout(SETTLE) { requests.first { it.getOrNull()?.isNotEmpty() == true } }
            .assertSuccess()
            .single()

        assertEquals(UserSwitchError.NotOffered("root"), request.switchUser("root").assertError())
        assertEquals(ownName(), request.user.value)
    }

    @Test
    fun `nobody but polkitd can make it ask for a password`() = runBlocking<Unit> {
        logind(display = "2")
        polkitd()
        val requests = served()
        val stranger = connect()

        val refused = stranger.call(
            destination = agentName(),
            path = AGENT_PATH,
            iface = AGENT,
            member = "BeginAuthentication",
            args = beginArguments(),
        ).assertError<DBusError.CallFailed>()

        assertEquals("org.freedesktop.DBus.Error.AccessDenied", refused.name)
        assertEquals(Ok(emptyList()), requests.value)
    }

    @Test
    fun `a user with no graphical session has nothing to serve`() = runBlocking<Unit> {
        logind(display = "")
        polkitd()

        assertEquals(Err(PolkitError.NoDisplaySession), settled())
    }

    @Test
    fun `polkitd refusing the agent says why`() = runBlocking<Unit> {
        logind(display = "2")
        polkitd(refusal = CallRejected(FAILED, "An authentication agent already exists for the given subject"))

        assertEquals(
            Err(PolkitError.Refused(FAILED, "An authentication agent already exists for the given subject")),
            settled(),
        )
    }

    @Test
    fun `once nobody collects, open requests are cancelled and the agent unregisters`() = runBlocking<Unit> {
        logind(display = "2")
        val polkitd = polkitd()
        val served = PolkitAgent.serve(SystemBus.at(bus.socket, scope), scope, helper.path)
        val collecting = scope.launch { served.collect {} }
        withTimeout(SETTLE) { served.first { it.getOrNull()?.isEmpty() == true } }
        val reply = polkitd.begin()
        val request = withTimeout(SETTLE) { served.first { it.getOrNull()?.isNotEmpty() == true } }
            .assertSuccess()
            .single()

        collecting.cancelAndJoin()

        val failure = withTimeout(SETTLE) { reply.await() }.assertError<DBusError.CallFailed>()
        assertEquals("org.freedesktop.PolicyKit1.Error.Cancelled", failure.name)
        assertIs<AuthState.Ended>(request.conversation.state.value)
        withTimeout(SETTLE) {
            authorityCalls.first { calls -> calls.any { it.member == "UnregisterAuthenticationAgent" } }
        }
    }

    /** The agent's requests, kept collected the way an app would, once it has registered. */
    private suspend fun served(): StateFlow<Result<List<PolkitRequest>, PolkitError>> {
        val served = PolkitAgent.serve(SystemBus.at(bus.socket, scope), scope, helper.path)
        scope.launch { served.collect {} }
        withTimeout(SETTLE) { served.first { it.getOrNull() != null } }
        return served
    }

    /** The first thing the agent reports once the bus is up, which for these tests is why it is not serving. */
    private suspend fun settled(): Result<List<PolkitRequest>, PolkitError> {
        val served = PolkitAgent.serve(SystemBus.at(bus.socket, scope), scope, helper.path)
        return withTimeout(SETTLE) { served.first { it.errorOrNull() != PolkitError.NotConnected } }
    }

    /** logind, answering `Display` for the calling user with [display], empty meaning none. */
    private suspend fun logind(display: String) {
        val connection = connect()
        connection.export("/org/freedesktop/login1/user/self") { call ->
            when {
                call.iface == Bus.PROPERTIES && call.member == "Get" -> Ok(
                    listOf(
                        DBusValue.Variant(
                            DBusValue.Struct(listOf(DBusValue.Text(display), DBusValue.ObjectPath(SESSION_PATH))),
                        ),
                    ),
                )

                else -> Err(CallRejected.unknownMethod(call))
            }
        }
        connection.requestName("org.freedesktop.login1").assertSuccess()
    }

    /** polkitd's authority, taking a registration unless told to refuse it with [refusal]. */
    private suspend fun polkitd(refusal: CallRejected? = null): Polkitd {
        val connection = connect()
        connection.export("/org/freedesktop/PolicyKit1/Authority") { call ->
            authorityCalls.update { it + Message(call.member, call.sender.orEmpty(), call.body) }
            when {
                call.member == "RegisterAuthenticationAgent" && refusal != null -> Err(refusal)
                else -> Ok(emptyList())
            }
        }
        connection.requestName("org.freedesktop.PolicyKit1").assertSuccess()
        return Polkitd(connection)
    }

    private inner class Polkitd(val connection: DBusConnection) {
        /** Asks the agent to authenticate one of [uids], and holds the reply that comes once it is over. */
        suspend fun begin(uids: List<UInt> = listOf(ownUid())): Deferred<Result<List<DBusValue>, DBusError>> {
            val agent = agentName()
            return scope.async {
                connection.call(
                    destination = agent,
                    path = AGENT_PATH,
                    iface = AGENT,
                    member = "BeginAuthentication",
                    args = beginArguments(uids),
                    timeout = SETTLE * 2,
                )
            }
        }
    }

    private suspend fun agentName(): String = withTimeout(SETTLE) {
        authorityCalls.first { calls -> calls.any { it.member == "RegisterAuthenticationAgent" } }
    }.first { it.member == "RegisterAuthenticationAgent" }.sender

    private fun beginArguments(uids: List<UInt> = listOf(ownUid())): List<DBusValue> = listOf(
        DBusValue.Text("org.freedesktop.systemd1.manage-units"),
        DBusValue.Text("Authentication is required to restart a unit."),
        DBusValue.Text("system-run"),
        DBusValue.Sequence(
            DBusType.Pair(DBusType.Basic.Text, DBusType.Basic.Text),
            listOf(DBusValue.Pair(DBusValue.Text("unit"), DBusValue.Text("sshd.service"))),
        ),
        DBusValue.Text(COOKIE),
        DBusValue.Sequence(
            DBusType.Struct(listOf(DBusType.Basic.Text, DBusType.Sequence(VARDICT))),
            uids.map { uid ->
                DBusValue.Struct(
                    listOf(
                        DBusValue.Text("unix-user"),
                        DBusValue.Sequence(
                            VARDICT,
                            listOf(DBusValue.Pair(DBusValue.Text("uid"), DBusValue.Variant(DBusValue.U32(uid)))),
                        ),
                    ),
                )
            },
        ),
    )

    private suspend fun connect(): DBusConnection = DBusConnection
        .open(bus.socket)
        .assertSuccess()
        .also { opened += it }

    private fun ownUid(): UInt = (Files.getAttribute(Path.of("/proc/self"), "unix:uid") as Int).toUInt()

    private fun ownName(): String = System.getProperty("user.name")

    private data class Message(
        val member: String,
        val sender: String,
        val body: List<DBusValue>,
    )

    private companion object {
        const val AGENT_PATH = "/com/fromwau/kortex/PolkitAgent"
        const val AGENT = "org.freedesktop.PolicyKit1.AuthenticationAgent"
        const val COOKIE = "3-a1b2c3-4-d5e6f7"
        const val FAILED = "org.freedesktop.PolicyKit1.Error.Failed"
        const val SESSION_PATH = "/org/freedesktop/login1/session/_32"
        const val ROOT = 0u
        val VARDICT = DBusType.Pair(DBusType.Basic.Text, DBusType.Variant)
        val SETTLE = 10.seconds
    }
}
