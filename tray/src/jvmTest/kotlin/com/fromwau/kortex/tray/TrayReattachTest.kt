package com.fromwau.kortex.tray

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.getOrElse
import com.fromwau.kern.result.getOrNull
import com.fromwau.kortex.dbus.DBusConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * The tray and the registry outliving whoever served it, against the session bus this machine is running.
 *
 * Needs the watcher name free at the start, like the rest of this module's live tests: stop the bar first.
 */
class TrayReattachTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val opened = mutableListOf<DBusConnection>()

    @AfterTest
    fun tearDown() {
        scope.cancel()
        opened.forEach { it.close() }
    }

    @Test
    fun `a tray started before any watcher reads the first one that takes the name`() = runBlocking<Unit> {
        val host = session()
        val tray = Tray(host, scope).alsoWatched()
        tray.items.awaitValue("no watcher yet") { it == Err(TrayError.NoWatcher) }

        claimHere(session())
        val item = registeredItem()

        tray.items.awaitValue("the item of a watcher that came later") { it.holds(item) }
    }

    @Test
    fun `a tray follows the next watcher after the one it read from dies`() = runBlocking<Unit> {
        val tray = Tray(session(), scope).alsoWatched()
        val first = session()
        claimHere(first)
        val item = registeredItem()
        tray.items.awaitValue("the item of the first watcher") { it.holds(item) }

        first.close()
        tray.items.awaitValue("no watcher once it died") { it == Err(TrayError.NoWatcher) }

        claimHere(session())
        // What an application does on its own when the name gets a new owner.
        item.connection.register(item.address.toString())

        tray.items.awaitValue("the item of the watcher that replaced it") { it.holds(item) }
    }

    @Test
    fun `serve takes the registry once the shell holding it is gone, and the tray follows`() = runBlocking<Unit> {
        val holder = session()
        claimHere(holder)
        val bar = session()

        val served = TrayWatcher.serve(bar, scope)
        val elsewhere = assertIs<TrayRegistry.HeldElsewhere>(served.value.getOrNull(), "served ${served.value}")
        assertEquals(holder.uniqueName, elsewhere.owner)
        val tray = Tray(bar, scope).alsoWatched()

        holder.close()
        served.awaitValue("the registry taken over") { it.getOrNull() is TrayRegistry.HeldHere }
        val item = registeredItem()

        tray.items.awaitValue("an item registered with the bar's own registry") { it.holds(item) }
    }

    @Test
    fun `once serve holds the registry, stopping gives it back for good`() = runBlocking<Unit> {
        val bar = session()
        val served = TrayWatcher.serve(bar, scope)
        val here = assertIs<TrayRegistry.HeldHere>(served.value.getOrNull(), "served ${served.value}")

        assertEquals(Ok(Unit), here.watcher.stop())
        delay(SETTLE_AFTER_STOP)

        assertNull(bar.nameOwner(KDE_WATCHER.service).getOrNull(), "serve took the name back after stop")
    }

    /** A connection to the session bus, closed after the test unless the test closes it first. */
    private fun session(): DBusConnection = runBlocking {
        DBusConnection
            .session()
            .getOrElse { error -> fail("no session bus answered: $error") }
            .also { opened += it }
    }

    private suspend fun claimHere(connection: DBusConnection) {
        val registry = TrayWatcher.claim(connection, scope).getOrElse { fail("the bus would not answer: $it") }
        assertIs<TrayRegistry.HeldHere>(registry, "something else holds the watcher name; stop the bar first")
    }

    /** An item on a connection of its own, registered with whoever holds the watcher name now. */
    private suspend fun registeredItem(): FakeItem {
        val connection = session()
        connection.exportFakeItem()
        val address = ItemAddress(connection.uniqueName, ItemAddress.DEFAULT_PATH)
        connection.register(address.toString())
        return FakeItem(connection, address)
    }

    /** Keeps [Tray.items] collected, so it runs between the reads a test makes of it. */
    private fun Tray.alsoWatched(): Tray = also { tray -> scope.launch { tray.items.collect {} } }

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

        /** Long enough for a re-claim to have landed, had serve been going to make one. */
        val SETTLE_AFTER_STOP = 1.seconds
    }
}
