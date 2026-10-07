package com.fromwau.kortex.dbus

import com.fromwau.kern.result.Result
import com.fromwau.kern.result.assertSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/** [DBusConnection.chancesToClaim] against a `dbus-daemon` of the test's own. */
class ClaimingANameTest {
    private val bus = PrivateBus()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val opened = mutableListOf<DBusConnection>()

    @AfterTest
    fun tearDown() {
        scope.cancel()
        opened.forEach { it.close() }
        bus.close()
    }

    @Test
    fun `the first chance comes at once, whoever holds the name`() = runBlocking<Unit> {
        assertEquals(NameRequest.Held, connect().requestName(NAME).assertSuccess())

        withTimeout(SETTLE) { connect().chancesToClaim(NAME).first() }.assertSuccess()
    }

    @Test
    fun `another chance comes when the holder lets go, and the claim it gives succeeds`() = runBlocking<Unit> {
        val holder = connect()
        assertEquals(NameRequest.Held, holder.requestName(NAME).assertSuccess())
        val waiting = connect()
        val chances = waiting.recorded()
        withTimeout(SETTLE) { chances.first { it.size == 1 } }
        assertEquals(NameRequest.Taken, waiting.requestName(NAME).assertSuccess())

        holder.releaseName(NAME).assertSuccess()

        withTimeout(SETTLE) { chances.first { it.size == 2 } }
        assertEquals(NameRequest.Held, waiting.requestName(NAME).assertSuccess())
    }

    @Test
    fun `the chances end with the connection`() = runBlocking<Unit> {
        val connection = connect()
        val ended = MutableStateFlow(false)
        scope.launch {
            connection.chancesToClaim(NAME).collect {}
            ended.value = true
        }

        bus.kill()

        withTimeout(SETTLE) { ended.first { it } }
    }

    private fun DBusConnection.recorded(): MutableStateFlow<List<Result<Unit, DBusError>>> {
        val seen = MutableStateFlow<List<Result<Unit, DBusError>>>(emptyList())
        scope.launch { chancesToClaim(NAME).collect { chance -> seen.update { it + chance } } }
        return seen
    }

    private suspend fun connect(): DBusConnection =
        DBusConnection.open(bus.socket).assertSuccess().also { opened += it }

    private companion object {
        const val NAME = "com.fromwau.kortex.Claimed"
        val SETTLE = 5.seconds
    }
}
