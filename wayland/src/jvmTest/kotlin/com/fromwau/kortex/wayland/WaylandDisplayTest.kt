package com.fromwau.kortex.wayland

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.getOrElse
import com.fromwau.kern.result.onSuccess
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

class WaylandDisplayTest {
    @Test
    fun `enumerates the compositor's globals`() {
        val display = WaylandDisplay.connect()
            .getOrElse { error -> fail("no compositor answered on $WAYLAND_DISPLAY: $error") }

        display.use {
            val globals = it.globals
            globals.sortedBy(WaylandGlobal::interfaceName).forEach { global ->
                println("GLOBAL ${global.interfaceName} v${global.version} (name=${global.name})")
            }

            val names = globals.map(WaylandGlobal::interfaceName)
            assertTrue(globals.isNotEmpty(), "the registry advertised nothing at all")
            REQUIRED.forEach { required ->
                assertTrue(required in names, "compositor did not advertise $required")
            }
            assertTrue(
                names.count { name -> name == "wl_output" } >= 1,
                "expected at least one wl_output",
            )
        }
    }

    @Test
    fun `a display named explicitly connects like the default one`() {
        val name = assertNotNull(System.getenv("WAYLAND_DISPLAY"), "WAYLAND_DISPLAY is unset")
        val display = WaylandDisplay.connect(name)
            .getOrElse { error -> fail("no compositor answered on $name: $error") }

        display.use { assertTrue(it.globals.isNotEmpty(), "the registry on $name advertised nothing") }
    }

    @Test
    fun `a display name nothing listens on fails to connect`() {
        val result = WaylandDisplay.connect(NO_SUCH_DISPLAY)
        result.onSuccess { it.close() }

        assertEquals(Err(KortexError.NoCompositorResponse), result)
    }

    @Test
    fun `closing the connection frees its registry listener`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }
        val listener = display.registryListener
        assertTrue(listener.scope().isAlive, "the registry listener was freed while its connection was open")

        display.close()
        assertFalse(listener.scope().isAlive, "the registry listener outlived its connection")
    }

    @Test
    fun `a wait right after a roundtrip returns at once`() {
        val waited = timedWait(deadlineAfterMillis = null, wakeAfterMillis = SAFETY_WAKE_MILLIS) { it.roundtrip() }
        assertTrue(
            waited < PROMPT_MILLIS,
            "a wait right after a roundtrip slept ${waited}ms instead of returning at once",
        )
    }

    @Test
    fun `a wake from another thread ends a wait with no deadline`() {
        val waited = timedWait(deadlineAfterMillis = null, wakeAfterMillis = WAKE_MILLIS)
        assertTrue(
            waited >= WAKE_MILLIS - WAKE_SLACK_MILLIS,
            "a wait with no deadline returned after ${waited}ms, before anything woke it",
        )
    }

    @Test
    fun `a wait with nothing to do ends at its deadline`() {
        val waited = timedWait(deadlineAfterMillis = DEADLINE_MILLIS, wakeAfterMillis = SAFETY_WAKE_MILLIS)
        assertTrue(
            waited in DEADLINE_MILLIS..<DEADLINE_MILLIS + PROMPT_MILLIS,
            "a wait with a ${DEADLINE_MILLIS}ms deadline and nothing to do returned after ${waited}ms",
        )
    }

    /**
     * Times one [WaylandDisplay.awaitWork] on a thread of its own, which owns the connection until it returns,
     * while this thread calls [WaylandDisplay.wake] once [wakeAfterMillis] have passed or the wait has ended.
     * A wake that never lands then fails the test rather than hanging it. A fresh connection owes no pass, so
     * without [setUp] only that wake or the deadline can end the wait.
     */
    private fun timedWait(
        deadlineAfterMillis: Long?,
        wakeAfterMillis: Long,
        setUp: (WaylandDisplay) -> Unit = {},
    ): Long {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }
        setUp(display)
        val waitedNanos = AtomicLong()
        val waiter = Thread(
            {
                val start = System.nanoTime()
                display.awaitWork(deadlineAfterMillis?.let { start + it * NANOS_PER_MILLI })
                waitedNanos.set(System.nanoTime() - start)
            },
            "kortex-wait-test",
        )
        waiter.isDaemon = true
        waiter.start()
        waiter.join(wakeAfterMillis)
        display.wake()
        waiter.join(JOIN_MILLIS)
        // Closing under a wait still inside poll would race it, so a stuck one keeps its connection open.
        if (waiter.isAlive) fail("awaitWork never returned, so its connection stays open for the rest of this test JVM")
        display.close()
        return waitedNanos.get() / NANOS_PER_MILLI
    }

    private companion object {
        val WAYLAND_DISPLAY: String = System.getenv("WAYLAND_DISPLAY") ?: "<unset>"
        val REQUIRED = listOf("wl_compositor", "wl_shm", "wl_seat", "wl_output", "zwlr_layer_shell_v1")
        const val NO_SUCH_DISPLAY = "kortex-no-such-display"

        // Well inside the safety wake, which only a wait that slept through its cue would last until.
        const val PROMPT_MILLIS = 300L
        const val SAFETY_WAKE_MILLIS = 2000L
        const val WAKE_MILLIS = 300L

        // The waiter starts its clock a little after this thread starts counting down to the wake.
        const val WAKE_SLACK_MILLIS = 50L
        const val DEADLINE_MILLIS = 300L
        const val JOIN_MILLIS = 4000L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
