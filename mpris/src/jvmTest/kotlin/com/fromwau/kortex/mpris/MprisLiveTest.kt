package com.fromwau.kortex.mpris

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.assertSuccess
import com.fromwau.kortex.dbus.SessionBus
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

/** The players this session really has, only read: nothing is played, paused or moved. */
class MprisLiveTest {
    @Test
    fun `the live players read, and none of them is playerctld`() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val players = withTimeout(10.seconds) {
                Mpris(SessionBus(scope), scope).players.first { it != Err(MprisError.NotConnected) }
            }.assertSuccess()

            println(players.joinToString("\n") { "${it.busName}: ${it.status} ${it.track.title}" })
            assertTrue(players.all { it.busName.startsWith(PLAYER_PREFIX) && !it.busName.endsWith(".playerctld") })
        } finally {
            scope.cancel()
        }
    }

}
