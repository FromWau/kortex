package com.fromwau.kortex.upower

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
import kotlin.test.assertEquals

/**
 * UPower on a connection of its own: the daemon's object and one object per device, answering from
 * properties the test sets, and announcing the changes a test makes.
 */
internal class FakeUpower private constructor(val connection: DBusConnection) {
    @Volatile
    var onBattery = false

    @Volatile
    var display: Map<String, DBusValue> = mapOf("IsPresent" to DBusValue.Bool(false))

    private val devices = java.util.concurrent.ConcurrentHashMap<String, Map<String, DBusValue>>()

    private fun serve() {
        connection.export(PATH) { call ->
            when {
                call.iface == SERVICE && call.member == "EnumerateDevices" -> Ok(
                    listOf(DBusValue.Sequence(DBusType.Basic.ObjectPath, devices.keys.map(DBusValue::ObjectPath))),
                )

                call.iface == Bus.PROPERTIES && call.member == "Get" && call.body.getOrNull(1)?.asText == "OnBattery" ->
                    Ok(listOf(DBusValue.Variant(DBusValue.Bool(onBattery))))

                else -> Err(CallRejected.unknownMethod(call))
            }
        }
        exportDevice(DISPLAY) { display }
    }

    /** Adds a device at [path] and announces it, as UPower does when one is plugged in or pairs. */
    suspend fun add(path: String, properties: Map<String, DBusValue>, announce: Boolean = true) {
        devices[path] = properties
        exportDevice(path) { devices.getValue(path) }
        if (announce) connection.emit(PATH, SERVICE, "DeviceAdded", listOf(DBusValue.ObjectPath(path))).assertSuccess()
    }

    suspend fun remove(path: String) {
        devices -= path
        connection.unexport(path)
        connection.emit(PATH, SERVICE, "DeviceRemoved", listOf(DBusValue.ObjectPath(path))).assertSuccess()
    }

    /** Changes properties at [path] and says what to, or with [invalidate] only that they changed. */
    suspend fun change(path: String, vararg changed: Pair<String, DBusValue>, invalidate: Boolean = false) {
        when (path) {
            DISPLAY -> display = display + changed
            PATH -> changed.toMap()["OnBattery"]?.let { onBattery = (it as DBusValue.Bool).value }
            else -> devices[path] = devices.getValue(path) + changed
        }
        val said = if (invalidate) emptyArray() else changed
        val names = if (invalidate) changed.map { DBusValue.Text(it.first) } else emptyList()
        connection.emit(
            path,
            Bus.PROPERTIES,
            "PropertiesChanged",
            listOf(
                DBusValue.Text(if (path == PATH) SERVICE else DEVICE),
                dictionary(*said),
                DBusValue.Sequence(DBusType.Basic.Text, names),
            ),
        ).assertSuccess()
    }

    private fun exportDevice(path: String, properties: () -> Map<String, DBusValue>) {
        connection.export(path) { call ->
            when {
                call.iface == Bus.PROPERTIES && call.member == "GetAll" ->
                    Ok(listOf(dictionary(*properties().toList().toTypedArray())))

                else -> Err(CallRejected.unknownMethod(call))
            }
        }
    }

    companion object {
        const val SERVICE = "org.freedesktop.UPower"
        const val PATH = "/org/freedesktop/UPower"
        const val DISPLAY = "/org/freedesktop/UPower/devices/DisplayDevice"
        const val DEVICE = "org.freedesktop.UPower.Device"

        /** UPower on [connection]: exported first and named second, so a reader never finds it half made. */
        suspend fun on(
            connection: DBusConnection,
            devices: Map<String, Map<String, DBusValue>> = emptyMap(),
        ): FakeUpower =
            FakeUpower(connection).also { fake ->
                fake.serve()
                devices.forEach { (path, properties) -> fake.add(path, properties, announce = false) }
                assertEquals(NameRequest.Held, connection.requestName(SERVICE).assertSuccess())
            }
    }
}

internal fun dictionary(vararg entries: Pair<String, DBusValue>): DBusValue.Sequence = DBusValue.Sequence(
    DBusType.Pair(DBusType.Basic.Text, DBusType.Variant),
    entries.map { (key, value) -> DBusValue.Pair(DBusValue.Text(key), DBusValue.Variant(value)) },
)

/** A peripheral's properties as UPower reports them: present, its own battery, not powering the machine. */
internal fun peripheral(
    kind: DeviceKind,
    model: String,
    percentage: Double,
    state: BatteryState,
): Map<String, DBusValue> = mapOf(
    "Type" to DBusValue.U32(kind.code),
    "Model" to DBusValue.Text(model),
    "IsPresent" to DBusValue.Bool(true),
    "Percentage" to DBusValue.F64(percentage),
    "State" to DBusValue.U32(state.code),
    "PowerSupply" to DBusValue.Bool(false),
    "TimeToEmpty" to DBusValue.I64(0),
    "TimeToFull" to DBusValue.I64(0),
    "IconName" to DBusValue.Text("battery-good-symbolic"),
)
