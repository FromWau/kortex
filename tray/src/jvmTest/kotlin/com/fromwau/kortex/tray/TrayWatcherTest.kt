package com.fromwau.kortex.tray

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertSuccess
import com.fromwau.kern.result.errorOrNull
import com.fromwau.kern.result.getOrElse
import com.fromwau.kern.result.getOrNull
import com.fromwau.kortex.dbus.Bus
import com.fromwau.kortex.dbus.DBusConnection
import com.fromwau.kortex.dbus.DBusError
import com.fromwau.kortex.dbus.MatchRule
import com.fromwau.kortex.dbus.PrivateBus
import com.fromwau.kortex.dbus.SessionBus
import com.fromwau.kortex.dbus.asItems
import com.fromwau.kortex.dbus.asText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * kortex as the tray's registry, on a bus of the test's own, so nothing on the desktop has to give up the name.
 *
 * The one these exist for is `a host reads a registry its own process is serving`: a shell that serves
 * and draws makes a property call to a name it owns itself, which the bus loops back through the same
 * connection that is waiting for the reply. Reading the code says that is fine. Only running it proves
 * the pump does not sit waiting on itself.
 */
class TrayWatcherTest {
    private val bus = PrivateBus()

    @AfterTest
    fun tearDown() = bus.close()

    @Test
    fun `a watcher takes the name applications call`() = withWatcher { _, connection ->
        assertEquals(
            connection.uniqueName,
            connection.nameOwner(KDE_WATCHER.service).getOrNull(),
            "the watcher started but something else holds the name",
        )
    }

    /**
     * Not getting the name is an outcome, not a failure, and it says who did.
     *
     * A shell that reads this as an error would stop drawing a tray that works perfectly well through
     * somebody else's registry, which is why both answers are a [TrayRegistry].
     */
    @Test
    fun `a shell that does not get the name is told who did`() = withWatcher { _, held ->
        onSecondConnection { connection, scope ->
            val elsewhere = assertIs<TrayRegistry.HeldElsewhere>(TrayWatcher.claim(connection, scope).assertSuccess())

            assertEquals(held.uniqueName, elsewhere.owner)
            assertEquals(ProcessHandle.current().pid().toInt(), elsewhere.pid, "found the wrong process")
            assertTrue(elsewhere.process?.isNotBlank() == true, "the pid resolved to no process name")
        }
    }

    @Test
    fun `an item that registers is in the registry and in the property a host reads`() =
        withWatcher { watcher, host ->
            onSecondConnection { item, _ ->
                item.register(ItemAddress(item.uniqueName, ItemAddress.DEFAULT_PATH).toString())

                val address = ItemAddress(item.uniqueName, ItemAddress.DEFAULT_PATH)
                watcher.await(address)

                assertContains(host.readRegistry(), address.toString())
            }
        }

    /**
     * The shape the specification does not describe and applications send anyway.
     *
     * An argument that is only an object path names no connection, so the sender is the one thing that
     * says who it belongs to. A watcher that stored the path alone would hand a host an address with no
     * peer behind it.
     */
    @Test
    fun `a registration that is only a path is resolved by whoever sent it`() = withWatcher { watcher, _ ->
        onSecondConnection { item, _ ->
            item.register("/org/ayatana/NotificationItem/probe")

            watcher.await(ItemAddress(item.uniqueName, "/org/ayatana/NotificationItem/probe"))
        }
    }

    /**
     * A second registration of one address is not announced again.
     *
     * Stated as the signal rather than as the size of the registry, because the registry is a set and
     * would hold one either way: asserting on its size would test the standard library rather than this.
     * A host told twice reads an item's properties twice for nothing.
     */
    @Test
    fun `an application that registers twice is announced once`() = withWatcher { watcher, host ->
        onSecondConnection { item, scope ->
            val address = ItemAddress(item.uniqueName, ItemAddress.DEFAULT_PATH)
            val announcements = host.countAnnouncements(address, scope)

            item.register(address.toString())
            watcher.await(address)
            item.register(address.toString())

            // Waits out a window rather than reading at once, since a second announcement would arrive
            // after the registration that caused it, not with it.
            delay(FORGERY_WINDOW)
            assertEquals(1, announcements.value, "the same address was announced more than once")
        }
    }

    /** An application that exits does not unregister first, so the bus leaving is the only notice. */
    @Test
    fun `an item goes when its application leaves the bus`() = withWatcher { watcher, _ ->
        val departed = DBusConnection.open(bus.socket).getOrElse { fail("no connection: $it") }
        val address = ItemAddress(departed.uniqueName, ItemAddress.DEFAULT_PATH)
        departed.register(address.toString())
        watcher.await(address)

        departed.close()

        val forgotten = withTimeoutOrNull(SETTLE) { watcher.registered.first { address !in it } }
        assertTrue(forgotten != null, "the registry kept $address with nobody behind it")
    }

    /**
     * The self-call this whole file exists for: one connection serving the registry and reading it, which
     * is what a shell gets when its tray and its registry follow the same [SessionBus].
     *
     * A deadlock here does not fail an assertion, it times out, which is why the message says so.
     */
    @Test
    fun `a host reads a registry its own process is serving`() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val session = SessionBus.at(bus.socket, scope)
            val served = TrayWatcher.serve(session, scope)
            scope.launch { served.collect {} }
            withTimeoutOrNull(SETTLE) { served.first { it.getOrNull() is TrayRegistry.HeldHere } }
                ?: fail("never held the watcher name, last ${served.value}")
            val tray = Tray(session, scope)

