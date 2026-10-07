package com.fromwau.kortex.bar.desktop

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertSuccess
import com.fromwau.kortex.dbus.DBusConnection
import com.fromwau.kortex.dbus.PrivateBus
import com.fromwau.kortex.dbus.SessionBus
import com.fromwau.kortex.notification.NotificationServer
import com.fromwau.kortex.notification.ServerInformation
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** The names a bar takes on the bus by drawing what it draws, on a `dbus-daemon` of the test's own. */
class BusDesktopTest {
    private val bus = PrivateBus()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val desktop = BusDesktop(scope, IDENTITY, SessionBus.at(bus.socket, scope))

    @AfterTest
    fun tearDown() {
        scope.cancel()
        bus.close()
    }

    @Test
    fun `drawing the tray serves its registry, with nothing collecting the registry itself`() = runBlocking<Unit> {
        desktop.tray.alsoCollected()

        awaitOwned(WATCHER)
    }

    @Test
    fun `showing notifications makes the bar the notification server`() = runBlocking<Unit> {
        desktop.notifications.alsoCollected()

        awaitOwned(NotificationServer.INTERFACE)
    }

    private fun Flow<*>.alsoCollected() {
        scope.launch { collect {} }
    }

    private suspend fun awaitOwned(name: String) {
        val looking = DBusConnection.open(bus.socket).assertSuccess()
        try {
            withTimeout(10.seconds) { while (looking.nameOwner(name) !is Ok) delay(50.milliseconds) }
        } finally {
            looking.close()
        }
    }

    private companion object {
        const val WATCHER = "org.kde.StatusNotifierWatcher"
        val IDENTITY = ServerInformation(name = "kortex-bar-test", vendor = "fromwau", version = "0.1.0")
    }
}
