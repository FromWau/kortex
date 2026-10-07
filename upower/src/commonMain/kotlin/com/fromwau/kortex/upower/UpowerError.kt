package com.fromwau.kortex.upower

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.IError
import com.fromwau.kern.result.Result
import com.fromwau.kortex.dbus.BusState
import com.fromwau.kortex.dbus.DBusError

/** Why [Upower.power] has nothing to give. */
public sealed interface UpowerError : IError {
    /** Nobody is watching yet, or the first read has not come back. */
    public data object NotConnected : UpowerError

    /** The system bus is not there, for [reason], and power is read again on its own once it is back. */
    public data class BusDown(public val reason: DBusError) : UpowerError

    /** UPower is not on the bus: not installed, or between a restart's stop and its start. */
    public data object NotRunning : UpowerError

    /** The bus itself failed, or UPower refused, and [cause] says how. */
    public data class BusFailed(public val cause: DBusError) : UpowerError
}

/** Why there is no power state while the bus is in [state]. */
internal fun <T> unavailable(state: BusState.Unavailable): Result<T, UpowerError> = when (state) {
    BusState.Connecting -> Err(UpowerError.NotConnected)
    is BusState.Down -> Err(UpowerError.BusDown(state.reason))
}
