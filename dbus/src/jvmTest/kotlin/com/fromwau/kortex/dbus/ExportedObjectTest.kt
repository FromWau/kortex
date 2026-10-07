package com.fromwau.kortex.dbus

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.getOrElse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import com.fromwau.kern.result.assertSuccess

/**
 * The half a tray host can never exercise: answering a call rather than making one.
 *
 * Two real connections on the running bus, one exporting and one calling. Nothing has to hold a well-known
 * name for this, so nothing on the desktop is displaced by it.
 */
class ExportedObjectTest {
    @Test
    fun `an exported object answers a call made to it`() = withPair { server, client ->
        val seen = CopyOnWriteArrayList<Message.Call>()
        server.export(PATH) { call ->
            seen += call
            Ok(listOf(DBusValue.Text("answered ${call.member}")))
        }

        val reply = client.call(server.uniqueName, PATH, IFACE, "Echo", listOf(DBusValue.I32(7)))

        assertEquals(Ok(listOf(DBusValue.Text("answered Echo"))), reply)
        val call = assertNotNull(seen.firstOrNull(), "the handler never ran")
        assertEquals(PATH, call.path)
        assertEquals(IFACE, call.iface)
        assertEquals(listOf(DBusValue.I32(7)), call.body, "the arguments did not survive the trip")
        assertEquals(client.uniqueName, call.sender, "the bus did not say who called")
    }

    @Test
    fun `a handler that rejects a call sends back the error name it chose`() = withPair { server, client ->
        server.export(PATH) { call -> Err(CallRejected.unknownMethod(call)) }

        assertEquals(
            Err(DBusError.CallFailed(CallRejected.UNKNOWN_METHOD, "no such method $IFACE.Nope")),
            client.call(server.uniqueName, PATH, IFACE, "Nope"),
        )
    }

    @Test
    fun `a call to a path nothing is exported at is refused rather than left waiting`() =
        withPair { server, client ->
            server.export(PATH) { Ok(emptyList()) }

            val reply = client.call(server.uniqueName, "/somewhere/else", IFACE, "Anything")

            assertEquals(
                Err(DBusError.CallFailed(CallRejected.UNKNOWN_OBJECT, "nothing is exported at /somewhere/else")),
                reply,
            )
        }

    @Test
    fun `an object that has been unexported stops answering`() = withPair { server, client ->
        server.export(PATH) { Ok(listOf(DBusValue.Text("here"))) }
        assertEquals(Ok(listOf(DBusValue.Text("here"))), client.call(server.uniqueName, PATH, IFACE, "Poke"))

        server.unexport(PATH)

        assertEquals(
            Err(DBusError.CallFailed(CallRejected.UNKNOWN_OBJECT, "nothing is exported at $PATH")),
            client.call(server.uniqueName, PATH, IFACE, "Poke"),
        )
    }

    /** Peer is about the connection, so it is answered whatever the object would have said. */
    @Test
    fun `Ping is answered without the handler being asked`() = withPair { server, client ->
        val reached = CopyOnWriteArrayList<String>()
        server.export(PATH) { call ->
            reached += call.member
            Err(CallRejected(CallRejected.UNKNOWN_METHOD, "the handler should not have seen this"))
        }

        assertEquals(Ok(emptyList()), client.call(server.uniqueName, PATH, PEER, "Ping"))
        assertTrue(reached.isEmpty(), "Ping reached the object's own handler")
    }

    @Test
    fun `GetMachineId answers the id this machine actually has`() = withPair { server, client ->
        server.export(PATH) { Ok(emptyList()) }

        val answered = client.call(server.uniqueName, PATH, PEER, "GetMachineId")
            .getOrElse { error -> fail("GetMachineId failed: $error") }

        val onDisk = java.nio.file.Files.readString(java.nio.file.Path.of("/etc/machine-id")).trim()
        assertEquals(listOf(DBusValue.Text(onDisk)), answered)
    }

    @Test
    fun `an object introspects as the xml it was exported with`() = withPair { server, client ->
        server.export(PATH, introspection = XML) { Ok(emptyList()) }

        assertEquals(
            Ok(listOf(DBusValue.Text(XML))),
            client.call(server.uniqueName, PATH, INTROSPECTABLE, "Introspect"),
        )
    }

    /** An object exported without any is not pretending to have one. */
    @Test
    fun `an object exported with no xml refuses to introspect`() = withPair { server, client ->
        server.export(PATH) { call -> Err(CallRejected.unknownMethod(call)) }

        val reply = client.call(server.uniqueName, PATH, INTROSPECTABLE, "Introspect")

        assertEquals(
            Err(DBusError.CallFailed(CallRejected.UNKNOWN_METHOD, "no such method $INTROSPECTABLE.Introspect")),
            reply,
        )
    }

