package com.fromwau.kortex.bar.desktop

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.getOrElse
import com.fromwau.kortex.dbus.DBusConnection
import com.fromwau.kortex.tray.Tray
import com.fromwau.kortex.tray.TrayError
import com.fromwau.kortex.tray.TrayItem
import com.fromwau.kortex.tray.TrayStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * `:tray` against the session bus this machine is actually running, reading only.
 *
 * Nothing here calls `activate`, `secondaryActivate`, `contextMenu`, `scroll` or any `Menu` command: a
 * host that does any of those opens the application's own window or its menu, which is not something a
 * test may do to the session it is running in. Reading properties changes nothing on screen.
 *
 * Every assertion is about what kortex promises rather than about what happens to be in this tray, so it
 * holds on a desktop with a tray, without one, and without a watcher at all. What is in the tray is
 * printed instead, for a reader of the test output.
 */
class TrayLiveTest {
    @Test
    fun `the tray's first value is NotConnected, so a bar can tell nothing-yet from no-tray`() =
        withLiveTray { tray ->
            assertEquals(TrayError.NotConnected, tray.items.value.assertError<TrayError>())
        }

    @Test
    fun `the live tray settles on either items or NoWatcher, and says which`() = withLiveTray { tray ->
        when (val settled = tray.settled()) {
            is Ok -> println("tray: ${settled.value.size} item(s): ${settled.value.map { it.id }}")
            is Err -> assertEquals(
                TrayError.NoWatcher,
                settled.error,
                "a desktop with no tray says NoWatcher; anything else is a failure worth seeing",
            )
        }
    }

    @Test
    fun `every item the watcher lists carries an address and an id a bar can key on`() =
        withLiveTray { tray ->
            tray.items().forEach { item ->
                assertTrue(item.address.service.isNotBlank(), "an item with no service cannot be addressed")
                assertTrue(item.address.path.isNotBlank(), "an item with no path cannot be addressed")
                assertTrue(item.id.isNotBlank(), "an item with no id cannot be told from another")
            }
        }

    /**
     * What the bar is responsible for about an icon, which is no longer what the icon is.
     *
     * Resolving a name through the theme directories and decoding what it finds both belong to `:icons`
     * now, and are tested there. What is left here is the choosing: an item that asked to be noticed is
     * drawn with its attention icon, and every entry carries hover text, since an empty tooltip is worse
     * than none.
     */
    @Test
    fun `every live item carries an icon to draw and something to say on hover`() =
        withLiveTray { tray ->
            tray.items().forEach { item ->
                val entry = item.asEntry()

                assertTrue(entry.hover.isNotBlank(), "${item.id} has a hover with nothing in it")
                assertEquals(item.status == TrayStatus.NeedsAttention, entry.needsAttention)
                val expected = if (entry.needsAttention) item.attentionIcon else item.icon
                assertEquals(expected, entry.icon, "the wrong one of an item's two icons was chosen")
            }
        }

    @Test
    fun `the tray can be read again after the last collector has gone, so rules that came down go back up`() =
        withLiveTray { tray ->
            val once = tray.settled()
            val twice = tray.settled()

            assertEquals(once.ids(), twice.ids(), "a second reading of an unchanged tray is the same tray")
        }

    private fun Result<List<TrayItem>, TrayError>.ids(): List<String> =
        when (this) {
            is Ok -> value.map { item -> item.id }.sorted()
            is Err -> emptyList()
        }

    /** The tray once it has answered something other than "nothing yet". */
    private suspend fun Tray.settled(): Result<List<TrayItem>, TrayError> = withTimeout(TIMEOUT) {
        items.first { outcome -> outcome !is Err || outcome.error != TrayError.NotConnected }
    }

    /** Whatever is in the tray, or nothing where this desktop has no tray to read. */
    private suspend fun Tray.items(): List<TrayItem> = when (val settled = settled()) {
        is Ok -> settled.value
        is Err -> emptyList()
    }
}

/**
 * [block] against the session bus, with the connection and the scope the tray lives on cleaned up after.
 *
 * The scope is cancelled before the connection closes, so the match rules the tray put up come down over
 * a connection that is still open rather than over a socket that has already gone.
 */
private fun withLiveTray(block: suspend (Tray) -> Unit) = runBlocking {
    val bus = DBusConnection.session().getOrElse { error ->
        fail("this test reads the live session bus, and there is none: $error")
    }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    try {
        block(Tray(bus, scope))
    } finally {
        scope.cancel()
        bus.close()
    }
}

private val TIMEOUT = 5.seconds
