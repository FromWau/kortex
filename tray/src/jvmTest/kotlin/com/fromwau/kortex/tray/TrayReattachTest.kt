package com.fromwau.kortex.tray

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.getOrElse
import com.fromwau.kern.result.getOrNull
import com.fromwau.kortex.dbus.DBusConnection
import com.fromwau.kortex.dbus.PrivateBus
import com.fromwau.kortex.dbus.SessionBus
import com.fromwau.kortex.dbus.asItems
import com.fromwau.kortex.dbus.asText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The tray and the registry outliving whoever served it, on a bus of the test's own. */
class TrayReattachTest {
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
    fun `a tray started before any watcher reads the first one that takes the name`() = runBlocking<Unit> {
        val tray = Tray(SessionBus.at(bus.socket, scope), scope).alsoWatched()
        tray.items.awaitValue("no watcher yet") { it == Err(TrayError.NoWatcher) }

        claimHere(connect())
        val item = registeredItem()

        tray.items.awaitValue("the item of a watcher that came later") { it.holds(item) }
    }

    @Test
    fun `a tray follows the next watcher after the one it read from dies`() = runBlocking<Unit> {
        val tray = Tray(SessionBus.at(bus.socket, scope), scope).alsoWatched()
        val first = connect()
        claimHere(first)
        val item = registeredItem()
        tray.items.awaitValue("the item of the first watcher") { it.holds(item) }

        first.close()
        tray.items.awaitValue("no watcher once it died") { it == Err(TrayError.NoWatcher) }

        claimHere(connect())
        // What an application does on its own when the name gets a new owner.
        item.connection.register(item.address.toString())

        tray.items.awaitValue("the item of the watcher that replaced it") { it.holds(item) }
    }

    @Test
    fun `serve takes the registry once the shell holding it is gone, and the tray follows`() = runBlocking<Unit> {
        val holder = connect()
        claimHere(holder)
        val bar = SessionBus.at(bus.socket, scope)

        val served = TrayWatcher.serve(bar, scope).alsoWatched()
        val elsewhere = served.awaitValue("the registry held elsewhere") {
            it.getOrNull() is TrayRegistry.HeldElsewhere
        }
        assertEquals(holder.uniqueName, (elsewhere.getOrNull() as TrayRegistry.HeldElsewhere).owner)
        val tray = Tray(bar, scope).alsoWatched()

        holder.close()
        served.awaitValue("the registry taken over") { it.getOrNull() is TrayRegistry.HeldHere }
        val item = registeredItem()

        tray.items.awaitValue("an item registered with the bar's own registry") { it.holds(item) }
    }

    @Test
    fun `once serve holds the registry, stopping gives it back for good`() = runBlocking<Unit> {
        val served = TrayWatcher.serve(SessionBus.at(bus.socket, scope), scope).alsoWatched()
        val held = served.awaitValue("the registry held here") { it.getOrNull() is TrayRegistry.HeldHere }
        val here = held.getOrNull() as TrayRegistry.HeldHere

        assertEquals(Ok(Unit), here.watcher.stop())
        delay(SETTLE_AFTER_STOP)

        assertNull(connect().nameOwner(KDE_WATCHER.service).getOrNull(), "serve took the name back after stop")
    }

    @Test
    fun `serve collected again keeps the items its registry already held`() = runBlocking<Unit> {
        val session = SessionBus.at(bus.socket, scope).alsoKeptUp()
        val served = TrayWatcher.serve(session, scope)
        val first = scope.launch { served.collect {} }
        served.awaitValue("the registry held here") { it.getOrNull() is TrayRegistry.HeldHere }
        val item = registeredItem()
        val reader = connect()
        withTimeoutOrNull(SETTLE) { while (item.address.toString() !in reader.registered()) delay(RETRY) }
            ?: fail("the item never reached the registry")

        first.cancelAndJoin()
        scope.launch { served.collect {} }
        served.awaitValue("the registry held here again") { it.getOrNull() is TrayRegistry.HeldHere }
        delay(RETRY)

        assertContains(reader.registered(), item.address.toString(), "collecting serve again emptied the registry")
    }

    @Test
    fun `an application that leaves while nobody collects serve is still dropped`() = runBlocking<Unit> {
        val session = SessionBus.at(bus.socket, scope).alsoKeptUp()
        val served = TrayWatcher.serve(session, scope)
        val first = scope.launch { served.collect {} }
        served.awaitValue("the registry held here") { it.getOrNull() is TrayRegistry.HeldHere }
        val item = registeredItem()
        val reader = connect()
        withTimeoutOrNull(SETTLE) { while (item.address.toString() !in reader.registered()) delay(RETRY) }
            ?: fail("the item never reached the registry")

        first.cancelAndJoin()
        // The sharing stops what it serves a moment after its last collector, not with it.
        delay(RETRY)
        item.connection.close()

        withTimeoutOrNull(SETTLE) { while (item.address.toString() in reader.registered()) delay(RETRY) }
            ?: fail("the registry kept ${item.address} after its application left the bus")
    }

    /**
     * Keeps this bus's connection open with nothing else collected, as a bar's tray does, so a test can take
     * away only the registry's collector. The connection would otherwise close a second after it, and the
     * name with it.
     */
    private fun SessionBus.alsoKeptUp(): SessionBus = also { session -> scope.launch { session.state.collect {} } }

    private suspend fun DBusConnection.registered(): List<String> =
        property(KDE_WATCHER.service, WATCHER_PATH, KDE_WATCHER.iface, REGISTERED_ITEMS)
            .getOrElse { fail("the registry could not be read: $it") }
            .asItems
            ?.mapNotNull { it.asText }
            .orEmpty()

    /** A connection to the test's bus, closed after the test unless the test closes it first. */
    private fun connect(): DBusConnection = runBlocking {
        DBusConnection
            .open(bus.socket)
            .getOrElse { error -> fail("the private bus did not answer: $error") }
            .also { opened += it }
    }

    private suspend fun claimHere(connection: DBusConnection) {
        val registry = TrayWatcher.claim(connection, scope).getOrElse { fail("the bus would not answer: $it") }
        assertIs<TrayRegistry.HeldHere>(registry)
    }

    /** An item on a connection of its own, registered with whoever holds the watcher name now. */
    private suspend fun registeredItem(): FakeItem {
        val connection = connect()
        connection.exportFakeItem()
        val address = ItemAddress(connection.uniqueName, ItemAddress.DEFAULT_PATH)
        connection.register(address.toString())
        return FakeItem(connection, address)
    }

    /** Keeps [Tray.items] collected, so it runs between the reads a test makes of it. */
    private fun Tray.alsoWatched(): Tray = also { tray -> tray.items.alsoWatched() }

    private fun <T> StateFlow<T>.alsoWatched(): StateFlow<T> = also { flow -> scope.launch { flow.collect {} } }

    private fun Result<List<TrayItem>, TrayError>.holds(item: FakeItem): Boolean =
        getOrNull().orEmpty().any { it.address == item.address }

    private suspend fun <T> StateFlow<T>.awaitValue(
        what: String,
        until: (T) -> Boolean,
    ): T = withTimeoutOrNull(SETTLE) { first(until) } ?: fail("never saw $what within $SETTLE: last was $value")

    private data class FakeItem(
        val connection: DBusConnection,
        val address: ItemAddress,
    )

    private companion object {
        val SETTLE = 5.seconds
        val RETRY = 100.milliseconds

        /** Long enough for a re-claim to have landed, had serve been going to make one. */
        val SETTLE_AFTER_STOP = 1.seconds
    }
}
