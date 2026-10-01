package com.fromwau.kortex.tray

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.getOrElse
import com.fromwau.kern.result.getOrNull
import com.fromwau.kortex.dbus.Bus
import com.fromwau.kortex.dbus.DBusConnection
import com.fromwau.kortex.dbus.DBusValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * The tray against the one this session is running.
 *
 * Needs a status notifier watcher on the bus and at least one application sitting in the tray, which is
 * the same kind of dependency every test in `:wayland` has on a live compositor.
 */
class TrayLiveTest {
    /** A flow always holds a value, so the one it holds before anybody looks has to be honest. */
    @Test
    fun `a tray nobody is collecting says so rather than saying it is empty`() = withTray { tray, _ ->
        assertEquals(
            Err(TrayError.NotConnected),
            tray.items.value,
            "an empty list here would read the same as a tray nobody has put anything in",
        )
    }

    @Test
    fun `the tray carries what the watcher is carrying`() = withTray { tray, _ ->
        val settled = tray.settle() ?: fail("the tray never left NotConnected")
        val items = settled.getOrElse { error -> fail("the tray could not be read: $error") }

        assertTrue(
            items.isNotEmpty(),
            "the watcher listed no items; this test needs an application sitting in the tray",
        )
        items.forEach { item ->
            assertTrue(item.address.service.isNotBlank(), "${item.address} has no connection behind it")
            assertTrue(item.address.path.startsWith("/"), "${item.address} has no object path")
            assertContains(TrayCategory.entries, item.category)
            assertContains(TrayStatus.entries, item.status)
            assertTrue(
                item.id.isNotEmpty() || item.title.isNotEmpty(),
                "${item.address} answered with neither an id nor a title, so nothing identifies it",
            )
        }
    }

    /**
     * An icon is a theme name, or pixels, or both, and a caller is given whichever arrived.
     *
     * Not resolved to one here: the specification prefers the name, and a caller with no icon theme to
     * look it up in wants the pixels even so.
     */
    @Test
    fun `every item offers an icon in one of the two ways the specification has`() = withTray { tray, _ ->
        val items = (tray.settle() ?: fail("the tray never left NotConnected"))
            .getOrElse { error -> fail("the tray could not be read: $error") }

        items.forEach { item ->
            assertTrue(
                !item.icon.isEmpty,
                "${item.address} offered neither an icon name nor any pixels, so nothing can draw it",
            )
            item.icon.pixmaps.forEach { image ->
                assertEquals(
                    image.width * image.height * BYTES_PER_PIXEL,
                    image.argb.size,
                    "${item.address} sent a ${image.width}x${image.height} icon of ${image.argb.size} bytes",
                )
            }
        }
    }

    /** The whole point of the shape: the caller reads data and is handed no widget and no toolkit type. */
    @Test
    fun `an item carries only data a caller can draw however it likes`() = withTray { tray, _ ->
        val item = (tray.settle() ?: fail("the tray never left NotConnected"))
            .getOrElse { error -> fail("the tray could not be read: $error") }
            .first()

        assertIs<String>(item.id)
        assertIs<TrayIcon>(item.icon)
        item.toolTip?.let { assertIs<String>(it.description) }
        // Pixels stay bytes: an ImageBitmap here would drag a toolkit into a module that has none.
        item.icon.pixmaps.forEach { assertIs<ByteArray>(it.argb) }
    }

    /**
     * Only the watcher can take an item out of the tray, because only the watcher's signals are routed here.
     *
     * The rules for the watcher's interface name it as their sender, so the bus resolves that name and
     * routes nothing else under it. Without that, the signal forged here is one any peer on the bus can
     * send, and the tray acts on it.
     */
    @Test
    fun `an unregistration forged by a peer that is not the watcher is not acted on`() =
        withTray { tray, connection ->
            // Collected throughout, and not only read twice. The flow runs while somebody is subscribed, so
            // a tray nobody holds open tears down between the two reads and is listening to nothing when the
            // forgery arrives, which passes this whether or not the sender is checked.
            coroutineScope {
                val holding = launch { tray.items.collect { } }
                try {
                    val before = (tray.settle() ?: fail("the tray never left NotConnected"))
                        .getOrElse { error -> fail("the tray could not be read: $error") }
                        .map { it.address }

                    forgeUnregistration(before.first(), host = connection)

                    val acted = withTimeoutOrNull(FORGERY_WINDOW) {
                        tray.items.first { outcome -> outcome.getOrNull()?.map { it.address } != before }
                    }
                    assertNull(acted, "the tray acted on a signal the watcher did not send: $acted")
                } finally {
                    holding.cancel()
                }
            }
        }

    /**
     * Sends the watcher's own unregistration signal for [entry] from a connection that is not the watcher.
     *
     * Returns once the bus has dealt with it, rather than after a sleep. The bus keeps one sender's messages
     * in order, so the peer's own call answering means the signal has been routed or dropped, and anything
     * routed to [host] reached its socket before that connection's own reply did.
     */
    private suspend fun forgeUnregistration(entry: ItemAddress, host: DBusConnection) {
        val watcher = host.liveWatcher()
        DBusConnection.session().getOrElse { error -> fail("no second connection: $error") }.use { peer ->
            assertEquals(
                Ok(Unit),
                peer.emit(
                    path = WATCHER_PATH,
                    iface = watcher,
                    member = "StatusNotifierItemUnregistered",
                    args = listOf(DBusValue.Text(entry.toString())),
                ),
                "the forged signal could not be sent, so this proves nothing",
            )
            peer.nameOwner(Bus.NAME).getOrElse { error -> fail("the peer lost the bus: $error") }
            host.nameOwner(Bus.NAME).getOrElse { error -> fail("the tray lost the bus: $error") }
        }
    }

    /**
     * Whichever watcher name has an owner, which is the one the tray itself will have settled on.
     *
     * The tray holds a rule for both, and a signal forged on the other one would be ignored for naming an
     * interface this session's tray is not reading, which passes whether or not the sender is checked.
     */
    private suspend fun DBusConnection.liveWatcher(): String = WATCHER_INTERFACES
        .firstOrNull { nameOwner(it).getOrNull() != null }
        ?: fail("no watcher holds either name, yet the tray settled")

    private suspend fun Tray.settle(): Result<List<TrayItem>, TrayError>? =
        withTimeoutOrNull(SETTLE) { items.first { it != Err(TrayError.NotConnected) } }

    private fun withTray(body: suspend (Tray, DBusConnection) -> Unit) = runBlocking {
        DBusConnection
            .session()
            .getOrElse { error -> fail("no session bus answered: $error") }
            .use { connection ->
                // Its own scope, because stateIn keeps a coroutine for as long as one is given and a
                // coroutineScope here would wait on it forever.
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                try {
                    body(Tray(connection, scope), connection)
                } finally {
                    scope.cancel()
                }
            }
    }

    private companion object {
        val SETTLE = 15.seconds

        /** Long enough for a routed signal to cross the tray's own coroutines, the bus having handled it. */
        val FORGERY_WINDOW = 2.seconds

        const val WATCHER_PATH = "/StatusNotifierWatcher"

        /** KDE's first, as the tray tries them. */
        val WATCHER_INTERFACES = listOf("org.kde.StatusNotifierWatcher", "org.freedesktop.StatusNotifierWatcher")

        /** ARGB32, which is what the specification says an icon's bytes are. */
        const val BYTES_PER_PIXEL = 4
    }
}
