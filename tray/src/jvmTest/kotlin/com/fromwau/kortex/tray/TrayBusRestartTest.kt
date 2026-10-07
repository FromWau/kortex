package com.fromwau.kortex.tray

import com.fromwau.kern.result.Result
import com.fromwau.kern.result.assertSuccess
import com.fromwau.kern.result.errorOrNull
import com.fromwau.kern.result.getOrNull
import com.fromwau.kortex.dbus.Backoff
import com.fromwau.kortex.dbus.BusState
import com.fromwau.kortex.dbus.DBusConnection
import com.fromwau.kortex.dbus.NameRequest
import com.fromwau.kortex.dbus.PrivateBus
import com.fromwau.kortex.dbus.SessionBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The tray, its registry and a menu across a `dbus-daemon` the test kills and starts again, which is the
 * session bus restarting under a running shell.
 *
 * A bus of the test's own, so no watcher has to be stopped and nothing on the desktop is touched.
 */
class TrayBusRestartTest {
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
    fun `the registry is served again and the tray read again once the bus is back`() = runBlocking<Unit> {
        val served = TrayWatcher.serve(session, scope).alsoWatched()
        val items = Tray(session, scope).items.alsoWatched()
        served.awaitValue("the registry held") { it.getOrNull() is TrayRegistry.HeldHere }
        val before = registeredItem()
        items.awaitValue("the item on the first bus") { it.holds(before) }

        bus.kill()
        items.awaitValue("the tray saying the bus is down") { it.errorOrNull() is TrayError.BusDown }
        bus.restart()

        served.awaitValue("the registry held on the new bus") { it.getOrNull() is TrayRegistry.HeldHere }
        val after = registeredItem()
        items.awaitValue("the item on the new bus") { it.holds(after) }
    }

    @Test
    fun `a menu is read again once its application is back on the restarted bus`() = runBlocking<Unit> {
        exportMenu("before")
        val layout = Menu(session, MENU_NAME, MENU_PATH, scope).layout.alsoWatched()
        layout.awaitValue("the menu on the first bus") { it.label() == "before" }

        bus.kill()
        layout.awaitValue("the menu saying the bus is down") { it.errorOrNull() is TrayError.BusDown }
        bus.restart()
        exportMenu("after")

        layout.awaitValue("the menu on the new bus") { it.label() == "after" }
    }

    @Test
    fun `a command while the bus is down says so`() = runBlocking<Unit> {
        session.state.alsoWatched().awaitValue("the bus up") { it is BusState.Up }
        bus.kill()
        session.state.awaitValue("the bus down") { it is BusState.Down }

        val sent = Tray(session, scope).activate(ItemAddress(":1.1", ItemAddress.DEFAULT_PATH))

        assertIs<TrayError.BusDown>(sent.errorOrNull(), "sent $sent")
    }

    /** An application holding [MENU_NAME] with a menu on it, as one does again after a bus restart. */
    private suspend fun exportMenu(label: String) {
        val application = connect()
        assertEquals(NameRequest.Held, application.requestName(MENU_NAME).assertSuccess())
        application.exportFakeMenu(MENU_PATH, label)
    }

    /** An item on a connection of its own, registered with whoever holds the watcher name now. */
    private suspend fun registeredItem(): ItemAddress {
        val connection = connect()
        connection.exportFakeItem()
        val address = ItemAddress(connection.uniqueName, ItemAddress.DEFAULT_PATH)
        connection.register(address.toString())
        return address
    }

    private suspend fun connect(): DBusConnection =
        DBusConnection.open(bus.socket).assertSuccess().also { opened += it }

    private fun <T> StateFlow<T>.alsoWatched(): StateFlow<T> = also { flow -> scope.launch { flow.collect {} } }

    private suspend fun <T> StateFlow<T>.awaitValue(
        what: String,
        until: (T) -> Boolean,
    ): T = withTimeoutOrNull(SETTLE) { first(until) } ?: fail("never saw $what within $SETTLE: last was $value")

    private fun Result<List<TrayItem>, TrayError>.holds(address: ItemAddress): Boolean =
        getOrNull().orEmpty().any { it.address == address }

    private fun Result<MenuItem, TrayError>.label(): String? = getOrNull()?.children?.firstOrNull()?.label

    private companion object {
        const val MENU_NAME = "com.fromwau.kortex.TestMenu"
        const val MENU_PATH = "/MenuBar"
        val FAST = Backoff(first = 20.milliseconds, cap = 200.milliseconds)
        val SETTLE = 10.seconds
    }
}
