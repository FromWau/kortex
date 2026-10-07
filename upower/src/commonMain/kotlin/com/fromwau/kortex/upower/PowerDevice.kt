package com.fromwau.kortex.upower

import com.fromwau.kortex.dbus.DBusValue
import com.fromwau.kortex.dbus.asDouble
import com.fromwau.kortex.dbus.asInt64
import com.fromwau.kortex.dbus.asUInt32
import com.fromwau.kortex.dbus.flag
import com.fromwau.kortex.dbus.text
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** What a power device is, as UPower's `Type` numbers it. */
public enum class DeviceKind(internal val code: UInt) {
    Unknown(0u),
    LinePower(1u),
    Battery(2u),
    Ups(3u),
    Monitor(4u),
    Mouse(5u),
    Keyboard(6u),
    Pda(7u),
    Phone(8u),
    MediaPlayer(9u),
    Tablet(10u),
    Computer(11u),
    GamingInput(12u),
    Pen(13u),
    Touchpad(14u),
    Modem(15u),
    Network(16u),
    Headset(17u),
    Speakers(18u),
    Headphones(19u),
    Video(20u),
    OtherAudio(21u),
    RemoteControl(22u),
    Printer(23u),
    Scanner(24u),
    Camera(25u),
    Wearable(26u),
    Toy(27u),
    BluetoothGeneric(28u),
}

/** Whether a battery is filling or emptying, as UPower's `State` numbers it. */
public enum class BatteryState(internal val code: UInt) {
    Unknown(0u),
    Charging(1u),
    Discharging(2u),
    Empty(3u),
    FullyCharged(4u),
    PendingCharge(5u),
    PendingDischarge(6u),
}

/**
 * One device UPower reports: the machine's own battery, a power supply, or a peripheral with a battery
 * of its own, such as a mouse or a headset.
 */
public data class PowerDevice(
    /** UPower's object path for it, which is what tells two devices of one model apart. */
    public val path: String,
    public val kind: DeviceKind,
    public val model: String,
    /** Whether a battery is there at all; a laptop's empty bay and a power supply's report false. */
    public val isPresent: Boolean,
    public val percentage: Double,
    public val state: BatteryState,
    /** Whether this device powers the machine, which a laptop's battery does and a mouse's does not. */
    public val powersMachine: Boolean,
    /** Null where UPower has no estimate, which it reports as zero. */
    public val timeToEmpty: Duration?,
    /** Null where UPower has no estimate, which it reports as zero. */
    public val timeToFull: Duration?,
    /** The icon name UPower suggests for the device's state, which a theme may carry. */
    public val iconName: String?,
)

/** The machine's power: whether it runs on battery, its combined battery, and every device UPower knows. */
public data class Power(
    public val onBattery: Boolean,
    /** Every battery that powers the machine, as one; null on a machine with none, such as a desktop. */
    public val display: PowerDevice?,
    /** Every device, ordered by path. */
    public val devices: List<PowerDevice>,
)

/** The device at [path] from what `GetAll` gave. */
internal fun deviceFrom(path: String, properties: Map<String, DBusValue>): PowerDevice = PowerDevice(
    path = path,
    kind = DeviceKind.entries.firstOrNull { it.code == properties["Type"]?.asUInt32 } ?: DeviceKind.Unknown,
    model = properties.text("Model").orEmpty(),
    isPresent = properties.flag("IsPresent"),
    percentage = properties["Percentage"]?.asDouble ?: 0.0,
    state = BatteryState.entries.firstOrNull { it.code == properties["State"]?.asUInt32 } ?: BatteryState.Unknown,
    powersMachine = properties.flag("PowerSupply"),
    timeToEmpty = properties["TimeToEmpty"]?.asInt64?.takeIf { it > 0 }?.seconds,
    timeToFull = properties["TimeToFull"]?.asInt64?.takeIf { it > 0 }?.seconds,
    iconName = properties.text("IconName")?.ifBlank { null },
)
