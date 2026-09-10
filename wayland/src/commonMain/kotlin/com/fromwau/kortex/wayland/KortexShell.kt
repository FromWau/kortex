package com.fromwau.kortex.wayland

import androidx.compose.runtime.CompositionLocalProvider
import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.getOrElse
import com.fromwau.kern.result.map
import com.fromwau.kortex.compose.KortexPlatform
import java.lang.foreign.MemorySegment
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher

/** A bound `wl_output`: the registry name it was announced under, its proxy, and what it publishes. */
internal class ShellOutput(
    val name: Int,
    val proxy: MemorySegment,
    val listener: OutputListener,
) {
    /** Gives the output back. The order is the point: the proxy first, then the stubs it dispatches into. */
    fun destroy() {
        releaseOutput(proxy)
        listener.close()
    }
}

/**
 * A live surface together with the spec it came from and the output it went on.
 *
 * @property spec what the host asked for, so one of its surfaces can be told from another. A
 *   per-output surface carries the output's registry name as a namespace suffix, so this is the
 *   namespace the host wrote rather than the one the compositor knows the surface by.
 */
public class ActiveSurface internal constructor(
    public val surface: KortexSurface,
    public val spec: SurfaceSpec,
    internal val output: ShellOutput?,
    // False for a spec that arrived through KortexHost.open: a one-shot placement never comes back.
    internal val standing: Boolean,
) {
    /** What the output published about itself; null before its first `done`, and with no output at all. */
    public val geometry: OutputGeometry? get() = output?.listener?.geometry
}

/**
 * Several independent surfaces on one connection, each [SurfaceSpec] placed where its [OutputTarget]
 * says and per-output ones created and destroyed as outputs come and go.
 */
