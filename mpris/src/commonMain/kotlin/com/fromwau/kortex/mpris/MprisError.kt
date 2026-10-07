package com.fromwau.kortex.mpris

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.IError
import com.fromwau.kern.result.Result
import com.fromwau.kortex.dbus.BusState
import com.fromwau.kortex.dbus.DBusError

/** Why [Mpris.players] has no players to give, or why a command did not reach one. */
public sealed interface MprisError : IError {
    /** Nobody is watching yet, or the first read has not come back. */
    public data object NotConnected : MprisError

    /** The bus is not there, for [reason], and the players are read again on their own once it is back. */
    public data class BusDown(public val reason: DBusError) : MprisError

    /** The player names no track, and moving to a position is only accepted for one it names. */
    public data object NoTrack : MprisError

    /** The bus itself failed, or the player refused, and [cause] says how. */
    public data class BusFailed(public val cause: DBusError) : MprisError
}

/** Why there are no players while the bus is in [state]. */
internal fun <T> unavailable(state: BusState.Unavailable): Result<T, MprisError> = when (state) {
    BusState.Connecting -> Err(MprisError.NotConnected)
    is BusState.Down -> busDown(state)
}

internal fun <T> busDown(state: BusState.Down): Result<T, MprisError> = Err(MprisError.BusDown(state.reason))
