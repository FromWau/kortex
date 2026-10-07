package com.fromwau.kortex.dbus

import com.fromwau.kern.result.assertSuccess
import com.fromwau.kern.result.getOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** [watching] and [nameOwnerChange] against a `dbus-daemon` of the test's own. */
class WatchingTest {
    private val bus = PrivateBus()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val opened = mutableListOf<DBusConnection>()

    @AfterTest
    fun tearDown() {
        scope.cancel()
        opened.forEach { it.close() }
        bus.close()
    }

    @Test
    fun `a signal sent as soon as the block starts is already being watched for`() = runBlocking<Unit> {
        val watcher = connect()
        val sender = connect()

        val received = withTimeout(SETTLE) {
            watcher.watching<String>(listOf(RULE), ruleFailed = {}) { signals ->
                sender.emit(PATH, IFACE, MEMBER).assertSuccess()
                send(signals.receive().member)
            }.first()
        }

        assertEquals(MEMBER, received)
    }

    @Test
    fun `the flow ends when the block returns, though the connection lives on`() = runBlocking<Unit> {
        val sent = withTimeout(SETTLE) {
            connect().watching<Int>(listOf(RULE), ruleFailed = {}) { send(1) }.toList()
        }

        assertEquals(listOf(1), sent)
    }

    @Test
    fun `the rules are gone once the flow ends`() = runBlocking<Unit> {
        val watcher = connect()
        withTimeout(SETTLE) { watcher.watching<Unit>(listOf(RULE), ruleFailed = {}) {}.toList() }
        val seen = Channel<Message.Signal>(Channel.UNLIMITED)
        val listening = MutableStateFlow(false)
        scope.launch {
            watcher.allSignals
                .onSubscription { listening.value = true }
                .collect { received -> received.getOrNull()?.let { seen.send(it) } }
        }
        withTimeout(SETTLE) { listening.first { it } }

        connect().emit(PATH, IFACE, MEMBER).assertSuccess()

        // A gap is the only way to see a signal not arrive.
        assertNull(withTimeoutOrNull(QUIET) { seen.receive() })
    }

    @Test
    fun `a rule the bus refuses is reported, and the block never runs`() = runBlocking<Unit> {
        val ran = MutableStateFlow(false)

        val sent = withTimeout(SETTLE) {
            connect().watching<DBusError>(
                listOf(MatchRule(path = "not a path")),
                ruleFailed = { send(it) },
            ) { ran.value = true }.toList()
        }

        assertEquals(1, sent.size)
        assertEquals(false, ran.value)
    }

    @Test
    fun `the signals end with the connection, and the block gets to finish`() = runBlocking<Unit> {
        val watcher = connect()
        val started = MutableStateFlow(false)
        val ended = MutableStateFlow(false)
        scope.launch {
            watcher.watching<Unit>(listOf(RULE), ruleFailed = {}) { signals ->
                started.value = true
                signals.consumeEach {}
                ended.value = true
            }.collect {}
        }
        withTimeout(SETTLE) { started.first { it } }

        bus.kill()

        withTimeout(SETTLE) { ended.first { it } }
    }

    @Test
    fun `a name taken and given up reads as who held it before and after`() = runBlocking<Unit> {
        val watcher = connect()
        val holder = connect()
        val changes = MutableStateFlow<List<NameOwnerChange>>(emptyList())
        val started = MutableStateFlow(false)
        scope.launch {
            watcher.watching<NameOwnerChange>(listOf(OWNERS), ruleFailed = {}) { signals ->
                started.value = true
                for (signal in signals) signal.nameOwnerChange?.takeIf { it.name == NAME }?.let { send(it) }
            }.collect { change -> changes.update { it + change } }
        }
        withTimeout(SETTLE) { started.first { it } }

        holder.requestName(NAME).assertSuccess()
        holder.releaseName(NAME).assertSuccess()

        withTimeout(SETTLE) { changes.first { it.size == 2 } }
        assertEquals(
            listOf(
                NameOwnerChange(NAME, oldOwner = null, newOwner = holder.uniqueName),
                NameOwnerChange(NAME, oldOwner = holder.uniqueName, newOwner = null),
            ),
            changes.value,
        )
    }

    @Test
    fun `a name change sent by anyone but the bus is not believed`() {
        val forged = Message.Signal(
            serial = 1u,
            path = Bus.PATH,
            iface = Bus.INTERFACE,
            member = Bus.NAME_OWNER_CHANGED,
            sender = ":1.42",
            body = listOf(DBusValue.Text(NAME), DBusValue.Text(":1.7"), DBusValue.Text("")),
        )

        assertNull(forged.nameOwnerChange)
        assertEquals(NameOwnerChange(NAME, ":1.7", null), forged.copy(sender = Bus.NAME).nameOwnerChange)
    }

    private suspend fun connect(): DBusConnection =
        DBusConnection.open(bus.socket).assertSuccess().also { opened += it }

    private companion object {
        const val PATH = "/com/fromwau/kortex/Watched"
        const val IFACE = "com.fromwau.kortex.Watched"
        const val MEMBER = "Changed"
        const val NAME = "com.fromwau.kortex.Watched"
        val RULE = MatchRule(iface = IFACE, member = MEMBER)
        val OWNERS = MatchRule(sender = Bus.NAME, iface = Bus.INTERFACE, member = Bus.NAME_OWNER_CHANGED)
        val SETTLE = 5.seconds
        val QUIET = 500.milliseconds
    }
}
