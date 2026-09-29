package com.fromwau.kortex.dbus

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.getOrElse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
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
 * The connection against the session bus that is actually running.
 *
 * Nothing here stands in for the bus. A reimplementation of the protocol would agree with whatever kortex
 * got wrong about it, so every expectation below was read off the live bus first and every error name is
 * one dbus-daemon really sends.
 *
 * Only `org.freedesktop.DBus` is relied on, which every session has. The names these tests take are their
 * own and are released when the connection closes.
 */
class SessionBusTest {
    @Test
    fun `connecting authenticates and the bus answers with a unique name`() = onBus { connection ->
        assertTrue(
            Regex("""^:\d+\.\d+$""").matches(connection.uniqueName),
            "the bus named this connection ${connection.uniqueName}, which is not a unique name",
        )
    }

    @Test
    fun `the bus answers with its own id and with every name it holds`() = onBus { connection ->
        val id = connection.bus("GetId").getOrElse { fail("GetId failed: $it") }
        val names = connection.bus("ListNames").getOrElse { fail("ListNames failed: $it") }

        assertTrue(
            Regex("""^[0-9a-f]{32}$""").matches(assertIs<DBusValue.Text>(id.single()).value),
            "the bus id is not 32 hex characters: $id",
        )
        val held = assertIs<DBusValue.Sequence>(names.single()).values.map { assertIs<DBusValue.Text>(it).value }
        assertContains(held, Bus.NAME, "the bus does not list its own name")
        assertContains(held, connection.uniqueName, "the bus does not list this connection")
    }

    /**
     * An argument going out and a number coming back, checked against something known independently.
     *
     * The bus reports the uid of whoever is behind a name, and this process's uid is readable from the
     * filesystem, so the two agreeing means the string reached the bus and the `u` came back intact.
     */
    @Test
    fun `the bus reports this connection's own uid back to it`() = onBus { connection ->
        val answer = connection
            .bus("GetConnectionUnixUser", DBusValue.Text(connection.uniqueName))
            .getOrElse { fail("GetConnectionUnixUser failed: $it") }

        assertEquals(
            DBusValue.U32(currentUid().getOrElse { fail("no uid: $it") }.toUInt()),
            answer.single(),
        )
    }

    /** Three errors the bus really sends, under the names it really sends them as. */
    @Test
    fun `an error reply comes back as the error's own bus name`() = onBus { connection ->
        assertEquals(
            Err(DBusError.CallFailed("org.freedesktop.DBus.Error.UnknownMethod", "Invalid method call")),
            connection.bus("NoSuchMethodAtAll"),
        )
        assertEquals(
            Err(DBusError.CallFailed("org.freedesktop.DBus.Error.NameHasNoOwner", "The name does not have an owner")),
            connection.bus("GetNameOwner", DBusValue.Text("com.fromwau.kortex.test.Nobody")),
        )

        val unknownService = connection.call("com.fromwau.kortex.test.NotThere", "/x", "com.example.X", "Y")
        assertEquals(
            Err(DBusError.CallFailed("org.freedesktop.DBus.Error.ServiceUnknown", "The name is not activatable")),
            unknownService,
        )
    }

    /**
     * A call nothing answers ends as a value.
     *
     * Addressed to this very connection, which the bus routes here faithfully and which nothing here
     * answers, because no module has exported an object yet. So the silence is real routing rather than a
     * peer arranged to be quiet.
     */
    @Test
    fun `a call routed to a connection that answers nothing times out`() = onBus { connection ->
        assertEquals(
            Err(DBusError.ReplyTimedOut),
            connection.call(
                destination = connection.uniqueName,
                path = "/com/fromwau/kortex/test",
                iface = "com.fromwau.kortex.test.Silent",
                member = "SaysNothing",
                timeout = 500.milliseconds,
            ),
        )
    }

