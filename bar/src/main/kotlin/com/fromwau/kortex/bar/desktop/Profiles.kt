package com.fromwau.kortex.bar.desktop

import com.fromwau.kortex.bar.BarError
import com.fromwau.kortex.bar.state.ProfileEntry
import com.fromwau.kortex.bar.state.Reading
import com.fromwau.kortex.powerprofiles.PowerProfile
import com.fromwau.kortex.powerprofiles.PowerProfilesError
import com.fromwau.kortex.powerprofiles.ProfileState

/** The active profile as the bar names it, and the next one round the profiles the machine offers. */
fun ProfileState.entry(): ProfileEntry = ProfileEntry(
    label = when (active) {
        PowerProfile.PowerSaver -> "SAVER"
        PowerProfile.Balanced -> "BALANCED"
        PowerProfile.Performance -> "PERF"
    },
    next = available
        .takeIf { it.size > 1 }
        ?.let { it[(it.indexOf(active) + 1) % it.size] },
    degraded = degraded.isNotEmpty(),
)

/**
 * Why there is no profile to show. The daemon not running is no widget rather than a failure, since a
 * machine without power-profiles-daemon has no profiles to switch either.
 */
fun PowerProfilesError.entry(): Reading<ProfileEntry?> = when (this) {
    PowerProfilesError.NotConnected -> Reading.Pending
    PowerProfilesError.NotRunning -> Reading.Value(null)
    else -> Reading.Unavailable(BarError.NoProfiles(this))
}
