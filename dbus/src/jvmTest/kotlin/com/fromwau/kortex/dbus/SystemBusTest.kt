package com.fromwau.kortex.dbus

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The machine's real system bus, only read from.
 *
 * logind's name is the proof it is the system bus rather than the session one, since only the system bus
 * carries it, so this needs a systemd machine.
 */
class SystemBusTest {
    @Test
    fun `connecting to the system bus authenticates and is given a unique name`() = runBlocking<Unit> {
        DBusConnection.system().assertSuccess().use { connection ->
            assertTrue(connection.uniqueName.startsWith(":"), "not a unique name: ${connection.uniqueName}")
            assertIs<Ok<String>>(connection.nameOwner(LOGIND), "no logind here, so this is not the system bus")
        }
    }

    @Test
    fun `a followed system bus comes up on a connection that answers`() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val up = withTimeout(10.seconds) { SystemBus(scope).state.first { it is BusState.Up } }

            assertIs<Ok<String>>(assertIs<BusState.Up>(up).connection.nameOwner(LOGIND))
        } finally {
            scope.cancel()
        }
    }

    private companion object {
        const val LOGIND = "org.freedesktop.login1"
    }
}
