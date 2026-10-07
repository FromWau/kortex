package com.fromwau.kortex.tray

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import com.fromwau.kern.result.getOrElse
import com.fromwau.kortex.dbus.CallRejected
import com.fromwau.kortex.dbus.DBusConnection
import com.fromwau.kortex.dbus.DBusValue
import com.fromwau.kortex.dbus.asInt32
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Every command `:tray` sends, received by an item and a menu this test exports over the session bus.
 *
 * Real bus, kortex on both ends, and nothing on anybody's desktop moves, because the item driven is the
 * test's own. Needs no watcher: a command goes to the item's address directly.
 */
class TrayCommandsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val opened = mutableListOf<DBusConnection>()
    private val received = MutableStateFlow<List<Received>>(emptyList())

    @AfterTest
    fun tearDown() {
        scope.cancel()
        opened.forEach { it.close() }
    }

    @Test
    fun `activate reaches the item with the point it was clicked at`() = runBlocking<Unit> {
        val item = exportedItem()

        assertEquals(Ok(Unit), tray().activate(item, x = 12, y = 34))

        assertEquals(Received(KDE_ITEM, "Activate", listOf(DBusValue.I32(12), DBusValue.I32(34))), next())
    }

    @Test
    fun `secondary activate reaches the item with its point`() = runBlocking<Unit> {
        val item = exportedItem()

        tray().secondaryActivate(item, x = 5, y = 6).assertSuccess()

        assertEquals(Received(KDE_ITEM, "SecondaryActivate", listOf(DBusValue.I32(5), DBusValue.I32(6))), next())
    }

    @Test
    fun `a context menu is asked for at its point`() = runBlocking<Unit> {
        val item = exportedItem()

        tray().contextMenu(item, x = 7, y = 8).assertSuccess()

        assertEquals(Received(KDE_ITEM, "ContextMenu", listOf(DBusValue.I32(7), DBusValue.I32(8))), next())
    }

    @Test
    fun `a scroll sends the delta first and the orientation second`() = runBlocking<Unit> {
        val item = exportedItem()
        val tray = tray()

        tray.scroll(item, delta = -3, orientation = ScrollOrientation.Vertical).assertSuccess()
        assertEquals(Received(KDE_ITEM, "Scroll", listOf(DBusValue.I32(-3), DBusValue.Text("vertical"))), next())

        tray.scroll(item, delta = 2, orientation = ScrollOrientation.Horizontal).assertSuccess()
        assertEquals(Received(KDE_ITEM, "Scroll", listOf(DBusValue.I32(2), DBusValue.Text("horizontal"))), next())
    }

    @Test
    fun `an item that answers only on the freedesktop interface is still reached`() = runBlocking<Unit> {
        val item = exportedItem(answersOn = FREEDESKTOP_ITEM)

        tray().activate(item, x = 1, y = 2).assertSuccess()

        assertEquals(Received(FREEDESKTOP_ITEM, "Activate", listOf(DBusValue.I32(1), DBusValue.I32(2))), next())
    }

    @Test
    fun `a command the item refuses on both interfaces is a bus failure`() = runBlocking<Unit> {
        val item = exportedItem(answersOn = "org.example.NotAnItem")

        tray().activate(item).assertError<TrayError.BusFailed>()
    }

    @Test
    fun `a menu event reaches the application with its entry, its name and its time`() = runBlocking<Unit> {
        val menu = exportedMenu()

        menu.send(id = 5, event = MenuEvent.Clicked, timestamp = 42u).assertSuccess()

        assertEquals(
            Received(
                DBUSMENU,
                "Event",
                listOf(
                    DBusValue.I32(5),
                    DBusValue.Text("clicked"),
                    DBusValue.Variant(DBusValue.Text("")),
                    DBusValue.U32(42u),
                ),
            ),
            next(),
        )
    }

    @Test
    fun `about to show says whether the application changed the menu`() = runBlocking<Unit> {
        val menu = exportedMenu()

        assertEquals(Ok(true), menu.aboutToShow(CHANGES_ON_SHOW))
        assertEquals(Received(DBUSMENU, "AboutToShow", listOf(DBusValue.I32(CHANGES_ON_SHOW))), next())
        assertEquals(Ok(false), menu.aboutToShow(CHANGES_ON_SHOW + 1))
    }

    @Test
    fun `an entry the application asks to activate arrives by its id`() = runBlocking<Unit> {
        val application = session()
        exportMenuOn(application)
        val menu = Menu(session(), application.uniqueName, MENU_PATH, scope)

        val requested = async { menu.activationRequests.first() }
        // Sent until it lands, since nothing says when the collector's match rule has reached the bus.
        val id = withTimeout(SETTLE) {
            while (!requested.isCompleted) {
                application.emit(
                    MENU_PATH,
                    DBUSMENU,
                    "ItemActivationRequested",
                    listOf(DBusValue.I32(9), DBusValue.U32(0u)),
                )
                delay(50.milliseconds)
            }
            requested.await()
        }

        assertEquals(9, id)
    }

    private data class Received(
        val iface: String,
        val member: String,
        val args: List<DBusValue>,
    )

    /** The next call the exported object received that has not been taken yet. */
    private suspend fun next(): Received {
        val arrived = withTimeoutOrNull(SETTLE) { received.first { it.isNotEmpty() } }
            ?: fail("the exported object received nothing within $SETTLE")
        received.update { it.drop(1) }
        return arrived.first()
    }

    private fun tray(): Tray = Tray(session(), scope)

    /** An item that records every call on [answersOn] and refuses every other interface. */
    private fun exportedItem(answersOn: String = KDE_ITEM): ItemAddress {
        val application = session()
        application.export(ItemAddress.DEFAULT_PATH) { call ->
            when (call.iface) {
                answersOn -> {
                    received.update { it + Received(call.iface.orEmpty(), call.member, call.body) }
                    Ok(emptyList())
                }

                else -> Err(CallRejected.unknownMethod(call))
            }
        }
        return ItemAddress(application.uniqueName, ItemAddress.DEFAULT_PATH)
    }

    private fun exportedMenu(): Menu {
        val application = session()
        exportMenuOn(application)
        return Menu(session(), application.uniqueName, MENU_PATH, scope)
    }

    /** A menu that records every call and changes itself only when [CHANGES_ON_SHOW] is about to show. */
    private fun exportMenuOn(application: DBusConnection) {
        application.export(MENU_PATH) { call ->
            if (call.iface != DBUSMENU) return@export Err(CallRejected.unknownMethod(call))
            received.update { it + Received(call.iface.orEmpty(), call.member, call.body) }
            when (call.member) {
                "AboutToShow" -> Ok(listOf(DBusValue.Bool(call.body.firstOrNull()?.asInt32 == CHANGES_ON_SHOW)))
                else -> Ok(emptyList())
            }
        }
    }

    private fun session(): DBusConnection = runBlocking {
        DBusConnection
            .session()
            .getOrElse { error -> fail("no session bus answered: $error") }
            .also { opened += it }
    }

    private companion object {
        const val KDE_ITEM = "org.kde.StatusNotifierItem"
        const val FREEDESKTOP_ITEM = "org.freedesktop.StatusNotifierItem"
        const val DBUSMENU = "com.canonical.dbusmenu"
        const val MENU_PATH = "/MenuBar"
        const val CHANGES_ON_SHOW = 7
        val SETTLE = 5.seconds
    }
}
