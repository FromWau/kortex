package com.fromwau.kortex.powerprofiles

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
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
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** power-profiles-daemon as a [FakePowerProfiles] on a `dbus-daemon` of the test's own. */
class PowerProfilesTest {
    private val bus = PrivateBus()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val opened = mutableListOf<DBusConnection>()
    private val profiles = PowerProfiles(SystemBus.at(bus.socket, scope, FAST), scope)
    private val state = profiles.state.also { flow -> scope.launch { flow.collect {} } }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        opened.forEach { it.close() }
        bus.close()
    }

    @Test
    fun `the active profile and the ones the machine offers are read`() = runBlocking<Unit> {
        fake("Profiles" to profiles("power-saver", "balanced"))

        val read = state.awaitState("the profiles") { true }

        assertEquals(PowerProfile.Balanced, read.active)
        assertEquals(listOf(PowerProfile.PowerSaver, PowerProfile.Balanced), read.available)
        assertEquals(emptyList(), read.degraded)
        assertEquals(emptyList(), read.holds)
    }

    @Test
    fun `a switch the daemon announces reaches the state`() = runBlocking<Unit> {
        val fake = fake()
        state.awaitState("balanced") { it.active == PowerProfile.Balanced }

        fake.change("ActiveProfile" to DBusValue.Text("performance"))

        state.awaitState("performance") { it.active == PowerProfile.Performance }
    }

    @Test
    fun `a property the daemon only says is invalid is read again`() = runBlocking<Unit> {
        val fake = fake()
        state.awaitState("the first read") { true }

        fake.change("PerformanceDegraded" to DBusValue.Text("lap-detected"), invalidate = true)

        state.awaitState("the degradation read again") { it.degraded == listOf(Degradation.LapDetected) }
    }

    @Test
    fun `choosing a profile switches to it, and the switch reaches the state`() = runBlocking<Unit> {
        val fake = fake()
        state.awaitState("the first read") { true }

        assertEquals(Ok(Unit), profiles.choose(PowerProfile.PowerSaver))

        assertEquals(listOf("power-saver"), fake.switches)
        state.awaitState("power saver") { it.active == PowerProfile.PowerSaver }
    }

    @Test
    fun `a profile the machine does not offer is refused before the daemon is asked`() = runBlocking<Unit> {
        val fake = fake("Profiles" to profiles("power-saver", "balanced"))

        assertEquals(
            Err(PowerProfilesError.Unavailable(PowerProfile.Performance)),
            profiles.choose(PowerProfile.Performance),
        )
        assertEquals(emptyList(), fake.switches)
    }

    @Test
    fun `polkit refusing the switch says so`() = runBlocking<Unit> {
        val fake = fake()
        fake.refuse = true

        assertEquals(Err(PowerProfilesError.NotAuthorized), profiles.choose(PowerProfile.PowerSaver))
    }

    @Test
    fun `the daemon stopping says so, and starting again is read from nothing`() = runBlocking<Unit> {
        val first = fake()
        state.awaitState("balanced") { it.active == PowerProfile.Balanced }

        first.connection.close()
        state.awaitValue("the daemon gone") { it == Err(PowerProfilesError.NotRunning) }
        fake("ActiveProfile" to DBusValue.Text("performance"))

        state.awaitState("the restarted daemon's profile") { it.active == PowerProfile.Performance }
    }

    @Test
    fun `the daemon leaving in the middle of a read still says it is not running`() = runBlocking<Unit> {
        val fake = fake()
        state.awaitState("balanced") { it.active == PowerProfile.Balanced }
        fake.stall = true
        fake.change("PerformanceDegraded" to DBusValue.Text(""), invalidate = true)
        withTimeout(SETTLE) { fake.stalled.await() }

        fake.connection.close()

        state.awaitValue("the daemon gone") { it == Err(PowerProfilesError.NotRunning) }
    }

    @Test
    fun `the profiles are read again once the bus is back`() = runBlocking<Unit> {
        fake()
        state.awaitState("balanced") { it.active == PowerProfile.Balanced }

        bus.kill()
        state.awaitValue("the bus going down") { it.errorOrNull() is PowerProfilesError.BusDown }
        bus.restart()
        fake("ActiveProfile" to DBusValue.Text("power-saver"))

        state.awaitState("the profile on the new bus") { it.active == PowerProfile.PowerSaver }
    }

    @Test
    fun `no daemon on the bus at all is said as such, and so is choosing without one`() = runBlocking<Unit> {
        state.awaitValue("the daemon not running") { it == Err(PowerProfilesError.NotRunning) }

        assertEquals(Err(PowerProfilesError.NotRunning), profiles.choose(PowerProfile.PowerSaver))
    }

    @Test
    fun `an active profile kortex cannot name is reported rather than guessed`() = runBlocking<Unit> {
        fake("ActiveProfile" to DBusValue.Text("turbo"))

        state.awaitValue("the unknown profile") { it == Err(PowerProfilesError.UnknownProfile("turbo")) }
    }

    private suspend fun fake(vararg overrides: Pair<String, DBusValue>): FakePowerProfiles = FakePowerProfiles.on(
        DBusConnection.open(bus.socket).assertSuccess().also { opened += it },
        *overrides,
    )

    private suspend fun StateFlow<Result<ProfileState, PowerProfilesError>>.awaitState(
        what: String,
        until: (ProfileState) -> Boolean,
    ): ProfileState = awaitValue(what) { outcome -> outcome.getOrNull()?.let(until) == true }.getOrNull()!!

    private suspend fun <T> StateFlow<T>.awaitValue(
        what: String,
        until: (T) -> Boolean,
    ): T = withTimeoutOrNull(SETTLE) { first(until) } ?: fail("never saw $what within $SETTLE: last was $value")

    private companion object {
        val FAST = Backoff(first = 20.milliseconds, cap = 200.milliseconds)
        val SETTLE = 10.seconds
    }
}