public class KortexShell private constructor(
    private val display: WaylandDisplay,
    private val specs: List<SurfaceSpec>,
    private val platform: KortexPlatform,
    // Shared so every surface's FrameRecomposer registers with GlobalSnapshotManager from the same
    // thread; a distinct dispatcher per surface is what provokes its multi-thread warning.
    private val frameDispatcher: ExecutorCoroutineDispatcher,
) : AutoCloseable {

    private val outputs = mutableMapOf<Int, ShellOutput>()
    private val surfaces = mutableListOf<ActiveSurface>()

    // Registry callbacks fire mid-dispatch; touching `outputs` or `surfaces` there would race the loop
    // iterating them.
    private val pendingAdds = mutableListOf<WaylandGlobal>()
    private val pendingRemoves = mutableListOf<Int>()

    // open() is called from a frame thread, unlike the registry callbacks above, which fire on the loop thread.
    private val pendingOpens = ConcurrentLinkedQueue<SurfaceSpec>()

    /** The surfaces currently live, each with its output; exposed so a caller or test can inspect them. */
    public val activeSurfaces: List<ActiveSurface> get() = surfaces.toList()

    // A future output could still complete an EveryOutput spec, or the one output a NamedOutput spec names.
    private val awaitingAnOutput: Boolean
        get() = specs.any { spec ->
            when (val target = spec.target) {
                OutputTarget.EveryOutput -> outputs.isEmpty()
                OutputTarget.CompositorChoice -> false
                is OutputTarget.NamedOutput -> outputs.values.none { it.matchesName(target.name) }
            }
        }

    init {
        display.onGlobalAdded = { global -> if (global.interfaceName == WL_OUTPUT) pendingAdds += global }
        display.onGlobalRemoved = { global -> if (global.interfaceName == WL_OUTPUT) pendingRemoves += global.name }
    }

    /**
     * Runs every surface until the connection dies, or until no surface is left and none can return.
     *
     * A surface that closed itself is never put back, so the loop ends with the last one, unless a spec
     * can still place another. An [OutputTarget.EveryOutput] spec waiting for any output keeps it running
     * with nothing on screen, and so does an [OutputTarget.NamedOutput] spec whose own output is not
     * connected. Only a new output places a per-output surface, so on two outputs content that closes its
     * own surface leaves that output bare while the other keeps running.
     *
     * A standing [OutputTarget.CompositorChoice] surface is a further exception: when the compositor
     * itself closes it, rather than content, it is placed again as long as any output remains connected,
     * so that case keeps the loop running too.
     *
     * Blocks, and owns the connection for as long as it does.
     */
    public fun runEventLoop() {
        while (true) {
            applyPendingChanges()
            if (surfaces.isEmpty() && !awaitingAnOutput) break
            if (!display.awaitWork(nextDeadlineNanos())) break
            serviceSurfaces()
        }
    }

    private fun nextDeadlineNanos(): Long? = surfaces.mapNotNull { it.surface.nextDeadlineNanos }.minOrNull()

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
        if (pendingOpens.isNotEmpty()) {
            // Drained fully before any is placed: placing one can compose content that calls open()
            // again, and servicing that in the same pass would spin forever on pathological content.
            val opens = generateSequence(pendingOpens::poll).toList()
            opens.forEach { spec ->
                placeSurfaces(spec, standing = false)
                    .getOrElse { error("kortex surface open failed for ${spec.config.namespace}: $it") }
            }
        }
    }

    private fun serviceSurfaces() {
        // Before the reap: a close content posts marks its surface only once run, and nothing wakes the loop again.
        surfaces.forEach { it.surface.drainQueue() }
        // filter copies first: removeSurface mutates the very list this walks.
        val closing = surfaces.filter { it.surface.closed }
        val toReplace = closing.filter(::shouldReplace)
        closing.forEach(::removeSurface)
        // Dropped rather than thrown: a replacement fails on a connection already going down, with no
        // caller left, and taking the host with it is worse than one surface staying gone.
        toReplace.forEach { active -> placeSurfaces(active.spec, standing = true) }
        surfaces.forEach { it.surface.serviceTick() }
    }

    // Exactly what OutputTarget.CompositorChoice's own KDoc promises: replaced only here, gone otherwise.
    private fun shouldReplace(active: ActiveSurface): Boolean =
        active.standing &&
            active.spec.target == OutputTarget.CompositorChoice &&
            active.surface.closeReason == CloseReason.Compositor &&
            outputs.isNotEmpty()

    // A hotplug arrives long after create() returned, with no Result channel left to report through.
    private fun addOutput(global: WaylandGlobal) {
        val output = bindOutput(global)
        // The output has no name until its own done arrives, and a NamedOutput match needs it now.
        display.roundtrip()
        specs.filter { spec ->
            when (val target = spec.target) {
                OutputTarget.EveryOutput -> true
                OutputTarget.CompositorChoice -> false
                is OutputTarget.NamedOutput -> output.matchesName(target.name)
            }
        }.forEach { spec ->
            createSurface(spec, output, standing = true)
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

    // wl_output.name (what a NamedOutput target carries) is not ShellOutput.name, the registry id.
    private fun ShellOutput.matchesName(name: String): Boolean = listener.geometry?.name == name

    private fun removeOutput(name: Int) {
        val output = outputs.remove(name) ?: return
        surfaces.filter { it.output === output }.forEach(::removeSurface)
        output.destroy()
    }

    private fun placeSurfaces(spec: SurfaceSpec, standing: Boolean): EmptyResult<KortexError> {
        when (val target = spec.target) {
            OutputTarget.CompositorChoice -> return createSurface(spec, output = null, standing)
            OutputTarget.EveryOutput -> outputs.values.forEach { output ->
                createSurface(spec, output, standing).getOrElse { return Err(it) }
            }
            is OutputTarget.NamedOutput -> outputs.values
                .firstOrNull { it.matchesName(target.name) }
                ?.let { output -> createSurface(spec, output, standing).getOrElse { return Err(it) } }
        }
        return Ok(Unit)
    }

    private fun createSurface(spec: SurfaceSpec, output: ShellOutput?, standing: Boolean): EmptyResult<KortexError> {
        val namespace = output?.let { "${spec.config.namespace}-${it.name}" } ?: spec.config.namespace
        return KortexSurface.create(
            display,
            spec.config.copy(namespace = namespace),
            platform = platform,
            output = output?.proxy ?: MemorySegment.NULL,
            frameDispatcher = frameDispatcher,
        ).map { surface ->
            val active = ActiveSurface(surface, spec, output, standing)
            val host = hostFor(active)
            surface.setContent {
                CompositionLocalProvider(LocalKortexHost provides host) { spec.content() }
            }
            surfaces += active
        }
    }

    // Built once per surface, not inside the content lambda: LocalKortexHost is static, so a fresh
    // instance handed to it on every recomposition would recompose everything the local reaches.
    private fun hostFor(active: ActiveSurface): KortexHost = object : KortexHost {
        override val output: OutputGeometry? get() = active.geometry
        override fun open(spec: SurfaceSpec) {
            pendingOpens += spec
            display.wake()
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
        outputs.values.forEach(ShellOutput::destroy)
        outputs.clear()
        // Only after every surface: each surface's own ownedDispatcher is null for this one, so none of
        // them would ever close it themselves.
        frameDispatcher.close()
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
            val frameDispatcher = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "kortex-frame").apply { isDaemon = true }
            }.asCoroutineDispatcher()
            val shell = KortexShell(display, specs.toList(), platform, frameDispatcher)
            display.globals
                .filter { it.interfaceName == WL_OUTPUT }
                .forEach(shell::bindOutput)
            // A NamedOutput spec placed before any output's own done arrives would match no name at all.
            display.roundtrip()
            for (spec in specs) {
                shell.placeSurfaces(spec, standing = true).getOrElse {
                    shell.close()
                    return Err(it)
                }
            }
            return Ok(shell)
        }

        private const val WL_OUTPUT = "wl_output"

        private const val NANOS_PER_MILLI = 1_000_000L
        private const val PUMP_INTERVAL_MILLIS = 16L
    }
}
