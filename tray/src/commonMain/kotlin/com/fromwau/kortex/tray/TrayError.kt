package com.fromwau.kortex.tray

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.IError
import com.fromwau.kern.result.Result
import com.fromwau.kortex.dbus.BusState
import com.fromwau.kortex.dbus.DBusError

/** Why [Tray.items] is carrying no items. */
public sealed interface TrayError : IError {
    /**
     * Nobody is watching yet, or the first read has not come back.
     *
     * Distinct from [NoWatcher] because the two call for different things: this one passes in a moment and
     * a bar should draw nothing, where [NoWatcher] lasts until something serves the registry and is worth
     * saying out loud.
     */
    public data object NotConnected : TrayError

    /**
     * No status notifier watcher holds a name on this bus, so there is no tray to read.
     *
     * A desktop without one has no system tray at all; a bar is the thing that usually provides the
     * watcher, and on a bare compositor nothing has. [TrayWatcher] is how a shell becomes that bar. The
     * tray reads again as soon as a watcher takes the name, whether for the first time or after the last
     * one left.
     */
    public data object NoWatcher : TrayError

    /** The bus is not there, for [reason], and the tray reads again on its own once it is back. */
    public data class BusDown(public val reason: DBusError) : TrayError

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

/** Why the tray has nothing while the bus is in [state]. */
internal fun <T> unavailable(state: BusState.Unavailable): Result<T, TrayError> = when (state) {
    BusState.Connecting -> Err(TrayError.NotConnected)
    is BusState.Down -> busDown(state)
}

internal fun <T> busDown(state: BusState.Down): Result<T, TrayError> = Err(TrayError.BusDown(state.reason))
