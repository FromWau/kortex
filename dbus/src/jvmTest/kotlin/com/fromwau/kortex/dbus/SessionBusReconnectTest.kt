package com.fromwau.kortex.dbus

import com.fromwau.kern.result.assertSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The bus followed across a `dbus-daemon` the test kills and starts again, which is a bus restarting. */
class SessionBusReconnectTest {
    private val bus = PrivateBus()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val states = MutableStateFlow<List<BusState>>(emptyList())

    @AfterTest
    fun tearDown() {
        scope.cancel()
        bus.close()
    }

    @Test
    fun `a bus that is up is Up, with a connection that answers`() = runBlocking<Unit> {
        val up = awaitUp(SessionBus.at(bus.socket, scope, FAST).recorded())

        up.connection.nameOwner(Bus.NAME).assertSuccess()
    }

    @Test
    fun `a bus that dies goes Down with the reason, then Up on a new connection once it is back`() =
        runBlocking<Unit> {
            val session = SessionBus.at(bus.socket, scope, FAST).recorded()
            val first = awaitUp(session)

            bus.kill()
            val down = withTimeout(SETTLE) { session.state.first { it is BusState.Down } }
            bus.restart()
            val second = awaitUp(session)

            assertIs<BusState.Down>(down)
            assertEquals(FAST.first, down.retryIn, "the wait after a connection that was up starts over")
            assertNotSame(first.connection, second.connection, "a lost connection is replaced, not mended")
            assertNotNull(first.connection.closed.value)
            second.connection.nameOwner(Bus.NAME).assertSuccess()
        }

    @Test
    fun `a bus that is not there is tried again, waiting longer each time up to the cap`() = runBlocking<Unit> {
        bus.kill()
        SessionBus.at(bus.socket, scope, FAST).recorded()

        withTimeout(SETTLE) { states.first { downs(it).count { wait -> wait == FAST.cap } >= 2 } }

        val waits = downs(states.value).take(4)
        assertEquals(listOf(20.milliseconds, 40.milliseconds, 80.milliseconds, 80.milliseconds), waits)
    }

    @Test
    fun `after a connection was up, the next wait starts over rather than where the failures left it`() =
        runBlocking<Unit> {
            bus.kill()
            val session = SessionBus.at(bus.socket, scope, FAST).recorded()
            withTimeout(SETTLE) { states.first { downs(it).contains(FAST.cap) } }

            bus.restart()
            awaitUp(session)
            states.value = emptyList()
            bus.kill()

            val down = withTimeout(SETTLE) { states.first { it.any { state -> state is BusState.Down } } }
            assertEquals(FAST.first, downs(down).first())
        }

    @Test
    fun `nothing is opened while nobody watches, and the connection closes when the last watcher leaves`() =
        runBlocking<Unit> {
            val opened = AtomicInteger()
            val session = SessionBus(scope, FAST) {
                opened.incrementAndGet()
                DBusConnection.open(bus.socket)
            }
            delay(200.milliseconds)
            assertEquals(0, opened.get(), "a bus nobody watches opened a connection")

            val watcher = scope.launch { session.state.collect {} }
            val up = awaitUp(session)
            watcher.cancel()

            assertNotNull(withTimeout(SETTLE) { up.connection.closed.first { it != null } })
            assertEquals(1, opened.get())
        }

    @Test
    fun `the wait doubles and stops at the cap`() {
        val backoff = Backoff(first = 100.milliseconds, cap = 5.seconds)

        assertEquals(200.milliseconds, backoff.after(100.milliseconds))
        assertEquals(5.seconds, backoff.after(3.seconds))
        assertEquals(5.seconds, backoff.after(5.seconds))
    }

    /** Keeps [SessionBus.state] collected for the test, recording every state it passes through. */
    private fun SessionBus.recorded(): SessionBus = also { session ->
        scope.launch { session.state.collect { state -> states.update { it + state } } }
    }

    private suspend fun awaitUp(session: SessionBus): BusState.Up =
        withTimeout(SETTLE) { session.state.first { it is BusState.Up } } as BusState.Up

    private fun downs(all: List<BusState>) = all.filterIsInstance<BusState.Down>().map { it.retryIn }

    private companion object {
        val FAST = Backoff(first = 20.milliseconds, cap = 80.milliseconds)
        val SETTLE = 10.seconds
    }
}
