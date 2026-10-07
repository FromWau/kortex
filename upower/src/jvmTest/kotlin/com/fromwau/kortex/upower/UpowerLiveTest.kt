package com.fromwau.kortex.upower

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

/** The machine's real UPower, only read. Needs UPower running, as on any machine with a battery or a wireless mouse. */
class UpowerLiveTest {
    @Test
    fun `the machine's power reads, and every device sits under UPower's own path`() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val power = withTimeout(10.seconds) {
                Upower(SystemBus(scope), scope).power.first { it != Err(UpowerError.NotConnected) }
            }.assertSuccess()

            println("onBattery=${power.onBattery} display=${power.display}")
            power.devices.forEach { println("${it.kind} ${it.model} ${it.percentage}% ${it.state}") }
            assertTrue(power.devices.all { it.path.startsWith("/org/freedesktop/UPower/devices/") })
        } finally {
            scope.cancel()
        }
    }
}
