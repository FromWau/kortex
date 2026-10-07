package com.fromwau.kortex.dbus

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.assertSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A connection whose bus goes away says so: to the calls waiting on it, to its signal subscribers after the
 * last signal they were owed, and to anyone who asks afterwards.
 *
 * Against a `dbus-daemon` the test starts and kills itself, which is what a bus restarting looks like from
 * the client's side.
 */
class ConnectionDeathTest {
    private val bus = PrivateBus()
    private val opened = mutableListOf<DBusConnection>()

    @AfterTest
    fun tearDown() {
        opened.forEach { it.close() }
        bus.close()
    }

    @Test
    fun `closed is null while the bus is up, and the reason once it is gone`() = runBlocking<Unit> {
        val connection = connect()
        assertNull(connection.closed.value)

        bus.kill()

        assertNotNull(withTimeout(SETTLE) { connection.closed.first { it != null } })
    }

    @Test
    fun `signals that arrived before the death come first, and the death comes last`() = runBlocking<Unit> {
        val listener = connect()
        val sender = connect()
        val rule = MatchRule(sender = sender.uniqueName, iface = IFACE, member = "Happened")
        listener.addMatch(rule).assertSuccess()
        val received = MutableStateFlow<List<Result<Message.Signal, DBusError>>>(emptyList())
        val collecting = launch(Dispatchers.IO) {
            listener.signals(rule).collect { one -> received.update { it + one } }
        }

        // A warm-up sent until one lands, since nothing says when the rule has reached the daemon.
        withTimeout(SETTLE) {
            while (received.value.isEmpty()) {
                sender.emit(PATH, IFACE, "Happened", listOf(DBusValue.U32(WARM_UP)))
                delay(50.milliseconds)
            }
        }
        (10u..12u).forEach { n -> sender.emit(PATH, IFACE, "Happened", listOf(DBusValue.U32(n))).assertSuccess() }
        withTimeout(SETTLE) { received.first { all -> numbersIn(all).containsAll(listOf(10u, 11u, 12u)) } }

        bus.kill()
        withTimeout(SETTLE) { collecting.join() }

        val all = received.value
        assertIs<Err<DBusError>>(all.last(), "the stream ends with the death: $all")
        assertEquals(1, all.count { it is Err }, "exactly one end: $all")
        assertEquals(listOf(10u, 11u, 12u), numbersIn(all).filter { it != WARM_UP }, "in order, ahead of the end")
    }

    private fun numbersIn(all: List<Result<Message.Signal, DBusError>>): List<UInt> =
        all.mapNotNull { (it as? Ok)?.value?.body?.firstOrNull() as? DBusValue.U32 }.map { it.value }

    @Test
    fun `a subscriber that arrives after the death is told at once`() = runBlocking<Unit> {
        val connection = connect()
        bus.kill()
        withTimeout(SETTLE) { connection.closed.first { it != null } }

        val first = withTimeout(SETTLE) { connection.allSignals.first() }

        assertEquals(Err(connection.closed.value!!), first)
    }

    @Test
    fun `a call after the death fails with the reason the connection ended`() = runBlocking<Unit> {
        val connection = connect()
        bus.kill()
        val death = withTimeout(SETTLE) { connection.closed.first { it != null } }

        assertEquals(Err(death!!), connection.nameOwner(Bus.NAME))
    }

    @Test
    fun `closing the connection ends its subscriptions too`() = runBlocking<Unit> {
        val connection = connect()
        val rule = MatchRule(iface = IFACE)
        val collected = async(Dispatchers.IO) { connection.signals(rule).toList() }
        delay(200.milliseconds)

        connection.close()

        val ended = withTimeout(SETTLE) { collected.await() }
        assertEquals(listOf<Result<Message.Signal, DBusError>>(Err(DBusError.Disconnected)), ended)
    }

    private suspend fun connect(): DBusConnection =
        DBusConnection.open(bus.socket).assertSuccess().also { opened += it }

    private companion object {
        const val PATH = "/com/fromwau/kortex/Death"
        const val IFACE = "com.fromwau.kortex.Death"
        const val WARM_UP = 1u
        val SETTLE = 5.seconds
    }
}
