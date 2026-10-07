package com.fromwau.kortex.dbus

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Where a [SessionBus] is: opening a connection, holding one, or waiting to try again. */
public sealed interface BusState {
    /** A connection is being opened. */
    public data object Connecting : BusState

    /** [connection] is open, and stays the one to use until the state moves on. */
    public data class Up(public val connection: DBusConnection) : BusState

    /** The last connection ended or would not open, for [reason], and the next attempt is [retryIn] away. */
    public data class Down(
        public val reason: DBusError,
        public val retryIn: Duration,
    ) : BusState
}

/**
 * How long a [SessionBus] waits between attempts: [first] after a connection that was up, doubling after
 * every attempt that fails, never longer than [cap].
 */
public data class Backoff(
    public val first: Duration = 100.milliseconds,
    public val cap: Duration = 5.seconds,
) {
    /** The wait after one of [previous] that did not help. */
    public fun after(previous: Duration): Duration = (previous * 2).coerceAtMost(cap)
}

/**
 * The bus as something that stays: a connection while there is one, and a new one each time it is lost.
 *
 * ```kotlin
 * val bus = SessionBus(scope)
 * bus.state.collect { state -> if (state is BusState.Up) use(state.connection) }
 * ```
 *
 * A D-Bus connection cannot be resumed: one that is lost had its own unique name, match rules and names on
 * the bus, and none of them carries over. So this does not mend a connection, it replaces it, and each
 * [BusState.Up] carries a new one that whoever uses it sets up from nothing. Calls in flight when the old one
 * ended failed with its reason and are not sent again.
 *
 * Nothing is opened while nobody collects [state], and the connection is closed when the last collector
 * leaves.
 */
public class SessionBus internal constructor(
    scope: CoroutineScope,
    private val backoff: Backoff,
    private val open: suspend () -> Result<DBusConnection, DBusError>,
) {
    /** Follows the session bus this process was started under. */
    public constructor(
        scope: CoroutineScope,
        backoff: Backoff = Backoff(),
    ) : this(scope, backoff, { DBusConnection.session() })

    /** Where the bus is, starting at [BusState.Connecting]. */
    public val state: StateFlow<BusState> = flow {
        var wait = backoff.first
        while (true) {
            emit(BusState.Connecting)
            when (val opened = open()) {
                is Err -> emit(BusState.Down(opened.error, wait))

                is Ok -> {
                    val connection = opened.value
                    try {
                        emit(BusState.Up(connection))
                        val reason = connection.closed.filterNotNull().first()
                        // It was up, so whatever ended it is news rather than a bus that keeps refusing.
                        wait = backoff.first
                        emit(BusState.Down(reason, wait))
                    } finally {
                        connection.close()
                    }
                }
            }
            delay(wait)
            wait = backoff.after(wait)
        }
    }.stateIn(scope, SharingStarted.WhileSubscribed(), BusState.Connecting)

    public companion object {
        /** Follows the bus listening on [socket], which is how a test points this at a bus of its own. */
        public fun at(
            socket: String,
            scope: CoroutineScope,
            backoff: Backoff = Backoff(),
        ): SessionBus = SessionBus(scope, backoff) { DBusConnection.open(socket) }
    }
}
