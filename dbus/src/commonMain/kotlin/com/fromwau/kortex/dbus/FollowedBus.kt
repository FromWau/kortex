package com.fromwau.kortex.dbus

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Where a [FollowedBus] is: opening a connection, holding one, or waiting to try again. */
public sealed interface BusState {
    /** Any state with no connection to use. */
    public sealed interface Unavailable : BusState

    /** A connection is being opened. */
    public data object Connecting : Unavailable

    /** [connection] is open, and stays the one to use until the state moves on. */
    public data class Up(public val connection: DBusConnection) : BusState

    /** The last connection ended or would not open, for [reason], and the next attempt is [retryIn] away. */
    public data class Down(
        public val reason: DBusError,
        public val retryIn: Duration,
    ) : Unavailable
}

/**
 * How long a [FollowedBus] waits between attempts: [first] after a connection that was up, doubling after
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
 * [SessionBus] and [SystemBus] are its two kinds, and separate types so that a provider says which bus its
 * service lives on and a caller cannot hand it the other.
 *
 * A D-Bus connection cannot be resumed: one that is lost had its own unique name, match rules and names on
 * the bus, and none of them carries over. So this does not mend a connection, it replaces it, and each
 * [BusState.Up] carries a new one that whoever uses it sets up from nothing. Calls in flight when the old one
 * ended failed with its reason and are not sent again.
 *
 * Nothing is opened while nobody collects [state]. The connection is closed a second after the last
 * collector leaves, so calls made one after another share one connection rather than each closing the one
 * the next would use. Once it is closed the state is [BusState.Connecting] again.
 */
public sealed class FollowedBus(
    scope: CoroutineScope,
    private val backoff: Backoff,
    private val open: suspend () -> Result<DBusConnection, DBusError>,
) {
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
    }.stateIn(
        scope,
        SharingStarted.WhileSubscribed(stopTimeout = LINGER, replayExpiration = Duration.ZERO),
        BusState.Connecting,
    )

    /**
     * [onConnection] for every connection the bus comes up on, each run from nothing, and [unavailable] for
     * the state in between.
     *
     * ```kotlin
     * val items = bus.following({ Err(MyError.NoBus(it)) }) { connection -> readItems(connection) }
     * ```
     *
     * A connection's flow is cancelled the moment the bus moves on from it, so it needs no end of its own.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    public fun <T> following(
        unavailable: (BusState.Unavailable) -> T,
        onConnection: (DBusConnection) -> Flow<T>,
    ): Flow<T> = state.flatMapLatest { state ->
        when (state) {
            is BusState.Unavailable -> flowOf(unavailable(state))
            is BusState.Up -> onConnection(state.connection)
        }
    }

    /**
     * [command] on the connection the bus is up on, or [down] at once while it is down.
     *
     * While a connection is being opened this waits for it. It watches the bus until [command] returns, so
     * the connection is not closed for want of a watcher while it is in use.
     */
    public suspend fun <T> withConnection(
        down: (BusState.Down) -> T,
        command: suspend (DBusConnection) -> T,
    ): T = state
        .filter { it !is BusState.Connecting }
        .map { state ->
            when (state) {
                is BusState.Up -> command(state.connection)
                is BusState.Down -> down(state)
                BusState.Connecting -> error("filtered out above")
            }
        }
        .first()

    private companion object {
        val LINGER = 1.seconds
    }
}

/** The session bus this process was started under, followed across restarts. */
public class SessionBus internal constructor(
    scope: CoroutineScope,
    backoff: Backoff,
    open: suspend () -> Result<DBusConnection, DBusError>,
) : FollowedBus(scope, backoff, open) {
    public constructor(
        scope: CoroutineScope,
        backoff: Backoff = Backoff(),
    ) : this(scope, backoff, { DBusConnection.session() })

    public companion object {
        /** Follows the bus listening on [socket], which is how a test points this at a bus of its own. */
        public fun at(
            socket: String,
            scope: CoroutineScope,
            backoff: Backoff = Backoff(),
        ): SessionBus = SessionBus(scope, backoff) { DBusConnection.open(socket) }
    }
}

/** The machine's system bus, where UPower, NetworkManager, BlueZ and logind live, followed across restarts. */
public class SystemBus internal constructor(
    scope: CoroutineScope,
    backoff: Backoff,
    open: suspend () -> Result<DBusConnection, DBusError>,
) : FollowedBus(scope, backoff, open) {
    public constructor(
        scope: CoroutineScope,
        backoff: Backoff = Backoff(),
    ) : this(scope, backoff, { DBusConnection.system() })

    public companion object {
        /** Follows the bus listening on [socket], which is how a test points this at a bus of its own. */
        public fun at(
            socket: String,
            scope: CoroutineScope,
            backoff: Backoff = Backoff(),
        ): SystemBus = SystemBus(scope, backoff) { DBusConnection.open(socket) }
    }
}