            onSecondConnection { item, _ ->
                item.exportFakeItem()
                val address = ItemAddress(item.uniqueName, ItemAddress.DEFAULT_PATH)
                item.register(address.toString())

                withTimeoutOrNull(SETTLE) {
                    tray.items.first { state -> state.getOrNull().orEmpty().any { it.address == address } }
                } ?: fail("the host never read its own registry, as a self-call deadlock would: ${tray.items.value}")
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `stopping gives the name back, so another bar can have it`() = withWatcher { watcher, connection ->
        assertEquals(Ok(Unit), watcher.stop())

        assertNull(
            connection.nameOwner(KDE_WATCHER.service).getOrNull(),
            "the name is still held after stopping",
        )
    }

    /**
     * A host reading while the registry changes hands finds the watcher or nobody, never the name held with
     * nothing exported behind it, which reads as a broken watcher rather than a missing one.
     */
    @Test
    fun `a host never finds the name held with no watcher behind it`() = runBlocking<Unit> {
        onSecondConnection { host, scope ->
            val broken = MutableStateFlow<DBusError?>(null)
            val reading = scope.launch {
                while (isActive) {
                    val failure = host
                        .property(KDE_WATCHER.service, WATCHER_PATH, KDE_WATCHER.iface, REGISTERED_ITEMS)
                        .errorOrNull()
                    val nobody = (failure as? DBusError.CallFailed)?.name == Bus.SERVICE_UNKNOWN
                    if (failure != null && !nobody) broken.compareAndSet(null, failure)
                }
            }

            onSecondConnection { serving, servingScope ->
                repeat(HANDOVERS) {
                    val registry = TrayWatcher.claim(serving, servingScope).assertSuccess()
                    assertIs<TrayRegistry.HeldHere>(registry).watcher.stop().assertSuccess()
                }
            }
            reading.cancel()

            assertNull(broken.value, "a host read the name between the watcher and nobody")
        }
    }

    /** Waits for one address to be in the registry. */
    private suspend fun TrayWatcher.await(address: ItemAddress) {
        withTimeoutOrNull(SETTLE) { registered.first { address in it } }
            ?: fail("$address never registered within $SETTLE")
    }

    /** Counts how often the watcher announces [address], for a test about announcing rather than storing. */
    private suspend fun DBusConnection.countAnnouncements(
        address: ItemAddress,
        scope: CoroutineScope,
    ): MutableStateFlow<Int> {
        addMatch(MatchRule(iface = KDE_WATCHER.iface, member = ITEM_REGISTERED))
            .getOrElse { fail("could not listen for announcements: $it") }

        val seen = MutableStateFlow(0)
        val subscribed = CompletableDeferred<Unit>()
        scope.launch {
            allSignals
                .onSubscription { subscribed.complete(Unit) }
                .collect { received ->
                    val signal = (received as? Ok)?.value ?: return@collect
                    val announced = signal.member == ITEM_REGISTERED &&
                        signal.body.firstOrNull()?.asText == address.toString()
                    if (announced) seen.update { it + 1 }
                }
        }
        subscribed.await()

        return seen
    }

    private suspend fun DBusConnection.readRegistry(): List<String> =
        property(KDE_WATCHER.service, WATCHER_PATH, KDE_WATCHER.iface, REGISTERED_ITEMS)
            .getOrElse { fail("the registry property could not be read: $it") }
            .asItems
            ?.mapNotNull { it.asText }
            .orEmpty()

    /**
     * A watcher on its own connection and scope for one test.
     *
     * The scope outlives the body rather than being a `coroutineScope`, because the watcher keeps a
     * collector for as long as it is given one and waiting on that would never return.
     */
    private fun withWatcher(body: suspend (TrayWatcher, DBusConnection) -> Unit) = runBlocking {
        DBusConnection
            .open(bus.socket)
            .getOrElse { error -> fail("the private bus did not answer: $error") }
            .use { connection ->
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                try {
                    val registry = TrayWatcher.claim(connection, scope).getOrElse { error ->
                        fail("the bus would not answer a claim for the name: $error")
                    }
                    val here = assertIs<TrayRegistry.HeldHere>(registry)
                    body(here.watcher, connection)
                } finally {
                    scope.cancel()
                }
            }
    }

    /** A second connection, which is what makes a registration arrive from somewhere else. */
    private suspend fun onSecondConnection(body: suspend (DBusConnection, CoroutineScope) -> Unit) {
        DBusConnection
            .open(bus.socket)
            .getOrElse { error -> fail("no second connection: $error") }
            .use { connection ->
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                try {
                    body(connection, scope)
                } finally {
                    scope.cancel()
                }
            }
    }

    private companion object {
        val SETTLE = 5.seconds

        /** Enough claims and stops that a window between the name and the object would be read through. */
        const val HANDOVERS = 50

        /** Long enough for a second announcement to have arrived if the watcher were going to send one. */
        val FORGERY_WINDOW = 1.seconds
    }
}
