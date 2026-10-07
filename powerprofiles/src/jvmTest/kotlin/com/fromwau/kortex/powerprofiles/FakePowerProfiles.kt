package com.fromwau.kortex.powerprofiles

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertSuccess
import com.fromwau.kortex.dbus.Bus
import com.fromwau.kortex.dbus.CallRejected
import com.fromwau.kortex.dbus.DBusConnection
import com.fromwau.kortex.dbus.DBusType
import com.fromwau.kortex.dbus.DBusValue
import com.fromwau.kortex.dbus.NameRequest
import com.fromwau.kortex.dbus.asText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlin.test.assertEquals

/**
 * power-profiles-daemon on a connection of its own: one object answering from properties the test sets,
 * switching when asked unless [refuse] says polkit would not let it, and announcing every change.
 */
internal class FakePowerProfiles private constructor(
    val connection: DBusConnection,
    @Volatile var properties: Map<String, DBusValue>,
) {
    @Volatile
    var refuse = false

    /** Leaves every `GetAll` unanswered, so a test can go away in the middle of a read. */
    @Volatile
    var stall = false

    /** Completes once a `GetAll` is being left unanswered. */
    val stalled = CompletableDeferred<Unit>()

    /** Every value written to `ActiveProfile`, in order. */
    val switches: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    private fun serve() {
        connection.export(PATH) { call ->
            when {
                call.iface == Bus.PROPERTIES && call.member == "GetAll" && stall -> {
                    stalled.complete(Unit)
                    awaitCancellation()
                }

                call.iface == Bus.PROPERTIES && call.member == "GetAll" ->
                    Ok(listOf(dictionary(*properties.toList().toTypedArray())))

                call.iface == Bus.PROPERTIES && call.member == "Get" ->
                    properties[call.body.getOrNull(1)?.asText]
                        ?.let { Ok(listOf(DBusValue.Variant(it))) }
                        ?: Err(CallRejected.unknownMethod(call))

                call.iface == Bus.PROPERTIES && call.member == "Set" && refuse ->
                    Err(CallRejected(Bus.ACCESS_DENIED, "Not Authorized: switch-profile"))

                call.iface == Bus.PROPERTIES && call.member == "Set" -> {
                    val value = (call.body.getOrNull(2) as DBusValue.Variant).value
                    switches += value.asText.orEmpty()
                    change("ActiveProfile" to value)
                    Ok(emptyList())
                }

                else -> Err(CallRejected.unknownMethod(call))
            }
        }
    }

    /** Changes properties and says what to, or with [invalidate] only that they changed. */
    suspend fun change(vararg changed: Pair<String, DBusValue>, invalidate: Boolean = false) {
        properties = properties + changed
        val said = if (invalidate) emptyArray() else changed
        val names = if (invalidate) changed.map { DBusValue.Text(it.first) } else emptyList()
        connection.emit(
            PATH,
            Bus.PROPERTIES,
            "PropertiesChanged",
            listOf(DBusValue.Text(SERVICE), dictionary(*said), DBusValue.Sequence(DBusType.Basic.Text, names)),
        ).assertSuccess()
    }

    companion object {
        const val SERVICE = "org.freedesktop.UPower.PowerProfiles"
        const val PATH = "/org/freedesktop/UPower/PowerProfiles"

        /**
         * The daemon on [connection], balanced with all three profiles unless [overrides] says otherwise:
         * exported first and named second, so a reader never finds it half made.
         */
        suspend fun on(connection: DBusConnection, vararg overrides: Pair<String, DBusValue>): FakePowerProfiles =
            FakePowerProfiles(connection, DEFAULTS + overrides).also { fake ->
                fake.serve()
                assertEquals(NameRequest.Held, connection.requestName(SERVICE).assertSuccess())
            }
    }
}

internal fun dictionary(vararg entries: Pair<String, DBusValue>): DBusValue.Sequence = DBusValue.Sequence(
    DBusType.Pair(DBusType.Basic.Text, DBusType.Variant),
    entries.map { (key, value) -> DBusValue.Pair(DBusValue.Text(key), DBusValue.Variant(value)) },
)

/** `Profiles` as the daemon lists it, one dictionary per profile its drivers support. */
internal fun profiles(vararg names: String): DBusValue.Sequence = DBusValue.Sequence(
    DICTIONARY,
    names.map { dictionary("Profile" to DBusValue.Text(it), "Driver" to DBusValue.Text("placeholder")) },
)

/** `ActiveProfileHolds`, from (profile, reason, application id) triples. */
internal fun holds(vararg held: Triple<String, String, String>): DBusValue.Sequence = DBusValue.Sequence(
    DICTIONARY,
    held.map { (profile, reason, application) ->
        dictionary(
            "Profile" to DBusValue.Text(profile),
            "Reason" to DBusValue.Text(reason),
            "ApplicationId" to DBusValue.Text(application),
        )
    },
)

private val DICTIONARY = DBusType.Sequence(DBusType.Pair(DBusType.Basic.Text, DBusType.Variant))

private val DEFAULTS = mapOf(
    "ActiveProfile" to DBusValue.Text("balanced"),
    "PerformanceDegraded" to DBusValue.Text(""),
    "Profiles" to profiles("power-saver", "balanced", "performance"),
    "ActiveProfileHolds" to holds(),
)
