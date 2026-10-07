package com.fromwau.kortex.bar.desktop

import com.fromwau.kortex.bar.BarError
import com.fromwau.kortex.bar.state.BatteryEntry
import com.fromwau.kortex.bar.state.Reading
import com.fromwau.kortex.upower.BatteryState
import com.fromwau.kortex.upower.DeviceKind
import com.fromwau.kortex.upower.Power
import com.fromwau.kortex.upower.PowerDevice
import com.fromwau.kortex.upower.UpowerError

/**
 * The batteries worth a place on the bar: the machine's own, as UPower combines them, then every
 * peripheral that has one. A battery that powers the machine is already in the combined one, so it is
 * not listed again, and a power supply has no battery to show.
 */
fun Power.batteries(): List<BatteryEntry> =
    (listOfNotNull(display) + devices.filter { it.isPresent && !it.powersMachine && it.kind != DeviceKind.LinePower })
        .map { device -> device.asEntry() }

/**
 * Why there are no batteries to show. UPower not running is no batteries rather than a failure, since a
 * desktop without it usually has nothing with a battery either.
 */
fun UpowerError.batteries(): Reading<List<BatteryEntry>> = when (this) {
    UpowerError.NotConnected -> Reading.Pending
    UpowerError.NotRunning -> Reading.Value(emptyList())
    else -> Reading.Unavailable(BarError.NoPower(this))
}

private fun PowerDevice.asEntry(): BatteryEntry {
    val charging = state == BatteryState.Charging || state == BatteryState.PendingCharge
    return BatteryEntry(
        path = path,
        label = label(),
        percent = percentage.toInt(),
        charging = charging,
        low = percentage < LOW_PERCENT && !charging && state != BatteryState.FullyCharged,
    )
}

private fun PowerDevice.label(): String = when (kind) {
    DeviceKind.Battery -> "BAT"
    DeviceKind.Mouse -> "MOUSE"
    DeviceKind.Keyboard -> "KBD"
    DeviceKind.Headset, DeviceKind.Headphones -> "HEADSET"
    DeviceKind.GamingInput -> "PAD"
    DeviceKind.Phone -> "PHONE"
    else -> model.ifBlank { kind.name }.uppercase()
}

private const val LOW_PERCENT = 20.0