    /**
     * A reply reaches the call that asked for it rather than the one that has waited longest.
     *
     * Two calls are left outstanding on purpose, so that a connection handing each arriving reply to its
     * oldest waiter would give this one's answer away and fail. Replies arriving in order cannot show that,
     * which is why the queue is deliberately jammed first.
     */
    @Test
    fun `a reply reaches its own call while others are still outstanding`() = onBus { connection ->
        val stuck = (1..2).map { index ->
            async(Dispatchers.IO) {
                connection.call(
                    destination = connection.uniqueName,
                    path = "/com/fromwau/kortex/test",
                    iface = "com.fromwau.kortex.test.Silent",
                    member = "Stuck$index",
                    timeout = 4.seconds,
                )
            }
        }
        delay(200)

        val answered = connection
            .bus("GetConnectionUnixUser", DBusValue.Text(connection.uniqueName))
            .getOrElse { fail("a call did not complete while two were outstanding: $it") }

        assertEquals(
            DBusValue.U32(currentUid().getOrElse { fail("no uid: $it") }.toUInt()),
            answered.single(),
            "the answer that came back belonged to a different call",
        )
        stuck.forEach { pending -> assertEquals(Err(DBusError.ReplyTimedOut), pending.await()) }
    }

    /** The bus really emits this, and taking a name is the cheapest way to make it happen. */
    @Test
    fun `a signal the bus emits reaches the rule that asked for it`() = onBus { connection ->
        val rule = MatchRule(sender = Bus.NAME, iface = Bus.INTERFACE, member = "NameOwnerChanged")
        assertEquals(Ok(Unit), connection.addMatch(rule))

        val name = "com.fromwau.kortex.test.Signalled"
        val arrived = collectWhile(connection.signals(rule)) {
            connection.requestName(name)
            connection.releaseName(name)
        }

        val signal = assertNotNull(arrived, "no NameOwnerChanged arrived within the budget")
        assertEquals("NameOwnerChanged", signal.member)
        assertEquals(Bus.PATH, signal.path)
        assertEquals(3, signal.body.size, "NameOwnerChanged carries the name, the old owner and the new one")
    }

    /**
     * The negative names a member the bus has no signal for.
     *
     * `NameLost` was the first choice and was wrong: releasing a name makes the bus send exactly that, so
     * the rule was right to take it and the test was measuring the bus rather than the filter.
     */
    @Test
    fun `a rule that does not match the signal gets nothing while one that does gets it`() = onBus { connection ->
        val matching = MatchRule(iface = Bus.INTERFACE, member = "NameOwnerChanged")
        val neverSent = MatchRule(iface = Bus.INTERFACE, member = "NoSignalOfThisName")
        assertEquals(Ok(Unit), connection.addMatch(matching))

        val name = "com.fromwau.kortex.test.Filtered"
        val missed = async(Dispatchers.IO) { connection.signals(neverSent).first() }
        val arrived = collectWhile(connection.signals(matching)) {
            connection.requestName(name)
            connection.releaseName(name)
        }

        assertNotNull(arrived, "the matching rule saw nothing, so the negative below proves nothing")
        assertTrue(missed.isActive, "a rule took a signal whose member it did not ask for")
        missed.cancel()
    }

    /**
     * Some signals arrive with no rule asked for, because the bus addresses them to this connection.
     *
     * Found by a test that assumed the opposite. It matters twice over: a provider cannot assume that
     * every signal it sees is one it subscribed to, and `:notification` needs `NameLost` to learn that it
     * has stopped being the notification server, which it gets for free.
     */
    @Test
    fun `a signal the bus addresses to this connection arrives without any match rule`() = onBus { connection ->
        val name = "com.fromwau.kortex.test.Directed"

        val acquired = collectWhile(connection.signals(MatchRule(member = "NameAcquired"))) {
            connection.requestName(name)
            connection.releaseName(name)
        }

        val signal = assertNotNull(acquired, "no NameAcquired arrived, though no rule was ever added")
        assertEquals(Bus.NAME, signal.sender)
        assertEquals(listOf(DBusValue.Text(name)), signal.body)
    }

