package com.fromwau.kortex.tray

import com.fromwau.kern.result.IError
import com.fromwau.kortex.dbus.DBusError

/** Why [Tray.items] is carrying no items. */
public sealed interface TrayError : IError {
    /**
     * Nobody is watching yet, or the first read has not come back.
     *
     * Distinct from [NoWatcher] because the two call for different things: this one passes on its own and
     * a bar should draw nothing, where [NoWatcher] will not pass and is worth saying out loud.
     */
    public data object NotConnected : TrayError

    /**
     * No status notifier watcher holds a name on this bus, so there is no tray to read.
     *
     * A desktop without one has no system tray at all; a bar is the thing that usually provides the
     * watcher, and on a bare compositor nothing has.
     */
    public data object NoWatcher : TrayError

    /** The bus itself failed, and [cause] says how. */
    public data class BusFailed(public val cause: DBusError) : TrayError

    /**
     * An application answered `GetLayout` with something that is not a menu.
     *
     * Its own variant rather than a bus failure, because the message arrived and decoded: what is wrong is
     * the shape inside it, which is the application's doing and not the connection's.
     */
    public data object MenuUnreadable : TrayError
}
