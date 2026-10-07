package com.fromwau.kortex.powerprofiles

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.assertSuccess
import com.fromwau.kortex.dbus.SystemBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** The machine's real power-profiles-daemon, only read, never switched. Reading starts it if it is installed. */
class PowerProfilesLiveTest {
    @Test
    fun `the machine's profiles read, and the active one is among those offered`() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val state = withTimeout(10.seconds) {
                PowerProfiles(SystemBus(scope), scope).state.first { it != Err(PowerProfilesError.NotConnected) }
            }.assertSuccess()

            println(state)
            assertTrue(state.active in state.available)
        } finally {
            scope.cancel()
        }
    }
}
