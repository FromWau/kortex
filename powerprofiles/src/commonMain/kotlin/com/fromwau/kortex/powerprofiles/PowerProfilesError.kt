package com.fromwau.kortex.powerprofiles

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.IError
import com.fromwau.kern.result.Result
import com.fromwau.kortex.dbus.BusState
import com.fromwau.kortex.dbus.DBusError

/** Why [PowerProfiles.state] has nothing to give, or why a switch did not happen. */
public sealed interface PowerProfilesError : IError {
    /** Nobody is watching yet, or the first read has not come back. */
    public data object NotConnected : PowerProfilesError

    /** The system bus is not there, for [reason], and the profiles are read again on their own once it is back. */
    public data class BusDown(public val reason: DBusError) : PowerProfilesError

    /** power-profiles-daemon is not on the bus: not installed, or between a restart's stop and its start. */
    public data object NotRunning : PowerProfilesError

    /** The daemon reports an active profile this version of kortex has no name for. */
    public data class UnknownProfile(public val name: String) : PowerProfilesError

    /** The machine's drivers do not offer [profile], so there is nothing to switch to. */
    public data class Unavailable(public val profile: PowerProfile) : PowerProfilesError

    /** polkit refused the switch, as it does for a session that is not the active local one. */
    public data object NotAuthorized : PowerProfilesError

    /** The bus itself failed, or the daemon refused, and [cause] says how. */
    public data class BusFailed(public val cause: DBusError) : PowerProfilesError
}

/** Why there are no profiles while the bus is in [state]. */
internal fun <T> unavailable(state: BusState.Unavailable): Result<T, PowerProfilesError> = when (state) {
    BusState.Connecting -> Err(PowerProfilesError.NotConnected)
    is BusState.Down -> busDown(state)
}

internal fun <T> busDown(state: BusState.Down): Result<T, PowerProfilesError> =
    Err(PowerProfilesError.BusDown(state.reason))
