package com.fromwau.kortex.upower

import com.fromwau.kortex.dbus.DBusValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes

/** What UPower's numbers and properties read as. */
class PowerDeviceTest {
    @Test
    fun `the type and state numbers read as the kinds and states UPower defines`() {
        val headset = deviceFrom(PATH, mapOf("Type" to DBusValue.U32(17u), "State" to DBusValue.U32(4u)))

        assertEquals(DeviceKind.Headset, headset.kind)
        assertEquals(BatteryState.FullyCharged, headset.state)
    }

    @Test
    fun `a number UPower adds later reads as unknown rather than failing`() {
        val future = deviceFrom(PATH, mapOf("Type" to DBusValue.U32(99u), "State" to DBusValue.U32(99u)))

        assertEquals(DeviceKind.Unknown, future.kind)
        assertEquals(BatteryState.Unknown, future.state)
    }

    @Test
    fun `an estimate of zero is no estimate, and a real one is seconds`() {
        val device = deviceFrom(PATH, mapOf("TimeToEmpty" to DBusValue.I64(0), "TimeToFull" to DBusValue.I64(1_800)))

        assertNull(device.timeToEmpty)
        assertEquals(30.minutes, device.timeToFull)
    }

    private companion object {
        const val PATH = "/org/freedesktop/UPower/devices/test"
    }
}
