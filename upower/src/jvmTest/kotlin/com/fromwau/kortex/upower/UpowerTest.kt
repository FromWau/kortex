package com.fromwau.kortex.upower

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.assertSuccess
import com.fromwau.kern.result.errorOrNull
import com.fromwau.kern.result.getOrNull
import com.fromwau.kortex.dbus.Backoff
import com.fromwau.kortex.dbus.DBusConnection
import com.fromwau.kortex.dbus.DBusValue
import com.fromwau.kortex.dbus.PrivateBus
import com.fromwau.kortex.dbus.SystemBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** UPower as a [FakeUpower] on a `dbus-daemon` of the test's own, followed the way the system bus is. */
class UpowerTest {
    private val bus = PrivateBus()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val opened = mutableListOf<DBusConnection>()
    private val upower = Upower(SystemBus.at(bus.socket, scope, FAST), scope)
    private val power = upower.power.also { flow -> scope.launch { flow.collect {} } }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        opened.forEach { it.close() }
        bus.close()
    }

    @Test
    fun `a desktop's peripherals are listed, and with no battery of its own it has no display device`() =
        runBlocking<Unit> {
            fake(MOUSE to peripheral(DeviceKind.Mouse, "G502", 52.0, BatteryState.Discharging))

            val read = power.awaitPower("the mouse") { it.devices.isNotEmpty() }

            val mouse = read.devices.single()
            assertEquals(MOUSE, mouse.path)
            assertEquals(DeviceKind.Mouse, mouse.kind)
            assertEquals("G502", mouse.model)
            assertEquals(52.0, mouse.percentage)
            assertEquals(BatteryState.Discharging, mouse.state)
            assertEquals(false, mouse.powersMachine)
            assertNull(read.display)
            assertEquals(false, read.onBattery)
        }

    @Test
    fun `a laptop's battery is the display device, with UPower's estimate of how long it lasts`() =
        runBlocking<Unit> {
            val fake = fake()
            fake.onBattery = true
            fake.display = peripheral(DeviceKind.Battery, "", 40.0, BatteryState.Discharging) +
                ("PowerSupply" to DBusValue.Bool(true)) +
                ("TimeToEmpty" to DBusValue.I64(7_200))

            val read = power.awaitPower("the display device") { it.display != null }

            assertEquals(true, read.onBattery)
            assertEquals(40.0, read.display?.percentage)
            assertEquals(2.hours, read.display?.timeToEmpty)
            assertEquals(true, read.display?.powersMachine)
        }

    @Test
    fun `a change UPower announces reaches the device`() = runBlocking<Unit> {
        val fake = fake(MOUSE to peripheral(DeviceKind.Mouse, "G502", 52.0, BatteryState.Discharging))
        power.awaitPower("the mouse") { it.devices.isNotEmpty() }

        fake.change(MOUSE, "Percentage" to DBusValue.F64(51.0), "State" to DBusValue.U32(BatteryState.Charging.code))

        val changed = power.awaitPower("the new percentage") { it.devices.single().percentage == 51.0 }
        assertEquals(BatteryState.Charging, changed.devices.single().state)
    }

    @Test
    fun `a property UPower only says is invalid is read again`() = runBlocking<Unit> {
        val fake = fake(MOUSE to peripheral(DeviceKind.Mouse, "G502", 52.0, BatteryState.Discharging))
        power.awaitPower("the mouse") { it.devices.isNotEmpty() }

        fake.change(MOUSE, "Percentage" to DBusValue.F64(10.0), invalidate = true)

        power.awaitPower("the percentage read again") { it.devices.single().percentage == 10.0 }
    }

    @Test
    fun `going on battery reaches the state`() = runBlocking<Unit> {
        val fake = fake()
        power.awaitPower("the first read") { true }

        fake.change(FakeUpower.PATH, "OnBattery" to DBusValue.Bool(true))

        power.awaitPower("on battery") { it.onBattery }
    }

    @Test
    fun `a device that pairs is added, and one that goes is removed`() = runBlocking<Unit> {
        val fake = fake()
        power.awaitPower("no devices") { it.devices.isEmpty() }

        fake.add(HEADSET, peripheral(DeviceKind.Headset, "WH-1000XM5", 90.0, BatteryState.Unknown))
        power.awaitPower("the headset") { it.devices.map { device -> device.kind } == listOf(DeviceKind.Headset) }
        fake.remove(HEADSET)

        power.awaitPower("the headset gone") { it.devices.isEmpty() }
    }

    /** What Quickshell's UPower never recovers from: the daemon restarting while the bus stays up. */
    @Test
    fun `UPower stopping says so, and starting again is read from nothing`() = runBlocking<Unit> {
        val first = fake(MOUSE to peripheral(DeviceKind.Mouse, "G502", 52.0, BatteryState.Discharging))
        power.awaitPower("the mouse") { it.devices.isNotEmpty() }

        first.connection.close()
        power.awaitValue("UPower gone") { it == Err(UpowerError.NotRunning) }
        fake(HEADSET to peripheral(DeviceKind.Headset, "WH-1000XM5", 90.0, BatteryState.Unknown))

        power.awaitPower("the restarted UPower's devices") { power -> power.devices.map { it.path } == listOf(HEADSET) }
    }

    @Test
    fun `power is read again once the bus is back`() = runBlocking<Unit> {
        fake(MOUSE to peripheral(DeviceKind.Mouse, "G502", 52.0, BatteryState.Discharging))
        power.awaitPower("the mouse") { it.devices.isNotEmpty() }

        bus.kill()
        power.awaitValue("the bus going down") { it.errorOrNull() is UpowerError.BusDown }
        bus.restart()
        fake(MOUSE to peripheral(DeviceKind.Mouse, "G502", 50.0, BatteryState.Discharging))

        power.awaitPower("the mouse on the new bus") { it.devices.singleOrNull()?.percentage == 50.0 }
    }

    @Test
    fun `no UPower on the bus at all is said as such rather than as a bus failure`() = runBlocking<Unit> {
        power.awaitValue("UPower not running") { it == Err(UpowerError.NotRunning) }
    }

    private suspend fun fake(vararg devices: Pair<String, Map<String, DBusValue>>): FakeUpower =
        FakeUpower.on(DBusConnection.open(bus.socket).assertSuccess().also { opened += it }, devices.toMap())

    private suspend fun StateFlow<Result<Power, UpowerError>>.awaitPower(
        what: String,
        until: (Power) -> Boolean,
    ): Power = awaitValue(what) { outcome -> outcome.getOrNull()?.let(until) == true }.getOrNull()!!

    private suspend fun <T> StateFlow<T>.awaitValue(
        what: String,
        until: (T) -> Boolean,
    ): T = withTimeoutOrNull(SETTLE) { first(until) } ?: fail("never saw $what within $SETTLE: last was $value")

    private companion object {
        const val MOUSE = "/org/freedesktop/UPower/devices/mouse_hidpp_battery_0"
        const val HEADSET = "/org/freedesktop/UPower/devices/headset_dev_58_18_62_53_3E_46"
        val FAST = Backoff(first = 20.milliseconds, cap = 200.milliseconds)
        val SETTLE = 10.seconds
    }
}
