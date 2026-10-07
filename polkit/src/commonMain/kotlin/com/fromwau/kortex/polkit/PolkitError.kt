package com.fromwau.kortex.polkit

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.IError
import com.fromwau.kern.result.Result
import com.fromwau.kortex.dbus.BusState
import com.fromwau.kortex.dbus.DBusError

/** Why [PolkitAgent.serve] is not serving this session. */
public sealed interface PolkitError : IError {
    /** The system bus is still being connected, which passes in a moment. */
    public data object NotConnected : PolkitError

    /** The system bus is not there, for [reason], and the agent registers again on its own once it is back. */
    public data class BusDown(public val reason: DBusError) : PolkitError

    /** A call on the bus failed, and [cause] says how. */
    public data class BusFailed(public val cause: DBusError) : PolkitError

    /** logind knows of no graphical session for this user, so there is no session to be the agent for. */
    public data object NoDisplaySession : PolkitError

    /**
     * polkitd would not take this agent, and [name] and [message] are its own words.
     *
     * Most often because another agent already serves the session. polkitd takes one per session and does not
     * announce when that one leaves, so this does not try again on its own.
     */
    public data class Refused(
        public val name: String,
        public val message: String?,
    ) : PolkitError
}

internal fun <T> unavailable(state: BusState.Unavailable): Result<T, PolkitError> = when (state) {
    BusState.Connecting -> Err(PolkitError.NotConnected)
    is BusState.Down -> Err(PolkitError.BusDown(state.reason))
}