    /**
     * A caller that promised not to wait is not answered at all.
     *
     * Sending one anyway would leave a reply on the bus that nothing will ever match to a serial, which is
     * exactly the message a peer is entitled to treat as a protocol violation.
     */
    @Test
    fun `a call that expects no reply is handled and not answered`() = withPair { server, client ->
        val seen = CopyOnWriteArrayList<String>()
        server.export(PATH) { call ->
            seen += call.member
            Ok(listOf(DBusValue.Text("nobody will read this")))
        }

        assertEquals(Ok(Unit), client.post(server.uniqueName, PATH, IFACE, "Quiet"))

        val ran = withTimeoutOrNull(5.seconds) {
            while (seen.isEmpty()) delay(20)
            true
        }
        assertEquals(true, ran, "the handler never ran for a call that expected no reply")
        // Still usable afterwards, which an unmatched reply on the socket would have put in doubt.
        assertEquals(Ok(emptyList()), client.call(server.uniqueName, PATH, PEER, "Ping"))
    }

    /** A handler that takes its time must not stop the connection reading anything else. */
    @Test
    fun `a slow handler does not stop the connection answering something else`() = withPair { server, client ->
        server.export(PATH) { call ->
            if (call.member == "Slow") delay(1.seconds)
            Ok(listOf(DBusValue.Text(call.member)))
        }

        val slow = async(Dispatchers.IO) { client.call(server.uniqueName, PATH, IFACE, "Slow") }
        delay(150)
        val quick = client.call(server.uniqueName, PATH, IFACE, "Quick", timeout = 2.seconds)

        assertEquals(Ok(listOf(DBusValue.Text("Quick"))), quick, "a slow handler held up the next call")
        assertEquals(Ok(listOf(DBusValue.Text("Slow"))), slow.await())
    }

    @Test
    fun `a signal emitted from an exported object reaches a connection that asked for it`() =
        withPair { server, client ->
            val rule = MatchRule(sender = server.uniqueName, iface = IFACE, path = PATH)
            assertEquals(Ok(Unit), client.addMatch(rule))

            val waiting = async(Dispatchers.IO) { client.signals(rule).first().assertSuccess() }
            val arrived = withTimeoutOrNull(10.seconds) {
                while (!waiting.isCompleted) {
                    server.emit(PATH, IFACE, "Happened", listOf(DBusValue.U32(9u)))
                    delay(50)
                }
                waiting.await()
            }

            val signal = assertNotNull(arrived, "no signal arrived within the budget")
            assertEquals("Happened", signal.member)
            assertEquals(PATH, signal.path)
            assertEquals(server.uniqueName, signal.sender)
            assertEquals(listOf(DBusValue.U32(9u)), signal.body)
            if (waiting.isActive) waiting.cancel()
        }

    /** Two objects on one connection are told apart by path, which is all a caller gives. */
    @Test
    fun `two exported objects each answer only calls to their own path`() = withPair { server, client ->
        server.export("$PATH/one") { Ok(listOf(DBusValue.Text("one"))) }
        server.export("$PATH/two") { Ok(listOf(DBusValue.Text("two"))) }

        assertEquals(
            Ok(listOf(DBusValue.Text("one"))),
            client.call(server.uniqueName, "$PATH/one", IFACE, "Which"),
        )
        assertEquals(
            Ok(listOf(DBusValue.Text("two"))),
            client.call(server.uniqueName, "$PATH/two", IFACE, "Which"),
        )
    }

    /** What a peer that does not know the path sees, which is the price of not walking the tree. */
    @Test
    fun `an exported object is visible to the bus as a name that can be called`() = withPair { server, client ->
        server.export(PATH) { Ok(emptyList()) }

        val names = client
            .call(Bus.NAME, Bus.PATH, Bus.INTERFACE, "ListNames")
            .getOrElse { error -> fail("ListNames failed: $error") }
        val held = (names.single() as DBusValue.Sequence).values.map { (it as DBusValue.Text).value }

        assertContains(held, server.uniqueName)
        assertEquals(
            Ok(emptyList()),
            client.call(server.uniqueName, PATH, PEER, "Ping", timeout = 500.milliseconds),
        )
    }

    private fun withPair(body: suspend CoroutineScope.(DBusConnection, DBusConnection) -> Unit) = runBlocking {
        DBusConnection.session().getOrElse { fail("the server connection did not open: $it") }.use { server ->
            DBusConnection.session().getOrElse { fail("the client connection did not open: $it") }.use { client ->
                body(server, client)
            }
        }
    }

    private companion object {
        const val PATH = "/com/fromwau/kortex/test"
        const val IFACE = "com.fromwau.kortex.test.Exported"
        const val PEER = "org.freedesktop.DBus.Peer"
        const val INTROSPECTABLE = "org.freedesktop.DBus.Introspectable"

        val XML = """
            <node>
              <interface name="$IFACE">
                <method name="Echo"><arg direction="in" type="s" name="what"/></method>
              </interface>
            </node>
        """.trimIndent()
    }
}
