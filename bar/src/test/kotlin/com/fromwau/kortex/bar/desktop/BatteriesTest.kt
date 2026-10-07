package com.fromwau.kortex.bar.desktop

import com.fromwau.kortex.bar.BarError
import com.fromwau.kortex.bar.state.Reading
import com.fromwau.kortex.dbus.DBusError
import com.fromwau.kortex.upower.BatteryState
import com.fromwau.kortex.upower.DeviceKind
import com.fromwau.kortex.upower.Power
import com.fromwau.kortex.upower.PowerDevice
import com.fromwau.kortex.upower.UpowerError
import kotlin.test.Test
import kotlin.test.assertEquals

/** Which batteries the bar shows, and how it marks them. */
class BatteriesTest {
    @Test
    fun `the machine's battery comes first, and the batteries inside it and the power supply are not repeated`() {
        val power = Power(
            onBattery = true,
            display = device("display", DeviceKind.Battery, 40.0, powersMachine = true),
            devices = listOf(
                device("BAT0", DeviceKind.Battery, 40.0, powersMachine = true),
                device("line_power_AC", DeviceKind.LinePower, 0.0, powersMachine = true),
                device("mouse", DeviceKind.Mouse, 52.0),
            ),
        )

        assertEquals(listOf("BAT", "MOUSE"), power.batteries().map { it.label })
    }

    @Test
    fun `a battery under a fifth and not charging is low, and a charging one is marked instead`() {
        val power = Power(
            onBattery = false,
            display = null,
            devices = listOf(
                device("mouse", DeviceKind.Mouse, 12.0, BatteryState.Discharging),
                device("headset", DeviceKind.Headset, 12.0, BatteryState.Charging),
            ),
        )

        val (mouse, headset) = power.batteries()
        assertEquals(true to false, mouse.low to mouse.charging)
        assertEquals(false to true, headset.low to headset.charging)
    }

    @Test
    fun `no UPower means no batteries rather than a failure, and anything else is said`() {
        assertEquals(Reading.Value(emptyList()), UpowerError.NotRunning.batteries())
        assertEquals(Reading.Pending, UpowerError.NotConnected.batteries())
        val down = UpowerError.BusDown(DBusError.Disconnected)
        assertEquals(Reading.Unavailable(BarError.NoPower(down)), down.batteries())
    }

    private fun device(
        name: String,
        kind: DeviceKind,
        percentage: Double,
        state: BatteryState = BatteryState.Discharging,
        powersMachine: Boolean = false,
    ): PowerDevice = PowerDevice(
        path = "/org/freedesktop/UPower/devices/$name",
        kind = kind,
        model = "",
        isPresent = true,
        percentage = percentage,
        state = state,
        powersMachine = powersMachine,
        timeToEmpty = null,
        timeToFull = null,
        iconName = null,
    )
}
