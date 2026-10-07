package com.fromwau.kortex.powerprofiles

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.getOrNull
import com.fromwau.kortex.dbus.DBusValue
import kotlin.test.Test
import kotlin.test.assertEquals

/** What the daemon's properties read as. */
class ProfileStateTest {
    @Test
    fun `every reason performance is degraded is read, including one kortex has no name for`() {
        val state = stateFrom("PerformanceDegraded" to DBusValue.Text("lap-detected,high-operating-temperature,dusty"))

        assertEquals(
            listOf(Degradation.LapDetected, Degradation.HighOperatingTemperature, Degradation.Other("dusty")),
            state?.degraded,
        )
    }

    @Test
    fun `a hold names its profile, its reason and the application holding it`() {
        val state = stateFrom("ActiveProfileHolds" to holds(Triple("performance", "compiling", "org.gnome.Builder")))

        assertEquals(listOf(ProfileHold(PowerProfile.Performance, "compiling", "org.gnome.Builder")), state?.holds)
    }

    @Test
    fun `a profile the daemon offers that kortex cannot name is left out of what is available`() {
        val state = stateFrom("Profiles" to profiles("power-saver", "turbo", "balanced"))

        assertEquals(listOf(PowerProfile.PowerSaver, PowerProfile.Balanced), state?.available)
    }

    @Test
    fun `an active profile kortex cannot name is an error naming it`() {
        assertEquals(
            Err(PowerProfilesError.UnknownProfile("turbo")),
            profileStateFrom(mapOf("ActiveProfile" to DBusValue.Text("turbo"))),
        )
    }

    private fun stateFrom(vararg properties: Pair<String, DBusValue>): ProfileState? =
        profileStateFrom(mapOf("ActiveProfile" to DBusValue.Text("balanced")) + properties).getOrNull()
}
