package com.fromwau.kortex.upower

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.getOrElse
import com.fromwau.kern.result.getOrNull
import com.fromwau.kortex.dbus.Bus
import com.fromwau.kortex.dbus.DBusConnection
import com.fromwau.kortex.dbus.DBusError
import com.fromwau.kortex.dbus.DBusValue
import com.fromwau.kortex.dbus.MatchRule
import com.fromwau.kortex.dbus.Message
import com.fromwau.kortex.dbus.SystemBus
import com.fromwau.kortex.dbus.asBoolean
import com.fromwau.kortex.dbus.asDictionary
import com.fromwau.kortex.dbus.asItems
import com.fromwau.kortex.dbus.asObjectPath
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
 * The machine's power, from UPower on the system bus: whether it runs on battery, its battery, and every
 * peripheral with a battery of its own.
 *
 * ```kotlin
 * Upower(bus, scope).power.collect { outcome ->
 *     val mouse = outcome.getOrNull()?.devices?.firstOrNull { it.kind == DeviceKind.Mouse }
 *     show(mouse?.percentage)
 * }
 * ```
 *
 * Nothing runs while nobody collects [power]. It follows [bus] across restarts, and UPower across its own:
 * while UPower is off the bus the value says so, and everything is read again once it is back.
 */
public class Upower(
    private val bus: SystemBus,
    scope: CoroutineScope,
) {
    /** The power state, or why there is none to give. */
    public val power: StateFlow<Result<Power, UpowerError>> = bus
        .following(::unavailable) { connection -> track(connection) }
        .stateIn(scope, SharingStarted.WhileSubscribed(), Err(UpowerError.NotConnected))

    /**
     * The power state on one connection: read once, then kept up from signals.
     *
     * Subscribed before the first read, so a device that appears or changes in between is not missed.
     */
    private fun track(connection: DBusConnection): Flow<Result<Power, UpowerError>> = channelFlow {
        RULES.forEach { rule ->
            connection.addMatch(rule).getOrElse {
                send(Err(UpowerError.BusFailed(it)))
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

        var known: Result<Known, UpowerError> = readAll(connection)
        send(known.snapshot())
        for (signal in signals) {
            val current = known
            val next = when {
                // Whatever was known, UPower leaving or coming back decides what is known next.
                ownerChanged(signal) -> when {
                    cameBack(signal) -> readAll(connection)
                    else -> Err(UpowerError.NotRunning)
                }

                current is Ok -> applied(connection, current.value, signal)
                else -> current
            }
            if (next != known) {
                known = next
                send(known.snapshot())
            }
        }
    }.onCompletion { withContext(NonCancellable) { RULES.forEach { connection.removeMatch(it) } } }

    /** [known] with [signal] folded in. */
    private suspend fun applied(
        connection: DBusConnection,
        known: Known,
        signal: Message.Signal,
    ): Result<Known, UpowerError> = when {
        signal.iface == SERVICE && signal.member == DEVICE_ADDED -> {
            val path = signal.body.firstOrNull()?.asObjectPath
            val properties = path?.let { device(connection, it) }
            Ok(
                if (path == null || properties == null) known
                else known.copy(devices = known.devices + (path to properties)),
            )
        }

        signal.iface == SERVICE && signal.member == DEVICE_REMOVED -> {
            val path = signal.body.firstOrNull()?.asObjectPath
            Ok(known.copy(devices = known.devices - path.orEmpty()))
        }

        signal.iface == Bus.PROPERTIES && signal.member == PROPERTIES_CHANGED -> {
            val changed = signal.body.getOrNull(1)?.asDictionary.orEmpty()
            val invalidated = signal.body.getOrNull(2)?.asItems.orEmpty().isNotEmpty()

            // An invalidated property says it changed without saying to what, so the device is read again.
            suspend fun updated(path: String, bag: Map<String, DBusValue>) =
                if (invalidated) device(connection, path) ?: bag else bag + changed

            Ok(
                when (signal.path) {
                    PATH -> changed["OnBattery"]?.asBoolean?.let { known.copy(onBattery = it) } ?: known
                    DISPLAY -> known.copy(display = updated(DISPLAY, known.display))
                    in known.devices -> {
                        val device = updated(signal.path, known.devices.getValue(signal.path))
                        known.copy(devices = known.devices + (signal.path to device))
                    }

                    else -> known
                },
            )
        }

        else -> Ok(known)
    }

    /** Everything at once, which is the first read and the read after UPower restarts. */
    private suspend fun readAll(connection: DBusConnection): Result<Known, UpowerError> {
        val onBattery = connection
            .property(SERVICE, PATH, SERVICE, "OnBattery")
            .getOrElse { return Err(it.asUpowerError()) }
            .asBoolean == true
        val paths = connection
            .call(SERVICE, PATH, SERVICE, "EnumerateDevices")
            .getOrElse { return Err(it.asUpowerError()) }
            .firstOrNull()
            ?.asItems
            ?.mapNotNull { it.asObjectPath }
            .orEmpty()
        val devices = paths.mapNotNull { path -> device(connection, path)?.let { path to it } }.toMap()
        return Ok(Known(onBattery, device(connection, DISPLAY).orEmpty(), devices))
    }

    /** One device's properties, or null where it went before it could be read. */
    private suspend fun device(connection: DBusConnection, path: String): Map<String, DBusValue>? =
        connection.properties(SERVICE, path, DEVICE).getOrNull()

    /** UPower as read off the bus, kept raw so a change to one property merges into the rest. */
    private data class Known(
        val onBattery: Boolean,
        val display: Map<String, DBusValue>,
        val devices: Map<String, Map<String, DBusValue>>,
    )

    private fun Result<Known, UpowerError>.snapshot(): Result<Power, UpowerError> = when (this) {
        is Err -> this
        is Ok -> Ok(
            Power(
                onBattery = value.onBattery,
                display = deviceFrom(DISPLAY, value.display).takeIf { it.isPresent },
                devices = value.devices.entries.sortedBy { it.key }.map { (path, bag) -> deviceFrom(path, bag) },
            ),
        )
    }

    private companion object {
        const val SERVICE = "org.freedesktop.UPower"
        const val PATH = "/org/freedesktop/UPower"
        const val DISPLAY = "/org/freedesktop/UPower/devices/DisplayDevice"
        const val DEVICE = "org.freedesktop.UPower.Device"
        const val DEVICE_ADDED = "DeviceAdded"
        const val DEVICE_REMOVED = "DeviceRemoved"
        const val PROPERTIES_CHANGED = "PropertiesChanged"

        val RULES = listOf(
            MatchRule(sender = Bus.NAME, iface = Bus.INTERFACE, member = Bus.NAME_OWNER_CHANGED),
            MatchRule(sender = SERVICE, iface = SERVICE),
            MatchRule(sender = SERVICE, iface = Bus.PROPERTIES, member = PROPERTIES_CHANGED, pathNamespace = PATH),
        )

        fun ownerChanged(signal: Message.Signal): Boolean =
            signal.iface == Bus.INTERFACE &&
                signal.member == Bus.NAME_OWNER_CHANGED &&
                signal.body.firstOrNull()?.asText == SERVICE

        /** UPower taking its name again, rather than giving it up. */
        fun cameBack(signal: Message.Signal): Boolean = !signal.body.getOrNull(2)?.asText.isNullOrEmpty()

        fun DBusError.asUpowerError(): UpowerError = when {
            this is DBusError.CallFailed && name == Bus.SERVICE_UNKNOWN -> UpowerError.NotRunning
            else -> UpowerError.BusFailed(this)
        }
    }
}
