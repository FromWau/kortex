package com.fromwau.kortex.powerprofiles

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kortex.dbus.DBusValue
import com.fromwau.kortex.dbus.asDictionary
import com.fromwau.kortex.dbus.asItems
import com.fromwau.kortex.dbus.text

/** One of the three profiles power-profiles-daemon knows, in the order it lists them. */
public enum class PowerProfile(internal val wireName: String) {
    PowerSaver("power-saver"),
    Balanced("balanced"),
    Performance("performance"),
    ;

    internal companion object {
        fun fromOrNull(wireName: String?): PowerProfile? = entries.firstOrNull { it.wireName == wireName }
    }
}

/** Why the performance profile is running below what it promises. */
public sealed interface Degradation {
    /** The machine is sitting on someone's lap. */
    public data object LapDetected : Degradation

    /** The machine is close to overheating. */
    public data object HighOperatingTemperature : Degradation

    /** A reason this version of kortex has no name for, as the daemon gave it. */
    public data class Other(public val reason: String) : Degradation
}

/** An application keeping a profile active, such as a game holding [PowerProfile.Performance]. */
public data class ProfileHold(
    public val profile: PowerProfile,
    public val reason: String,
    public val applicationId: String,
)

/** The machine's power profiles: which one is active, which it can switch to, and what is holding one. */
public data class ProfileState(
    public val active: PowerProfile,
    /** Every profile the machine's drivers support, in [PowerProfile]'s order. */
    public val available: List<PowerProfile>,
    /** Empty unless [PowerProfile.Performance] is held back, and then every reason it is. */
    public val degraded: List<Degradation>,
    public val holds: List<ProfileHold>,
)

/** The state from what `GetAll` gave, which needs an active profile kortex can name. */
internal fun profileStateFrom(properties: Map<String, DBusValue>): Result<ProfileState, PowerProfilesError> {
    val name = properties.text("ActiveProfile").orEmpty()
    val active = PowerProfile.fromOrNull(name) ?: return Err(PowerProfilesError.UnknownProfile(name))
    return Ok(
        ProfileState(
            active = active,
            available = offeredFrom(properties["Profiles"]),
            degraded = properties
                .text("PerformanceDegraded")
                .orEmpty()
                .split(',')
                .filter { it.isNotBlank() }
                .map(::degradationFrom),
            holds = properties["ActiveProfileHolds"]
                .dictionaries()
                .mapNotNull(::holdFrom),
        ),
    )
}

/** The profiles in `Profiles`, leaving out any kortex cannot name. */
internal fun offeredFrom(profiles: DBusValue?): List<PowerProfile> =
    profiles.dictionaries().mapNotNull { PowerProfile.fromOrNull(it.text("Profile")) }

private fun degradationFrom(reason: String): Degradation = when (reason) {
    "lap-detected" -> Degradation.LapDetected
    "high-operating-temperature" -> Degradation.HighOperatingTemperature
    else -> Degradation.Other(reason)
}

private fun holdFrom(properties: Map<String, DBusValue>): ProfileHold? = ProfileHold(
    profile = PowerProfile.fromOrNull(properties.text("Profile")) ?: return null,
    reason = properties.text("Reason").orEmpty(),
    applicationId = properties.text("ApplicationId").orEmpty(),
)

private fun DBusValue?.dictionaries(): List<Map<String, DBusValue>> =
    this?.asItems.orEmpty().mapNotNull { it.asDictionary }