    /** Removing a rule has to stop delivery, or an unwatched provider keeps costing the bus work. */
    @Test
    fun `a rule that has been removed stops delivering`() = onBus { connection ->
        val rule = MatchRule(iface = Bus.INTERFACE, member = "NameOwnerChanged")
        val name = "com.fromwau.kortex.test.Removed"

        assertEquals(Ok(Unit), connection.addMatch(rule))
        assertNotNull(
            collectWhile(connection.signals(rule)) {
                connection.requestName(name)
                connection.releaseName(name)
            },
            "the rule never delivered, so removing it proves nothing",
        )

        assertEquals(Ok(Unit), connection.removeMatch(rule))
        val afterRemoval = async(Dispatchers.IO) { connection.signals(rule).first() }
        repeat(5) {
            connection.requestName(name)
            connection.releaseName(name)
            delay(50)
        }

        assertTrue(afterRemoval.isActive, "a signal arrived after its rule was removed")
        afterRemoval.cancel()
    }

    /**
     * Two real connections, because a name being taken needs somebody to have taken it.
     *
     * This is the case `:notification` turns on: only one connection can be the notification server, and
     * kortex asks not to be queued behind whoever already is.
     */
    @Test
    fun `a name is held by the first connection and refused to the second`() = onBus { first ->
        val name = "com.fromwau.kortex.test.Contested"

        assertEquals(Ok(NameRequest.Held), first.requestName(name))
        assertEquals(Ok(NameRequest.AlreadyHeld), first.requestName(name))

        DBusConnection.session().getOrElse { fail("a second connection did not open: $it") }.use { second ->
            assertEquals(Ok(NameRequest.Taken), second.requestName(name))
            assertEquals(Ok(first.uniqueName), second.nameOwner(name))
        }

        assertEquals(Ok(Unit), first.releaseName(name))
        assertEquals(
            Err(DBusError.CallFailed("org.freedesktop.DBus.Error.NameHasNoOwner", "The name does not have an owner")),
            first.nameOwner(name),
            "the name was still owned after being released",
        )
    }

    /** `Get` answers in a variant and `GetAll` in a dictionary of them; neither should reach a caller. */
    @Test
    fun `a property read unwraps the variant the bus answers in`() = onBus { connection ->
        val interfaces = connection
            .property(Bus.NAME, Bus.PATH, Bus.INTERFACE, "Interfaces")
            .getOrElse { fail("reading Interfaces failed: $it") }
        val all = connection
            .properties(Bus.NAME, Bus.PATH, Bus.INTERFACE)
            .getOrElse { fail("GetAll failed: $it") }

        assertIs<DBusValue.Sequence>(interfaces, "Interfaces is an as, and a variant should not survive the read")
        assertEquals(DBusType.Basic.Text, interfaces.element)
        assertContains(all.keys, "Interfaces")
        assertContains(all.keys, "Features")
        assertEquals(interfaces, all["Interfaces"], "the two ways of reading one property disagree")
    }

    @Test
    fun `a closed connection refuses a call instead of waiting on a dead socket`() = runBlocking {
        val connection = DBusConnection.session().getOrElse { fail("the connection did not open: $it") }
        connection.close()

        assertEquals(Err(DBusError.Disconnected), connection.bus("GetId"))
    }

    /**
     * Waits for one signal while [trigger] keeps making the bus send it.
     *
     * A shared flow replays nothing, so a signal sent before the collector has subscribed is gone and
     * there is no subscription count to wait on. Triggering repeatedly costs two name changes a cycle and
     * removes the race entirely.
     */
    private suspend fun CoroutineScope.collectWhile(
        signals: Flow<Message.Signal>,
        trigger: suspend () -> Unit,
    ): Message.Signal? {
        val waiting = async(Dispatchers.IO) { signals.first() }
        return withTimeoutOrNull(10.seconds) {
            while (!waiting.isCompleted) {
                trigger()
                delay(50)
            }
            waiting.await()
        }.also { if (waiting.isActive) waiting.cancel() }
    }

    private fun onBus(body: suspend CoroutineScope.(DBusConnection) -> Unit) = runBlocking {
        DBusConnection
            .session()
            .getOrElse { error -> fail("no session bus answered: $error") }
            .use { connection -> body(connection) }
    }
}

/** A call to the bus's own object, which is most of what a test here needs. */
private suspend fun DBusConnection.bus(
    member: String,
    vararg args: DBusValue,
) = call(Bus.NAME, Bus.PATH, Bus.INTERFACE, member, args.toList())
