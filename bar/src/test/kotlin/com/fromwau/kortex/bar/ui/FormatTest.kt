package com.fromwau.kortex.bar.ui

import com.fromwau.kortex.dbus.DBusError
import com.fromwau.kortex.notification.NotificationError
import com.fromwau.kortex.tray.TrayError
import com.fromwau.kortex.bar.BarError
import kotlin.test.Test
import kotlin.test.assertEquals

/** The one place a typed error becomes words, which is the only place a person reads one. */
class FormatTest {
    @Test
    fun `a notification name already held says which process holds it, not which bus name does`() {
        val taken = NotificationError.AlreadyServed(owner = ":1.1860", pid = 714007, process = "dunst")

        assertEquals("dunst serves", BarError.NotServing(taken).shortly())
    }

    @Test
    fun `a name held by a process nobody could resolve falls back to the bus name`() {
        val taken = NotificationError.AlreadyServed(owner = ":1.1860", pid = null, process = null)

        assertEquals(":1.1860 serves", BarError.NotServing(taken).shortly())
    }

    @Test
    fun `a desktop with no tray watcher says so, rather than saying the tray is empty`() {
        assertEquals("no watcher", BarError.NoTray(TrayError.NoWatcher).shortly())
    }

    @Test
    fun `a bus failure under the tray reads as the bus's failure, not as the tray's`() {
        assertEquals("bus closed", BarError.NoTray(TrayError.BusFailed(DBusError.Disconnected)).shortly())
    }

    @Test
    fun `a session with no bus at all says that, which is the one bus failure a person can act on`() {
        assertEquals("no session bus", BarError.NoTray(TrayError.BusDown(DBusError.NoSessionBus)).shortly())
        assertEquals("no session bus", BarError.NotServing(NotificationError.BusDown(DBusError.NoSessionBus)).shortly())
    }

    @Test
    fun `a protocol failure a bar can do nothing about reads as one sentence rather than eighteen`() {
        assertEquals("bus failed", BarError.NoTray(TrayError.BusDown(DBusError.NestingTooDeep(depth = 64))).shortly())
        assertEquals("bus failed", BarError.NoTray(TrayError.BusFailed(DBusError.TruncatedMessage)).shortly())
    }
}
