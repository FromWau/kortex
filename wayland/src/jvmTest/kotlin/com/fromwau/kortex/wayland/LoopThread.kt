package com.fromwau.kortex.wayland

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import com.fromwau.kern.result.getOrElse
import com.fromwau.kortex.compose.LocalKortexSurface
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.fail

/**
 * Runs a real [KortexShell.runEventLoop] on a thread of its own. That thread owns the connection until the loop
 * returns, so a test watches it only through `hyprctl`, snapshot state and [WaylandDisplay.waits].
 */
internal object LoopThread {
    const val APPEAR_MILLIS = 3000L
    const val JOIN_MILLIS = 4000L

    /**
     * Creates a shell of [specs] on a loop thread of its own and runs it there around [block], then calls [end],
     * which has to make the loop return. The loop thread closes the shell as it returns, and the display is closed
     * only once it has, since until then the loop owns it.
     */
    fun run(
        vararg specs: SurfaceSpec,
        end: () -> Unit,
        block: (display: WaylandDisplay, loop: Thread) -> Unit,
    ) {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }
        val created = CompletableFuture<Unit>()
        val loopFailure = AtomicReference<Throwable?>(null)

        // Created on the loop thread, as runSurfaces does: a snapshot pump first runs where its surface is created.
        fun createAndRun() {
            // Caught so a throw reaches the test thread at once, rather than as a bare timeout.
            val creation = try {
                KortexShell.create(display, *specs)
            } catch (thrown: Throwable) {
                created.completeExceptionally(thrown)
                return
            }
            val shell = creation.getOrElse { error ->
                created.completeExceptionally(AssertionError("shell creation failed: $error"))
                return
            }
            created.complete(Unit)
            runCatching { shell.use(KortexShell::runEventLoop) }.onFailure(loopFailure::set)
        }

        val loop = Thread(::createAndRun, "kortex-test-loop")
        loop.isDaemon = true
        loop.start()

        // Set in catch, read in finally: what finally finds attaches to the failure that surfaced first.
        var primary: Throwable? = null
        try {
            awaitCreated(created)
            block(display, loop)
        } catch (thrown: Throwable) {
            primary = thrown
            throw thrown
        } finally {
            end()
            loop.join(JOIN_MILLIS)
            if (!loop.isAlive) display.close()
            val trouble = when {
                loop.isAlive -> AssertionError(
                    "the loop never returned, so its connection, its surfaces and the thread stay live for the " +
                        "rest of this test JVM",
                )
                else -> loopFailure.get()?.let { AssertionError("the loop thread threw", it) }
            }
            if (trouble != null) {
                val existing = primary
                if (existing != null) existing.addSuppressed(trouble) else throw trouble
            }
        }
    }

    /** Polls [condition] until it holds, for at most [timeoutMillis]; the loop thread is the one making it true. */
    fun waitUntil(timeoutMillis: Long = APPEAR_MILLIS, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLI
        while (!condition()) {
            if (System.nanoTime() >= deadline) return false
            Thread.sleep(POLL_MILLIS)
        }
        return true
    }

    /** Polls `hyprctl layers` until [namespace] is there, or gone, as [present] asks, for at most [timeoutMillis]. */
    fun awaitNamespace(namespace: String, present: Boolean, timeoutMillis: Long = APPEAR_MILLIS): Boolean =
        waitUntil(timeoutMillis) { (namespace in Hyprctl.namespaces()) == present }

    /** Waits for the loop thread to create its shell, and fails with the reason if it could not. */
    private fun awaitCreated(created: CompletableFuture<Unit>) {
        try {
            created.get(APPEAR_MILLIS, TimeUnit.MILLISECONDS)
        } catch (failed: ExecutionException) {
            throw failed.cause ?: failed
        }
    }

    private const val POLL_MILLIS = 50L
    private const val NANOS_PER_MILLI = 1_000_000L
}

/** Closes the surface this content is on once [requested] turns true. */
@Composable
internal fun CloseWhen(requested: MutableState<Boolean>) {
    val surface = LocalKortexSurface.current
    val close = requested.value
    LaunchedEffect(close) { if (close) surface.close() }
}

/** What Compose's `GlobalSnapshotManager` prints once its snapshot pumps have run on more than one thread. */
internal const val SNAPSHOT_PUMP_WARNING = "GlobalSnapshotManager: concurrent registrations"

/** Runs [block] with `System.out` captured, and returns what was printed there meanwhile. */
internal fun capturingStdout(block: () -> Unit): String {
    val captured = ByteArrayOutputStream()
    val realOut = System.out
    System.setOut(PrintStream(captured))
    try {
        block()
    } finally {
        System.setOut(realOut)
    }
    return captured.toString()
}
