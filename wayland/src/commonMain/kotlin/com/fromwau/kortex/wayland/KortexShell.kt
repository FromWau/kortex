package com.fromwau.kortex.wayland

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.getOrElse
import com.fromwau.kern.result.map
import com.fromwau.kortex.compose.KortexPlatform
import java.lang.foreign.MemorySegment

/** A bound `wl_output`: the registry name it was announced under, its proxy, and what it publishes. */
internal class ShellOutput(
    val name: Int,
    val proxy: MemorySegment,
    val listener: OutputListener,
)

/** A live surface together with the output it went on. */
public class ActiveSurface internal constructor(
    public val surface: KortexSurface,
    internal val output: ShellOutput?,
) {
    /** What the output published about itself; null before its first `done`, and with no output at all. */
    internal val geometry: OutputGeometry? get() = output?.listener?.geometry
}

/**
 * Several independent surfaces on one connection, each [SurfaceSpec] placed where its [OutputTarget]
 * says and per-output ones created and destroyed as outputs come and go.
 */
public class KortexShell private constructor(
    private val display: WaylandDisplay,
    private val specs: List<SurfaceSpec>,
    private val platform: KortexPlatform,
) : AutoCloseable {

    private val outputs = mutableMapOf<Int, ShellOutput>()
    private val surfaces = mutableListOf<ActiveSurface>()

    // Registry callbacks fire mid-dispatch; touching `outputs` or `surfaces` there would race the loop
    // iterating them.
    private val pendingAdds = mutableListOf<WaylandGlobal>()
    private val pendingRemoves = mutableListOf<Int>()

    /** The surfaces currently live, each with its output; exposed so a caller or test can inspect them. */
    public val activeSurfaces: List<ActiveSurface> get() = surfaces.toList()

    // Once the shell is running, only an output arriving can add a surface, and only for a spec that
    // asked for every output.
    private val awaitingAnOutput: Boolean
        get() = outputs.isEmpty() && specs.any { it.target == OutputTarget.EveryOutput }

    init {
        display.onGlobalAdded = { global -> if (global.interfaceName == WL_OUTPUT) pendingAdds += global }
        display.onGlobalRemoved = { global -> if (global.interfaceName == WL_OUTPUT) pendingRemoves += global.name }
    }

    /**
     * Runs every surface until the connection dies, or until no surface is left and none can return.
     *
     * A surface that closed itself is never put back, so the loop ends with the last one — unless an
     * [OutputTarget.EveryOutput] spec is still waiting for an output to place one on, which keeps it
     * running with nothing on screen.
     *
     * Blocks, and owns the connection for as long as it does.
     */
    public fun runEventLoop() {
        while (true) {
            applyPendingChanges()
            if (surfaces.isEmpty() && !awaitingAnOutput) break
            display.flush()
            if (display.dispatch(EVENT_LOOP_TIMEOUT_MILLIS) < 0) break
            serviceSurfaces()
        }
    }

    /** Pumps the connection until [predicate] holds or [timeoutMillis] elapses; mirrors [KortexSurface.pump]. */
    public fun pump(timeoutMillis: Long, predicate: () -> Boolean = { false }): Boolean {
        val deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLI
        while (System.nanoTime() < deadline) {
            applyPendingChanges()
            if (predicate()) return true
            display.roundtrip()
            serviceSurfaces()
            Thread.sleep(PUMP_INTERVAL_MILLIS)
        }
        applyPendingChanges()
        serviceSurfaces()
        return predicate()
    }

    private fun applyPendingChanges() {
        if (pendingAdds.isNotEmpty()) {
            val adds = pendingAdds.toList()
            pendingAdds.clear()
            adds.forEach(::addOutput)
        }
        if (pendingRemoves.isNotEmpty()) {
            val removes = pendingRemoves.toList()
            pendingRemoves.clear()
            removes.forEach(::removeOutput)
        }
    }

    private fun serviceSurfaces() {
        // filter copies first: removeSurface mutates the very list this walks.
        surfaces.filter { it.surface.closed }.forEach(::removeSurface)
        surfaces.forEach { it.surface.serviceTick() }
    }

    // A hotplug arrives long after create() returned, with no Result channel left to report through.
    private fun addOutput(global: WaylandGlobal) {
        val output = bindOutput(global)
        specs.filter { it.target == OutputTarget.EveryOutput }.forEach { spec ->
            createSurface(spec, output)
                .getOrElse { error("kortex surface creation failed for output ${global.name}: $it") }
        }
    }

    private fun bindOutput(global: WaylandGlobal): ShellOutput {
        val proxy = display.bind(global, LibWayland.outputInterface, WlVersion.OUTPUT)
        // A wl_output proxy with no listener crashes on its first event.
        val listener = OutputListener()
        listener.install(proxy)
        return ShellOutput(global.name, proxy, listener).also { outputs[global.name] = it }
    }

    private fun removeOutput(name: Int) {
        val output = outputs.remove(name) ?: return
        surfaces.filter { it.output === output }.forEach(::removeSurface)
        LibWayland.proxyDestroy(output.proxy)
    }

    private fun placeSurfaces(spec: SurfaceSpec): EmptyResult<KortexError> {
        when (spec.target) {
            OutputTarget.CompositorChoice -> return createSurface(spec, output = null)
            OutputTarget.EveryOutput -> outputs.values.forEach { output ->
                createSurface(spec, output).getOrElse { return Err(it) }
            }
        }
        return Ok(Unit)
    }

    private fun createSurface(spec: SurfaceSpec, output: ShellOutput?): EmptyResult<KortexError> {
        val namespace = output?.let { "${spec.config.namespace}-${it.name}" } ?: spec.config.namespace
        return KortexSurface.create(
            display,
            spec.config.copy(namespace = namespace),
            platform = platform,
            output = output?.proxy ?: MemorySegment.NULL,
        ).map { surface ->
            surface.setContent(spec.content)
            surfaces += ActiveSurface(surface, output)
        }
    }

    private fun removeSurface(active: ActiveSurface) {
        surfaces.remove(active)
        active.surface.close()
    }

    override fun close() {
        display.onGlobalAdded = null
        display.onGlobalRemoved = null
        surfaces.toList().forEach(::removeSurface)
        outputs.values.forEach { LibWayland.proxyDestroy(it.proxy) }
        outputs.clear()
    }

    public companion object {
        /**
         * Creates every surface [specs] asks for, in the order given, on the outputs connected now.
         *
         * A per-output surface takes the output's registry name as a namespace suffix, so one spec's
         * surfaces stay distinguishable to the compositor and to whoever reads its layer list.
         */
        public fun create(
            display: WaylandDisplay,
            vararg specs: SurfaceSpec,
            platform: KortexPlatform = KortexPlatform.None,
        ): Result<KortexShell, KortexError> {
            val shell = KortexShell(display, specs.toList(), platform)
            display.globals
                .filter { it.interfaceName == WL_OUTPUT }
                .forEach(shell::bindOutput)
            for (spec in specs) {
                shell.placeSurfaces(spec).getOrElse {
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
