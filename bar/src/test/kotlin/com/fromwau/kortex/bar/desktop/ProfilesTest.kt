package com.fromwau.kortex.bar.desktop

import com.fromwau.kortex.bar.BarError
import com.fromwau.kortex.bar.state.Reading
import com.fromwau.kortex.powerprofiles.Degradation
import com.fromwau.kortex.powerprofiles.PowerProfile
import com.fromwau.kortex.powerprofiles.PowerProfilesError
import com.fromwau.kortex.powerprofiles.ProfileState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Which profile the bar names, and which one a click switches to. */
class ProfilesTest {
    @Test
    fun `a click goes round the profiles the machine offers, from the last back to the first`() {
        val all = PowerProfile.entries

        assertEquals(PowerProfile.Performance, state(PowerProfile.Balanced, all).entry().next)
        assertEquals(PowerProfile.PowerSaver, state(PowerProfile.Performance, all).entry().next)
    }

    @Test
    fun `a profile the machine does not offer is skipped`() {
        val offered = listOf(PowerProfile.PowerSaver, PowerProfile.Balanced)

        assertEquals(PowerProfile.PowerSaver, state(PowerProfile.Balanced, offered).entry().next)
    }

    @Test
    fun `a machine with one profile has nothing to switch to`() {
        assertNull(state(PowerProfile.Balanced, listOf(PowerProfile.Balanced)).entry().next)
    }

    @Test
    fun `a degraded performance profile is marked`() {
        val degraded = state(PowerProfile.Performance, PowerProfile.entries)
            .copy(degraded = listOf(Degradation.LapDetected))

        assertEquals(true, degraded.entry().degraded)
    }

    @Test
    fun `no daemon is no widget, and any other failure is said`() {
        assertEquals(Reading.Value(null), PowerProfilesError.NotRunning.entry())
        assertEquals(Reading.Pending, PowerProfilesError.NotConnected.entry())
        assertEquals(
            Reading.Unavailable(BarError.NoProfiles(PowerProfilesError.NotAuthorized)),
            PowerProfilesError.NotAuthorized.entry(),
        )
    }

    private fun state(
        active: PowerProfile,
        available: List<PowerProfile>,
    ): ProfileState = ProfileState(active, available, degraded = emptyList(), holds = emptyList())
}
