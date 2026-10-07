package com.fromwau.kortex.bar.desktop

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertSuccess
import com.fromwau.kortex.bar.state.Reading
import com.fromwau.kortex.bar.state.TrayCommand
import com.fromwau.kortex.bar.state.TrayEntry
import com.fromwau.kortex.bar.state.TrayMenuEntry
import com.fromwau.kortex.dbus.Bus
import com.fromwau.kortex.dbus.CallRejected
import com.fromwau.kortex.dbus.DBusConnection
import com.fromwau.kortex.dbus.DBusType
import com.fromwau.kortex.dbus.DBusValue
import com.fromwau.kortex.dbus.PrivateBus
import com.fromwau.kortex.dbus.SessionBus
import com.fromwau.kortex.dbus.asInt32
import com.fromwau.kortex.dbus.asText
import com.fromwau.kortex.notification.ServerInformation
import com.fromwau.kortex.tray.ItemAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** A tray item's menu, read and clicked through the bar's desktop, from an application on a private bus. */
class TrayMenuTest {
    private val bus = PrivateBus()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val desktop = BusDesktop(scope, IDENTITY, SessionBus.at(bus.socket, scope))
    private val heard = MutableStateFlow<List<String>>(emptyList())
    private val opened = mutableListOf<DBusConnection>()

    @AfterTest
    fun tearDown() {
        scope.cancel()
        opened.forEach { it.close() }
        bus.close()
    }

    @Test
    fun `the application is told before its menu is read, and its hidden entries stay hidden`() = runBlocking<Unit> {
        val address = application()

        val entries = withTimeout(SETTLE) {
            desktop.trayMenu(address).first { it is Reading.Value }
        }

        assertEquals(
            Reading.Value(
                listOf(
                    TrayMenuEntry(1, "Open Discord", enabled = true, isSeparator = false, checked = null, emptyList()),
                    TrayMenuEntry(2, "", enabled = true, isSeparator = true, checked = null, emptyList()),
                    TrayMenuEntry(4, "Mute", enabled = true, isSeparator = false, checked = true, emptyList()),
                ),
            ),
            entries,
        )
        assertEquals(listOf("AboutToShow 0", "opened 0"), heard.value.take(2))
    }

    @Test
    fun `a pick reaches the application, and closing the menu says so`() = runBlocking<Unit> {
        val address = application()
        val reading = scope.launch { desktop.trayMenu(address).collect {} }
        withTimeout(SETTLE) { heard.first { "opened 0" in it } }

        desktop.tellTrayItem(address, TrayCommand.MenuEntryClicked(1))
        withTimeout(SETTLE) { heard.first { "clicked 1" in it } }
        reading.cancelAndJoin()

        withTimeout(SETTLE) { heard.first { it.lastOrNull() == "closed 0" } }
    }

    /** Registers an application with a menu, and answers once the bar's tray lists it. */
    private suspend fun application(): ItemAddress {
        val tray = MutableStateFlow<Reading<List<TrayEntry>>>(Reading.Pending)
        scope.launch { desktop.tray.collect { tray.value = it } }

        val app = DBusConnection.open(bus.socket).assertSuccess().also { opened += it }
        app.export(ITEM_PATH) { call ->
            when {
                call.iface == Bus.PROPERTIES && call.member == "GetAll" -> Ok(listOf(itemProperties()))
                else -> Err(CallRejected.unknownMethod(call))
            }
        }
        app.export(MENU_PATH) { call ->
            when (call.member) {
                "GetLayout" -> Ok(listOf(DBusValue.U32(1u), layout()))

                "AboutToShow" -> {
                    heard.update { it + "AboutToShow ${call.body.firstOrNull()?.asInt32}" }
                    Ok(listOf(DBusValue.Bool(false)))
                }

                "Event" -> {
                    heard.update { it + "${call.body.getOrNull(1)?.asText} ${call.body.firstOrNull()?.asInt32}" }
                    Ok(emptyList())
                }

                else -> Err(CallRejected.unknownMethod(call))
            }
        }

        val address = ItemAddress(app.uniqueName, ITEM_PATH)
        withTimeout(SETTLE) {
            while (app.register(app.uniqueName) !is Ok) delay(RETRY)
            tray.first { (it as? Reading.Value)?.value?.any { entry -> entry.address == address } == true }
        }
        return address
    }

    private suspend fun DBusConnection.register(entry: String) = call(
        destination = WATCHER,
        path = "/StatusNotifierWatcher",
        iface = WATCHER,
        member = "RegisterStatusNotifierItem",
        args = listOf(DBusValue.Text(entry)),
    )

    private fun itemProperties(): DBusValue = dictionary(
        "Id" to DBusValue.Text("discord"),
        "Title" to DBusValue.Text("Discord"),
        "Status" to DBusValue.Text("Active"),
        "Menu" to DBusValue.ObjectPath(MENU_PATH),
    )

    private fun layout(): DBusValue = node(
        0,
        mapOf("children-display" to DBusValue.Text("submenu")),
        node(1, mapOf("label" to DBusValue.Text("_Open Discord"))),
        node(2, mapOf("type" to DBusValue.Text("separator"))),
        node(3, mapOf("label" to DBusValue.Text("Hidden"), "visible" to DBusValue.Bool(false))),
        node(
            4,
            mapOf(
                "label" to DBusValue.Text("Mute"),
                "toggle-type" to DBusValue.Text("checkmark"),
                "toggle-state" to DBusValue.I32(1),
            ),
        ),
    )

    private fun node(
        id: Int,
        properties: Map<String, DBusValue>,
        vararg children: DBusValue,
    ): DBusValue = DBusValue.Struct(
        listOf(
            DBusValue.I32(id),
            dictionary(*properties.toList().toTypedArray()),
            DBusValue.Sequence(DBusType.Variant, children.map(DBusValue::Variant)),
        ),
    )

    private fun dictionary(vararg entries: Pair<String, DBusValue>): DBusValue = DBusValue.Sequence(
        DBusType.Pair(DBusType.Basic.Text, DBusType.Variant),
        entries.map { (key, value) -> DBusValue.Pair(DBusValue.Text(key), DBusValue.Variant(value)) },
    )

    private companion object {
        const val WATCHER = "org.kde.StatusNotifierWatcher"
        const val ITEM_PATH = "/StatusNotifierItem"
        const val MENU_PATH = "/MenuBar"
        val SETTLE = 10.seconds
        val RETRY = 50.milliseconds
        val IDENTITY = ServerInformation(name = "kortex-bar-test", vendor = "fromwau", version = "0.1.0")
    }
}
