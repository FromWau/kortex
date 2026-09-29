package com.fromwau.kortex.tray

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.getOrElse
import com.fromwau.kortex.dbus.DBusConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
    fun `a tray nobody is collecting says so rather than saying it is empty`() = withTray { tray ->
        assertEquals(
            Err(TrayError.NotConnected),
            tray.items.value,
            "an empty list here would read the same as a tray nobody has put anything in",
        )
    }

    @Test
    fun `the tray carries what the watcher is carrying`() = withTray { tray ->
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
    fun `every item offers an icon in one of the two ways the specification has`() = withTray { tray ->
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
    fun `an item carries only data a caller can draw however it likes`() = withTray { tray ->
        val item = (tray.settle() ?: fail("the tray never left NotConnected"))
            .getOrElse { error -> fail("the tray could not be read: $error") }
            .first()

        assertIs<String>(item.id)
        assertIs<TrayIcon>(item.icon)
        item.toolTip?.let { assertIs<String>(it.description) }
        // Pixels stay bytes: an ImageBitmap here would drag a toolkit into a module that has none.
        item.icon.pixmaps.forEach { assertIs<ByteArray>(it.argb) }
    }

    private suspend fun Tray.settle(): Result<List<TrayItem>, TrayError>? =
        withTimeoutOrNull(SETTLE) { items.first { it != Err(TrayError.NotConnected) } }

    private fun withTray(body: suspend (Tray) -> Unit) = runBlocking {
        DBusConnection
            .session()
            .getOrElse { error -> fail("no session bus answered: $error") }
            .use { connection ->
                // Its own scope, because stateIn keeps a coroutine for as long as one is given and a
                // coroutineScope here would wait on it forever.
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                try {
                    body(Tray(connection, scope))
                } finally {
                    scope.cancel()
                }
            }
    }

    private companion object {
        val SETTLE = 15.seconds

        /** ARGB32, which is what the specification says an icon's bytes are. */
        const val BYTES_PER_PIXEL = 4
    }
}
