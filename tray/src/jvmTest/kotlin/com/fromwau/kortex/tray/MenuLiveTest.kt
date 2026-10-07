package com.fromwau.kortex.tray

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.getOrElse
import com.fromwau.kern.result.getOrNull
import com.fromwau.kortex.dbus.DBusConnection
import com.fromwau.kortex.dbus.SessionBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * The menu of whatever is really in this session's tray.
 *
 * Needs an application in the tray that exports one. The entries it holds are that application's business,
 * so nothing here asserts what they say, only that a real menu reads back as a menu.
 */
class MenuLiveTest {
    @Test
    fun `an item that offers a menu gives one, and an item that does not gives null`() = withTray { tray ->
        val items = tray.settleItems()

        items.forEach { item ->
            val menu = tray.menu(item)
            if (item.menuPath == null) {
                assertEquals(null, menu, "${item.address} has no menu path but handed one back")
            } else {
                assertNotNull(menu, "${item.address} names a menu at ${item.menuPath} and gave none")
            }
        }
    }

    @Test
    fun `a live menu reads back as a tree of entries`() = withTray { tray ->
        val item = tray.itemWithMenu()
        val menu = assertNotNull(tray.menu(item))

        val root = menu.settle()
            ?.getOrElse { error -> fail("${item.address} has a menu that could not be read: $error") }
            ?: fail("the menu never left NotConnected")

        assertEquals(ROOT_ID, root.id, "GetLayout was asked from the root, so the root is what comes back")
        assertTrue(
            root.hasSubmenu || root.children.isNotEmpty(),
            "the root neither holds entries nor says it has any, so there is nothing a bar could draw",
        )
        root.children.forEach { entry ->
            assertTrue(entry.id != ROOT_ID, "an entry took the root's own id, which nothing could address")
            assertTrue(
                entry.isSeparator || entry.label.isNotEmpty() || !entry.icon.isEmpty,
                "entry ${entry.id} is not a separator and has neither a label nor an icon to draw",
            )
        }
    }

    /** A flow always holds a value, and the one before anybody looks must not read as an empty menu. */
    @Test
    fun `a menu nobody is collecting says so rather than saying it is empty`() = withTray { tray ->
        val item = tray.itemWithMenu()

        assertEquals(Err(TrayError.NotConnected), assertNotNull(tray.menu(item)).layout.value)
    }

    private suspend fun Tray.settleItems(): List<TrayItem> =
        withTimeoutOrNull(SETTLE) { items.first { it != Err(TrayError.NotConnected) } }
            ?.getOrElse { error -> fail("the tray could not be read: $error") }
            ?: fail("the tray never left NotConnected")

    /**
     * Waits for an item with a menu rather than taking the first reading, since applications register again
     * one by one whenever the watcher name changes hands, which the test before this one may just have done.
     */
    private suspend fun Tray.itemWithMenu(): TrayItem =
        withTimeoutOrNull(SETTLE) {
            items.mapNotNull { state -> state.getOrNull()?.firstOrNull { it.menuPath != null } }.first()
        } ?: fail("no application in the tray exports a menu; this test needs one that does")

    private suspend fun Menu.settle(): Result<MenuItem, TrayError>? =
        withTimeoutOrNull(SETTLE) { layout.first { it != Err(TrayError.NotConnected) } }

    private fun withTray(body: suspend (Tray) -> Unit) = runBlocking {
        DBusConnection
            .session()
            .getOrElse { error -> fail("no session bus answered: $error") }
            .use { connection ->
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                try {
                    // Serves the registry only where nothing else does, so a menu is readable whether or
                    // not a bar is running. See the same line in TrayLiveTest.
                    TrayWatcher.claim(connection, scope)
                    body(Tray(SessionBus(scope), scope))
                } finally {
                    scope.cancel()
                }
            }
    }

    private companion object {
        val SETTLE = 15.seconds

        /** What `GetLayout` is asked from, and what it answers with. */
        const val ROOT_ID = 0
    }
}
