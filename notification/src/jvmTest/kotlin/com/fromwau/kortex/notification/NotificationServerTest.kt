package com.fromwau.kortex.notification

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertSuccess
import com.fromwau.kern.result.errorOrNull
import com.fromwau.kern.result.getOrElse
import com.fromwau.kern.result.getOrNull
import com.fromwau.kortex.dbus.Backoff
import com.fromwau.kortex.dbus.BusState
import com.fromwau.kortex.dbus.DBusConnection
import com.fromwau.kortex.dbus.DBusValue
import com.fromwau.kortex.dbus.MatchRule
import com.fromwau.kortex.dbus.Message
import com.fromwau.kortex.dbus.PrivateBus
import com.fromwau.kortex.dbus.SessionBus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Kortex as the notification server, on a `dbus-daemon` of the test's own.
 *
 * Only one connection on a bus may hold `org.freedesktop.Notifications`, and being that one is the whole
 * job. A bus of its own is where a test can have the name without taking it from the desktop's daemon,
 * and can kill and start the bus again, which is a session bus restarting under a running shell.
 *
 * The applications posting here are a second real connection and, in one case, `notify-send` itself.
 */
class NotificationServerTest {
    private val bus = PrivateBus()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val opened = mutableListOf<DBusConnection>()
    private val session = SessionBus.at(bus.socket, scope, FAST)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        opened.forEach { it.close() }
        bus.close()
    }

    @Test
    fun `kortex becomes the server and answers for it`() = withServer { server, client, hosting ->
        assertEquals(Ok(hosting.uniqueName), hosting.nameOwner(NotificationServer.INTERFACE))
        assertEquals(Ok(emptyList()), server.notifications.value, "nothing has been posted yet")

        val information = client.notifications("GetServerInformation")

        assertEquals(DBusValue.Text(INFORMATION.name), information[0])
        assertEquals(DBusValue.Text(INFORMATION.vendor), information[1])
        assertEquals(DBusValue.Text(INFORMATION.version), information[2])
        assertEquals(
            DBusValue.Text("1.2"),
            information[3],
            "the spec version, read off a real server before it was claimed here",
        )
    }

    @Test
    fun `a notification an application posts arrives with everything it sent`() = withServer { server, client, _ ->
        val id = client.notify(
            notifyBody(
                appName = "Signal",
                appIcon = "signal-desktop",
                summary = "New message",
                body = "from somebody",
                actions = listOf("default", "Open", "reply", "Reply"),
                hints = mapOf(
                    "urgency" to DBusValue.U8(2),
                    "category" to DBusValue.Text("im.received"),
                    "desktop-entry" to DBusValue.Text("org.signal.Signal"),
                ),
                expireMillis = 0,
            ),
        )

        assertTrue(id > 0u, "an id of 0 is not one an application can refer to")
        val posted = server.settle(1).single()
        assertEquals(id, posted.id)
        assertEquals("Signal", posted.appName)
        assertEquals("signal-desktop", posted.appIcon)
        assertEquals("New message", posted.summary)
        assertEquals("from somebody", posted.body)
        assertEquals(Urgency.Critical, posted.urgency)
        assertEquals(Expiry.Never, posted.expiry)
        assertEquals("im.received", posted.category)
        assertEquals("org.signal.Signal", posted.desktopEntry)
        assertEquals(
            listOf(NotificationAction("default", "Open"), NotificationAction("reply", "Reply")),
            posted.actions,
        )
    }

    /** The one real client here, and the only thing that proves libnotify agrees with this reading. */
    @Test
    fun `notify-send reaches kortex`() = withServer { server, _, _ ->
        val sent = ProcessBuilder(
            "notify-send",
            "--urgency=critical",
            "--app-name=kortex-test",
            "--category=test.category",
            "a summary from notify-send",
            "and a body",
        ).apply { environment()["DBUS_SESSION_BUS_ADDRESS"] = "unix:path=${bus.socket}" }.start().waitFor()

        assertEquals(0, sent, "notify-send did not exit cleanly")
        val posted = server.settle(1).single()
        assertEquals("a summary from notify-send", posted.summary)
        assertEquals("and a body", posted.body)
        assertEquals("kortex-test", posted.appName)
        assertEquals(Urgency.Critical, posted.urgency)
        assertEquals("test.category", posted.category)
    }

    @Test
    fun `a notification that replaces another keeps its id and its place`() = withServer { server, client, _ ->
        val first = client.notify(notifyBody(summary = "first"))
        val second = client.notify(notifyBody(summary = "second"))
        server.settle(2)

        val replaced = client.notify(notifyBody(summary = "first, again", replaces = first))

        assertEquals(first, replaced, "a replacement was given a new id")
        val posted = server.settle(2)
        assertEquals(listOf("first, again", "second"), posted.map { it.summary }, "the replacement moved")
        assertEquals(listOf(first, second), posted.map { it.id })
    }

    /**
     * What a progress notification that has not moved yet sends, and the one replacement with nothing to see.
     *
     * A notification is a value and [NotificationServer.notifications] is a [kotlinx.coroutines.flow.StateFlow],
     * so a second posting of identical content is an identical list that a collector is never handed.
     * [Notification.revision] is what keeps the postings apart.
     */
    @Test
    fun `a replacement that changes nothing still reaches a collector`() = withServer { server, client, _ ->
        val id = client.notify(notifyBody(summary = "unchanged"))
        assertEquals(FIRST_REVISION, server.settle(1).single().revision)

        val subscribed = CompletableDeferred<Unit>()
        val next = async(Dispatchers.IO) {
            server.notifications
                .onSubscription { subscribed.complete(Unit) }
                .drop(1)
                .first()
                .assertSuccess()
        }
        subscribed.await()

        assertEquals(id, client.notify(notifyBody(summary = "unchanged", replaces = id)), "the id changed")

        val posted = withTimeoutOrNull(BUDGET) { next.await() }
            ?: fail("the replacement reached no collector, so nothing can redraw or retime the notification")
        assertEquals(2u, posted.single().revision, "the replacement is not marked as a second posting")
        assertEquals("unchanged", posted.single().summary)
    }

    @Test
    fun `a replaces id nothing is posted under is treated as a new notification`() = withServer { server, client, _ ->
        val id = client.notify(notifyBody(summary = "out of nowhere", replaces = 9_999u))

        assertTrue(id != 9_999u, "an id nothing was posted under was taken at face value")
        assertEquals("out of nowhere", server.settle(1).single().summary)
    }

    @Test
    fun `closing a notification takes it away and tells the application why`() = withServer { server, client, _ ->
        val id = client.notify(notifyBody(summary = "will be closed"))
        server.settle(1)

        val closed = awaitSignal(client, "NotificationClosed") { server.close(id, CloseReason.Expired) }

        assertEquals(listOf(DBusValue.U32(id), DBusValue.U32(1u)), closed.body, "reason 1 is expired")
        assertTrue(server.settle(0).isEmpty())
    }

    @Test
    fun `closing one that is not posted says so rather than pretending`() = withServer { server, _, _ ->
        assertEquals(Err(NotificationError.NoSuchNotification(42u)), server.close(42u, CloseReason.Dismissed))
    }

    @Test
    fun `an application closing its own notification is told nothing is wrong`() = withServer { server, client, _ ->
        val id = client.notify(notifyBody(summary = "withdrawn by its application"))
        server.settle(1)

        val closed = awaitSignal(client, "NotificationClosed") {
            assertEquals(emptyList(), client.notifications("CloseNotification", DBusValue.U32(id)))
        }

        assertEquals(listOf(DBusValue.U32(id), DBusValue.U32(3u)), closed.body, "reason 3 is the application asking")
        assertTrue(server.settle(0).isEmpty())
    }

    @Test
    fun `invoking an action tells the application and then closes the notification`() =
        withServer { server, client, _ ->
            val id = client.notify(notifyBody(summary = "has an action", actions = listOf("reply", "Reply")))
            server.settle(1)

            val invoked = awaitSignal(client, "ActionInvoked") { server.invoke(id, "reply") }

            assertEquals(listOf(DBusValue.U32(id), DBusValue.Text("reply")), invoked.body)
            assertTrue(server.settle(0).isEmpty(), "a notification that is not resident should be gone")
        }

    /** The one hint that changes what invoking an action does. */
    @Test
    fun `invoking an action on a resident notification leaves it posted`() = withServer { server, client, _ ->
        val id = client.notify(
            notifyBody(
                summary = "stays put",
                actions = listOf("more", "More"),
                hints = mapOf("resident" to DBusValue.Bool(true)),
            ),
        )
        server.settle(1)

        val invoked = awaitSignal(client, "ActionInvoked") { server.invoke(id, "more") }

        assertEquals(listOf(DBusValue.U32(id), DBusValue.Text("more")), invoked.body)
        assertEquals(listOf(id), server.settle(1).map { it.id }, "a resident notification was closed anyway")
    }

    /** Declared by the caller, because only the content drawing a notification knows what it can render. */
    @Test
    fun `the capabilities a caller declared are the ones an application is told about`() =
        withServer { _, client, _ ->
            val declared = assertIs<DBusValue.Sequence>(client.notifications("GetCapabilities").single())
                .values
                .map { assertIs<DBusValue.Text>(it).value }

            assertEquals(INFORMATION.capabilities, declared)
        }

    /**
     * The failure the plan called a first-class outcome rather than an edge case.
     *
     * Both connections are this process, so the pid reported is this test's own and the name is whatever
     * `/proc/<pid>/comm` says it is, which is the whole reason for looking either of them up.
     */
    @Test
    fun `a second server is told who already holds the name`() = withServer { _, _, hosting ->
        val second = NotificationServer(SessionBus.at(bus.socket, scope, FAST), INFORMATION, scope).alsoServing()

        val refused = second.notifications.awaitValue("the second server refused") {
            it.errorOrNull() is NotificationError.AlreadyServed
        }
        val error = refused.errorOrNull() as NotificationError.AlreadyServed
        assertEquals(hosting.uniqueName, error.owner)
        assertEquals(ProcessHandle.current().pid().toInt(), error.pid, "both connections are this process")
        assertNotNull(error.process, "the pid was never turned into a name")
    }

    @Test
    fun `a server waiting on the name takes it once its holder lets go`() = runBlocking<Unit> {
        val first = NotificationServer(session, INFORMATION, scope)
        val holding = scope.launch { first.notifications.collect {} }
        first.notifications.awaitValue("the first server serving") { it is Ok }
        val second = NotificationServer(SessionBus.at(bus.socket, scope, FAST), INFORMATION, scope).alsoServing()
        second.notifications.awaitValue("the second server waiting") {
            it.errorOrNull() is NotificationError.AlreadyServed
        }

        holding.cancel()

        second.notifications.awaitValue("the second server serving") { it is Ok }
    }

    /** With the bus itself still watched, as a shell's tray watches it, so the connection stays open. */
    @Test
    fun `nobody collecting gives the name back`() = runBlocking<Unit> {
        scope.launch { session.state.collect {} }
        val server = NotificationServer(session, INFORMATION, scope)
        val holding = scope.launch { server.notifications.collect {} }
        server.notifications.awaitValue("the server serving") { it is Ok }
        val other = connect()

        holding.cancel()

        withTimeoutOrNull(BUDGET) {
            while (other.nameOwner(NotificationServer.INTERFACE) is Ok) delay(50.milliseconds)
        } ?: fail("the name was still held after the last collector left")
    }

    @Test
    fun `the server serves again once the bus is back, with what was posted before`() =
        withServer { server, client, _ ->
            val before = client.notify(notifyBody(summary = "posted before the restart"))
            server.settle(1)

            bus.kill()
            server.notifications.awaitValue("the server saying the bus is down") {
                it.errorOrNull() is NotificationError.BusDown
            }
            bus.restart()
            server.notifications.awaitValue("the server serving again") { it is Ok }

            val after = connect().notify(notifyBody(summary = "posted after the restart"))
            assertTrue(after > before, "an id was handed out again after the restart")
            assertEquals(
                listOf("posted before the restart", "posted after the restart"),
                server.settle(2).map { it.summary },
            )
        }

    @Test
    fun `closing while the bus is down takes it away and says the application was not told`() =
        withServer { server, client, _ ->
            val id = client.notify(notifyBody(summary = "closed while the bus is down"))
            server.settle(1)

            bus.kill()
            server.notifications.awaitValue("the server saying the bus is down") {
                it.errorOrNull() is NotificationError.BusDown
            }

            assertIs<NotificationError.BusDown>(server.close(id, CloseReason.Dismissed).errorOrNull())
            bus.restart()
            assertTrue(server.settle(0).isEmpty())
        }

    @Test
    fun `the exported object introspects, so the bus can be asked what kortex offers`() =
        withServer { _, client, _ ->
            val xml = client
                .call(
                    NotificationServer.INTERFACE,
                    NotificationServer.PATH,
                    "org.freedesktop.DBus.Introspectable",
                    "Introspect",
                )
                .getOrElse { error -> fail("Introspect failed: $error") }
                .single()

            val text = assertIs<DBusValue.Text>(xml).value
            assertContains(text, """<method name="Notify">""")
            assertContains(text, """<arg direction="in" type="a{sv}" name="hints"/>""")
            assertContains(text, """<signal name="ActionInvoked">""")
        }

    /** A call to the notification object, which is all any application here makes. */
    private suspend fun DBusConnection.notifications(member: String, vararg args: DBusValue): List<DBusValue> = call(
        destination = NotificationServer.INTERFACE,
        path = NotificationServer.PATH,
        iface = NotificationServer.INTERFACE,
        member = member,
        args = args.toList(),
    ).getOrElse { error -> fail("$member failed: $error") }

    private suspend fun DBusConnection.notify(body: List<DBusValue>): UInt =
        assertIs<DBusValue.U32>(
            call(
                destination = NotificationServer.INTERFACE,
                path = NotificationServer.PATH,
                iface = NotificationServer.INTERFACE,
                member = "Notify",
                args = body,
            ).getOrElse { error -> fail("Notify failed: $error") }.single(),
        ).value

    /**
     * Runs [trigger] only once [on] is subscribed.
     *
     * A shared flow replays nothing, so a signal sent before the collector is subscribed is simply gone,
     * and the trigger here fires once and cannot be retried the way taking a bus name can.
     */
    private suspend fun CoroutineScope.awaitSignal(
        on: DBusConnection,
        member: String,
        trigger: suspend () -> Unit,
    ): Message.Signal {
        val rule = MatchRule(iface = NotificationServer.INTERFACE, member = member)
        assertEquals(Ok(Unit), on.addMatch(rule))

        val ready = CompletableDeferred<Unit>()
        val waiting = async(Dispatchers.IO) {
            on.allSignals
                .onSubscription { ready.complete(Unit) }
                .first { received -> received !is Ok || rule.matches(received.value) }
                .assertSuccess()
        }
        ready.await()
        trigger()

        return withTimeoutOrNull(BUDGET) { waiting.await() } ?: fail("no $member arrived within the budget")
    }

    /** A server that has taken the name, a client connection to post with, and the connection serving. */
    private fun withServer(
        body: suspend CoroutineScope.(NotificationServer, DBusConnection, DBusConnection) -> Unit,
    ) = runBlocking<Unit> {
        val server = NotificationServer(session, INFORMATION, scope).alsoServing()
        server.notifications.awaitValue("kortex serving") { it is Ok }
        val hosting = assertIs<BusState.Up>(session.state.value).connection
        body(server, connect(), hosting)
    }

    /** Keeps [NotificationServer.notifications] collected, which is what holds the name. */
    private fun NotificationServer.alsoServing(): NotificationServer =
        also { server -> scope.launch { server.notifications.collect {} } }

    private suspend fun connect(): DBusConnection =
        DBusConnection.open(bus.socket).assertSuccess().also { opened += it }

    private suspend fun <T> StateFlow<T>.awaitValue(
        what: String,
        until: (T) -> Boolean,
    ): T = withTimeoutOrNull(BUDGET) { first(until) } ?: fail("never saw $what within $BUDGET: last was $value")

    /** Waits for the posted list to hold [count], since a post crosses two connections to get here. */
    private suspend fun NotificationServer.settle(count: Int): List<Notification> =
        withTimeoutOrNull(BUDGET) { notifications.mapNotNull { it.getOrNull() }.first { it.size == count } }
            ?: fail("the server carries ${notifications.value}, not $count notifications")

    private companion object {
        val BUDGET = 10.seconds
        val FAST = Backoff(first = 20.milliseconds, cap = 200.milliseconds)

        val INFORMATION = ServerInformation(
            name = "kortex",
            vendor = "com.fromwau",
            version = "0.1.0",
            capabilities = listOf("actions", "body", "body-markup", "persistence"),
        )
    }
}
