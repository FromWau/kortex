package com.fromwau.kortex.notification

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.errorOrNull
import com.fromwau.kern.result.getOrElse
import com.fromwau.kortex.dbus.DBusConnection
import com.fromwau.kortex.dbus.DBusValue
import com.fromwau.kortex.dbus.MatchRule
import com.fromwau.kortex.dbus.Message
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * Kortex as the notification server this session is actually using.
 *
 * Only one connection on a bus may hold `org.freedesktop.Notifications`, so whatever daemon usually holds
 * it has to be stopped for any of this to run: `systemctl --user stop dunst.service`. That is the subject
 * of the test rather than an inconvenience, since being the only server is the whole job.
 *
 * The applications posting here are a second real connection and, in one case, `notify-send` itself.
 */
class NotificationServerTest {
    @Test
    fun `kortex becomes the server and answers for it`() = withServer { server, client, hosting ->
        assertEquals(Ok(hosting.uniqueName), hosting.nameOwner(NotificationServer.INTERFACE))
        assertTrue(server.notifications.value.isEmpty(), "nothing has been posted yet")

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
        ).start().waitFor()

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
    fun `a second server is refused and told who already holds the name`() = withServer { _, _, hosting ->
        DBusConnection.session().getOrElse { fail("a second connection did not open: $it") }.use { second ->
            val refused = NotificationServer.start(second, INFORMATION)

            val error = assertIs<NotificationError.AlreadyServed>(
                refused.errorOrNull(),
                "a second server was allowed to start",
            )
            assertEquals(hosting.uniqueName, error.owner)
            assertEquals(ProcessHandle.current().pid().toInt(), error.pid, "both connections are this process")
            assertNotNull(error.process, "the pid was never turned into a name")
        }
    }

    @Test
    fun `a server that has stopped gives the name back`() = runBlocking {
        DBusConnection.session().getOrElse { fail("the connection did not open: $it") }.use { connection ->
            val server = NotificationServer.start(connection, INFORMATION)
                .getOrElse { error -> fail("kortex could not become the server: $error") }

            assertEquals(Ok(connection.uniqueName), connection.nameOwner(NotificationServer.INTERFACE))
            assertEquals(Ok(Unit), server.stop())
            assertTrue(
                connection.nameOwner(NotificationServer.INTERFACE) != Ok(connection.uniqueName),
                "the name was still held after stopping",
            )
        }
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
                .first { signal -> rule.matches(signal) }
        }
        ready.await()
        trigger()

        return withTimeoutOrNull(BUDGET) { waiting.await() } ?: fail("no $member arrived within the budget")
    }

    private fun withServer(
        body: suspend CoroutineScope.(NotificationServer, DBusConnection, DBusConnection) -> Unit,
    ) = runBlocking {
        DBusConnection.session().getOrElse { fail("the server connection did not open: $it") }.use { hosting ->
            val server = NotificationServer.start(hosting, INFORMATION).getOrElse { error ->
                fail(
                    "kortex could not become the notification server: $error. " +
                        "Stop whatever holds the name first: systemctl --user stop dunst.service",
                )
            }
            try {
                DBusConnection.session().getOrElse { fail("the client connection did not open: $it") }.use { client ->
                    body(server, client, hosting)
                }
            } finally {
                // The name outlives a failed test otherwise, and every test after it reads as AlreadyServed.
                server.stop()
            }
        }
    }

    /** Waits for the posted list to hold [count], since a post crosses two connections to get here. */
    private suspend fun NotificationServer.settle(count: Int): List<Notification> =
        withTimeoutOrNull(BUDGET) { notifications.first { it.size == count } }
            ?: fail("the server carries ${notifications.value.size} notifications, not $count")

    private companion object {
        val BUDGET = 10.seconds

        val INFORMATION = ServerInformation(
            name = "kortex",
            vendor = "com.fromwau",
            version = "0.1.0",
            capabilities = listOf("actions", "body", "body-markup", "persistence"),
        )
    }
}
