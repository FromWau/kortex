package com.fromwau.kortex.wayland

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.getOrElse
import com.fromwau.kern.result.map
import com.fromwau.kortex.compose.KortexPlatform
import java.lang.foreign.MemorySegment

/** One [KortexBar] per connected `wl_output`, created and destroyed as outputs come and go. */
public class KortexShell private constructor(
    private val display: WaylandDisplay,
    private val namespace: String,
    private val height: Dp,
    private val platform: KortexPlatform,
    private val keyboard: KeyboardInteractivity,
    private val content: @Composable () -> Unit,
) : AutoCloseable {

    private val bars = mutableMapOf<Int, ShellBar>()

    // Registry callbacks fire mid-dispatch; mutating `bars` there would race serviceBars() iterating it.
    private val pendingAdds = mutableListOf<WaylandGlobal>()
    private val pendingRemoves = mutableListOf<Int>()

    /** The bars currently live, one per connected output; exposed so a caller or test can inspect them. */
    public val activeBars: List<KortexBar> get() = bars.values.map { it.bar }

    init {
        display.onGlobalAdded = { global -> if (global.interfaceName == WL_OUTPUT) pendingAdds += global }
        display.onGlobalRemoved = { global -> if (global.interfaceName == WL_OUTPUT) pendingRemoves += global.name }
    }

    /** Runs every bar until the connection dies. Blocks, and owns the connection for as long as it does. */
    public fun runEventLoop() {
        while (true) {
            applyPendingChanges()
            display.flush()
            if (display.dispatch(EVENT_LOOP_TIMEOUT_MILLIS) < 0) break
            serviceBars()
        }
    }

    /** Pumps the connection until [predicate] holds or [timeoutMillis] elapses; mirrors [KortexBar.pump]. */
    public fun pump(timeoutMillis: Long, predicate: () -> Boolean = { false }): Boolean {
        val deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLI
        while (System.nanoTime() < deadline) {
            applyPendingChanges()
            if (predicate()) return true
            display.roundtrip()
            serviceBars()
            Thread.sleep(PUMP_INTERVAL_MILLIS)
        }
        applyPendingChanges()
        serviceBars()
        return predicate()
    }

    private fun applyPendingChanges() {
        if (pendingAdds.isNotEmpty()) {
            val adds = pendingAdds.toList()
            pendingAdds.clear()
            adds.forEach(::addBar)
        }
        if (pendingRemoves.isNotEmpty()) {
            val removes = pendingRemoves.toList()
            pendingRemoves.clear()
            removes.forEach(::removeBar)
        }
    }

    private fun serviceBars() {
        // Snapshot first: removing here must not race the forEach below iterating the same map.
        val surfaceClosed = bars.filterValues { it.bar.closed }.keys.toList()
        surfaceClosed.forEach(::removeBar)
        bars.values.forEach { it.bar.serviceTick() }
    }

    // A hotplug arrives long after create() returned, with no Result channel left to report through.
    private fun addBar(global: WaylandGlobal) {
        addBarOrError(global).getOrElse { error("kortex bar creation failed for output ${global.name}: $it") }
    }

    private fun addBarOrError(global: WaylandGlobal): EmptyResult<KortexError> {
        val output = display.bind(global, LibWayland.outputInterface, WlVersion.OUTPUT)
        // A wl_output proxy with no listener crashes on its first event; reuse WlOutput's no-op one.
        OutputListener().install(output)
        return KortexBar.create(
            display,
            namespace = "$namespace-${global.name}",
            height = height,
            platform = platform,
            keyboard = keyboard,
            output = output,
        ).map { bar ->
            bar.setContent(content)
            bars[global.name] = ShellBar(output, bar)
        }
    }

    private fun removeBar(name: Int) {
        val removed = bars.remove(name) ?: return
        removed.bar.close()
        LibWayland.proxyDestroy(removed.output)
    }

    override fun close() {
        display.onGlobalAdded = null
        display.onGlobalRemoved = null
        bars.keys.toList().forEach(::removeBar)
    }

    private class ShellBar(val output: MemorySegment, val bar: KortexBar)

    public companion object {
        /** Namespace, height, platform and keyboard are shared by every bar the shell creates. */
        public fun create(
            display: WaylandDisplay,
            namespace: String = "kortex",
            height: Dp = 32.dp,
            platform: KortexPlatform = KortexPlatform.None,
            keyboard: KeyboardInteractivity = KeyboardInteractivity.None,
            content: @Composable () -> Unit,
        ): Result<KortexShell, KortexError> {
            val shell = KortexShell(display, namespace, height, platform, keyboard, content)
            for (global in display.globals.filter { it.interfaceName == WL_OUTPUT }) {
                shell.addBarOrError(global).getOrElse {
                    shell.close()
                    return Err(it)
                }
            }
            return Ok(shell)
        }

        private const val WL_OUTPUT = "wl_output"

        private const val NANOS_PER_MILLI = 1_000_000L
        private const val PUMP_INTERVAL_MILLIS = 16L
        private const val EVENT_LOOP_TIMEOUT_MILLIS = 16L
    }
}
