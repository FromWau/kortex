package com.fromwau.kortex.powerprofiles

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.flatMap
import com.fromwau.kern.result.getOrElse
import com.fromwau.kern.result.mapError
import com.fromwau.kortex.dbus.Bus
import com.fromwau.kortex.dbus.DBusConnection
import com.fromwau.kortex.dbus.DBusError
import com.fromwau.kortex.dbus.DBusValue
import com.fromwau.kortex.dbus.MatchRule
import com.fromwau.kortex.dbus.Message
import com.fromwau.kortex.dbus.SystemBus
import com.fromwau.kortex.dbus.asDictionary
import com.fromwau.kortex.dbus.asItems
import com.fromwau.kortex.dbus.asText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The machine's power profiles, from power-profiles-daemon on the system bus, and the switch between them.
 *
 * ```kotlin
 * val profiles = PowerProfiles(bus, scope)
 * profiles.state.collect { outcome -> show(outcome.getOrNull()?.active) }
 * profiles.choose(PowerProfile.PowerSaver)
 * ```
 *
 * Nothing runs while nobody collects [state]. It follows [bus] across restarts, and the daemon across its
 * own: while the daemon is off the bus the value says so, and everything is read again once it is back.
 */
public class PowerProfiles(
    private val bus: SystemBus,
    scope: CoroutineScope,
) {
    /** The profiles, or why there are none to give. */
    public val state: StateFlow<Result<ProfileState, PowerProfilesError>> = bus
        .following(::unavailable) { connection -> track(connection) }
        .stateIn(scope, SharingStarted.WhileSubscribed(), Err(PowerProfilesError.NotConnected))

    /**
     * Makes [profile] the active one, which also cancels every hold an application has on a profile.
     *
     * [state] shows the switch once the daemon announces it.
     *
     * @return [PowerProfilesError.Unavailable] where the machine's drivers do not offer [profile], and
     *   [PowerProfilesError.NotAuthorized] where polkit refuses this session.
     */
    public suspend fun choose(profile: PowerProfile): EmptyResult<PowerProfilesError> =
        bus.withConnection(::busDown) { connection ->
            val offered = connection
                .property(SERVICE, PATH, SERVICE, "Profiles")
                .getOrElse { return@withConnection Err(it.asPowerProfilesError()) }
            if (profile !in offeredFrom(offered)) return@withConnection Err(PowerProfilesError.Unavailable(profile))

            connection
                .setProperty(SERVICE, PATH, SERVICE, "ActiveProfile", DBusValue.Text(profile.wireName))
                .mapError { it.asPowerProfilesError() }
        }

    /**
     * The profiles on one connection: read once, then kept up from signals.
     *
     * Subscribed before the first read, so a change in between is not missed.
     */
    private fun track(connection: DBusConnection): Flow<Result<ProfileState, PowerProfilesError>> = channelFlow {
        RULES.forEach { rule ->
            connection.addMatch(rule).getOrElse {
                send(Err(PowerProfilesError.BusFailed(it)))
                return@channelFlow
            }
        }

        val signals = Channel<Message.Signal>(Channel.UNLIMITED)
        val subscribed = CompletableDeferred<Unit>()
        launch {
            connection.allSignals
                .onSubscription { subscribed.complete(Unit) }
                .transformWhile { received ->
                    if (received is Ok) emit(received.value)
                    received is Ok
                }
                .collect { signals.send(it) }
            // The connection ended, which the bus reports itself, so this pass just ends with it.
            signals.close()
        }
        subscribed.await()

        var known = readAll(connection)
        send(known.flatMap(::profileStateFrom))
        for (signal in signals) {
            val current = known
            val next = when {
                // Whatever was known, the daemon leaving or coming back decides what is known next.
                ownerChanged(signal) -> when {
                    cameBack(signal) -> readAll(connection)
                    else -> Err(PowerProfilesError.NotRunning)
                }

                current is Ok -> applied(connection, current.value, signal)
                else -> current
            }
            if (next != known) {
                known = next
                send(known.flatMap(::profileStateFrom))
            }
        }
    }.onCompletion { withContext(NonCancellable) { RULES.forEach { connection.removeMatch(it) } } }

    /** [known] with [signal] folded in. */
    private suspend fun applied(
        connection: DBusConnection,
        known: Map<String, DBusValue>,
        signal: Message.Signal,
    ): Result<Map<String, DBusValue>, PowerProfilesError> = when {
        signal.iface == Bus.PROPERTIES && signal.member == PROPERTIES_CHANGED && signal.path == PATH -> {
            val changed = signal.body.getOrNull(1)?.asDictionary.orEmpty()
            val invalidated = signal.body.getOrNull(2)?.asItems.orEmpty().isNotEmpty()
            // An invalidated property says it changed without saying to what, so everything is read again.
            if (invalidated) readAll(connection) else Ok(known + changed)
        }

        else -> Ok(known)
    }

    private suspend fun readAll(connection: DBusConnection): Result<Map<String, DBusValue>, PowerProfilesError> =
        connection
            .properties(SERVICE, PATH, SERVICE)
            .mapError { it.asPowerProfilesError() }

    private companion object {
        const val SERVICE = "org.freedesktop.UPower.PowerProfiles"
        const val PATH = "/org/freedesktop/UPower/PowerProfiles"
        const val PROPERTIES_CHANGED = "PropertiesChanged"

        val RULES = listOf(
            MatchRule(sender = Bus.NAME, iface = Bus.INTERFACE, member = Bus.NAME_OWNER_CHANGED),
            MatchRule(sender = SERVICE, iface = Bus.PROPERTIES, member = PROPERTIES_CHANGED, path = PATH),
        )

        fun ownerChanged(signal: Message.Signal): Boolean =
            signal.iface == Bus.INTERFACE &&
                signal.member == Bus.NAME_OWNER_CHANGED &&
                signal.body.firstOrNull()?.asText == SERVICE

        /** The daemon taking its name again, rather than giving it up. */
        fun cameBack(signal: Message.Signal): Boolean = !signal.body.getOrNull(2)?.asText.isNullOrEmpty()

        fun DBusError.asPowerProfilesError(): PowerProfilesError = when {
            this is DBusError.CallFailed && name == Bus.SERVICE_UNKNOWN -> PowerProfilesError.NotRunning
            this is DBusError.CallFailed && name == Bus.ACCESS_DENIED -> PowerProfilesError.NotAuthorized
            else -> PowerProfilesError.BusFailed(this)
        }
    }
}
