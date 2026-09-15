package com.fromwau.kortex.wayland

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.IError
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.flatMap
import com.fromwau.kern.result.getOrElse
import com.fromwau.kern.result.map
import com.fromwau.kern.result.onError
import com.fromwau.kern.result.onSuccess
import com.fromwau.kortex.compose.KortexPlatform
import java.lang.foreign.MemorySegment
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import kotlin.reflect.KClass

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

/** [clipboard]'s calls without its close, which is the shell's alone: what content reaches through its host. */
private class HostClipboard(clipboard: TextClipboard) : KortexClipboard by clipboard

/**
 * What the shell holds for one [Show]: the newest instance it was handed, what it asks for, its surface, and the
 * ending one of its instances asked for.
 */
internal class ShownSurface(val newest: State<LayerSurface<*>>, private val wake: () -> Unit) {
    // The settings its Show asks for while in composition, and null once it has left. Loop thread only.
    var wanted: SurfaceSettings? = null

    // Null until placed, and again once it has ended. Snapshot state, written on the loop thread outside composition,
    // so content that reads size recomposes as the surface is placed or goes, as it does on a configure.
    var surface: KortexSurface? by mutableStateOf(null)

    // What surface was placed with, which a change of settings replaces it over. Loop thread only.
    var placedWith: SurfaceSettings? = null

    // Set as its onClose is called: whatever the shell sees of this Show afterwards reports nothing. Loop thread only.
    var reported = false

    private val requested = AtomicReference<EndRequest?>(null)

    /**
     * How its surface ended by itself, once it has: the first ending an instance of the class it shows asked for,
     * else its content's crash, else a close by the compositor or through the surface's own handle.
     */
    val ownEnding: EmptyResult<SurfaceError<IError>>?
        get() = requested.get()?.takeIf { it.fromShownClass() }?.ending ?: surface?.let { placed ->
            placed.crash?.let { Err(SurfaceError.Failed(it)) } ?: Ok(Unit).takeIf { placed.closed }
        }

    /**
     * Asks for [ending] on behalf of [from], from any thread; the shell acts on it in its next pass. The first ask
     * from an instance of the class the Show shows decides. One from a class it no longer shows concerns a surface
     * already replaced, and does nothing.
     */
    fun requestEnd(from: LayerSurface<*>, ending: EmptyResult<SurfaceError<IError>>) {
        val ask = EndRequest(from::class, ending)
        val standing = requested.updateAndGet { current ->
            current?.takeIf { it.fromShownClass() } ?: ask.takeIf { it.fromShownClass() }
        }
        if (standing === ask) wake()
    }

    private fun EndRequest.fromShownClass(): Boolean = kind == newest.value::class

    private class EndRequest(val kind: KClass<*>, val ending: EmptyResult<SurfaceError<IError>>)
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
    private val onCrashSurface: (KortexError.SurfaceCrashed) -> Unit,
    // Shared by every surface: GlobalSnapshotManager keys each snapshot pump on its recomposer's own trampoline.
    private val loopQueue: LoopQueue,
    /** The one clipboard every surface's content shares; not private because a test reads through it. */
    internal val clipboard: WaylandClipboard,
    // What content copies and pastes through: the shell's clipboard, or a test's stand-in for it.
    private val contentClipboard: TextClipboard,
) {

    private val hostClipboard: KortexClipboard = HostClipboard(contentClipboard)

    private val outputs = mutableMapOf<Int, ShellOutput>()
    private val listedMonitors = mutableStateOf<List<Monitor>>(emptyList())
    private val surfaces = mutableListOf<ActiveSurface>()

    // Filled by each surface's scene, on whichever thread its content fails, and emptied by reportCrashes.
    private val crashes = ConcurrentLinkedQueue<KortexError.SurfaceCrashed>()

    // Registry callbacks fire mid-dispatch; touching `outputs` or `surfaces` there would race the loop
    // iterating them.
    private val pendingAdds = mutableListOf<WaylandGlobal>()
    private val pendingRemoves = mutableListOf<Int>()

    // Queued rather than placed at once: content may call open() from any thread, or mid-pass from the loop's own.
    private val pendingOpens = ConcurrentLinkedQueue<SurfaceSpec>()

    // The composition this shell runs, for a shell that runs an application rather than specs.
    private var application: ApplicationComposition? = null

    // Set from any thread by exitApplication; the run ends at its next pass.
    @Volatile
    private var exitRequested = false

    // The first throw of the application's own code, which ends the run; no onClose is called after it.
    private val applicationCrash = AtomicReference<KortexError.ApplicationCrashed?>(null)

    private val applicationScope = object : KortexApplicationScope {
        override fun exitApplication() {
            exitRequested = true
            display.wake()
        }
    }

    // Each Show whose surface is on screen, in the order placed.
    private val placed = mutableListOf<ShownSurface>()

    // Filled by Show's effects, which run inside a composition's apply, and acted on in the next pass.
    private val changedShows = ConcurrentLinkedQueue<ShownSurface>()

    /** The surfaces currently live, each with its output; exposed so a caller or test can inspect them. */
    public val activeSurfaces: List<ActiveSurface> get() = surfaces.toList()

    /** Every surface a [Show] holds on screen, in the order they were placed; a test reads them. */
    internal val shownSurfaces: List<KortexSurface> get() = placed.mapNotNull { it.surface }

    /** What [rememberMonitors] hands content: each bound output that has described itself, in the order bound. */
    internal val monitors: State<List<Monitor>> get() = listedMonitors

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
     * Ends early with the error when content throws, as [KortexError.SurfaceCrashed], or when a surface cannot be
     * placed; each crash also reaches `onCrashSurface`, and closing the shell afterwards is still the caller's.
     *
     * Blocks, and owns the connection for as long as it does. Content runs on the thread that runs the loop,
     * so create the shell on that same thread.
     */
    public fun runEventLoop(): EmptyResult<KortexError> {
        while (true) {
            applyPendingChanges().getOrElse { return Err(it) }
            if (exitRequested) break
            // An application ends only when asked to, so an empty screen keeps it running.
            if (application == null && surfaces.isEmpty() && !awaitingAnOutput) break
            if (!display.awaitWork(nextDeadlineNanos())) {
                // An application did not ask to stop, so the connection dying is its error.
                if (application != null) return Err(display.protocolError() ?: KortexError.NoCompositorResponse)
                break
            }
            serviceSurfaces().getOrElse { return Err(it) }
        }
        return Ok(Unit)
    }

    /** The earliest deadline across the surfaces that no event announces; not private because a test asserts it. */
    internal fun nextDeadlineNanos(): Long? =
        (surfaces.map { it.surface } + shownSurfaces).mapNotNull { it.nextDeadlineNanos }.minOrNull()

    /**
     * Pumps the connection until [predicate] holds or [timeoutMillis] elapses.
     *
     * @return whether [predicate] held, or the error that ended the run first, as [runEventLoop] would return it.
     */
    public fun pump(timeoutMillis: Long, predicate: () -> Boolean = { false }): Result<Boolean, KortexError> {
        val deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLI
        while (System.nanoTime() < deadline) {
            applyPendingChanges().getOrElse { return Err(it) }
            if (predicate()) return Ok(true)
            display.roundtrip()
            serviceSurfaces().getOrElse { return Err(it) }
            Thread.sleep(PUMP_INTERVAL_MILLIS)
        }
        applyPendingChanges().getOrElse { return Err(it) }
        serviceSurfaces().getOrElse { return Err(it) }
        return Ok(predicate())
    }

    private fun applyPendingChanges(): EmptyResult<KortexError> {
        applicationCrash.get()?.let { return Err(it) }
        if (pendingAdds.isNotEmpty()) {
            val adds = pendingAdds.toList()
            pendingAdds.clear()
            adds.forEach { global -> addOutput(global).getOrElse { return Err(it) } }
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
            opens.forEach { spec -> placeSurfaces(spec, standing = false).getOrElse { return Err(it) } }
        }
        reconcileShows()
        // Reconciling calls each onClose, and one that threw there ends the run here.
        return applicationCrash.get()?.let { Err(it) } ?: Ok(Unit)
    }

    private fun serviceSurfaces(): EmptyResult<KortexError> {
        // First: content running here posts to the surface queues drained next, its own close among them.
        loopQueue.runPass()
        // After the pass, which runs the snapshot pump that asks the application for a frame.
        application?.frame()
        // Before the reap: content's posted close marks its surface only when drained, and its wake is already spent.
        surfaces.forEach { it.surface.drainQueue() }
        shownSurfaces.forEach(KortexSurface::drainQueue)
        // filter copies first: removeSurface mutates the very list this walks.
        val closing = surfaces.filter { it.surface.closed }
        val toReplace = closing.filter(::shouldReplace)
        closing.forEach(::removeSurface)
        // A replacement that cannot be placed is dropped, as on a connection going down: one surface staying gone
        // beats ending the run. One whose content crashed has queued that crash, which ends the run below.
        toReplace.forEach { active -> placeSurfaces(active.spec, standing = true) }
        surfaces.forEach { active -> active.surface.serviceTick().getOrElse { return Err(it) } }
        // A shown surface that fails its tick ends by itself, as Failed; the run goes on.
        placed.forEach { shown ->
            shown.surface?.serviceTick()?.onError { reason ->
                shown.requestEnd(shown.newest.value, Err(SurfaceError.Failed(reason)))
            }
        }
        return reportCrashes()?.let { Err(it) } ?: applicationCrash.get()?.let { Err(it) } ?: Ok(Unit)
    }

    // Every crash since the last report goes to the host once; the first is what ends the run.
    private fun reportCrashes(): KortexError.SurfaceCrashed? =
        generateSequence(crashes::poll).toList().onEach(onCrashSurface).firstOrNull()

    // Exactly what OutputTarget.CompositorChoice's own KDoc promises: replaced only here, gone otherwise.
    private fun shouldReplace(active: ActiveSurface): Boolean =
        active.standing &&
            active.spec.target == OutputTarget.CompositorChoice &&
            active.surface.closeReason == CloseReason.Compositor &&
            outputs.isNotEmpty()

    private fun addOutput(global: WaylandGlobal): EmptyResult<KortexError> {
        val output = bindOutput(global)
        // The output has no name until its own done arrives, and a NamedOutput match needs it now.
        display.roundtrip()
        listMonitors()
        specs.filter { spec ->
            when (val target = spec.target) {
                OutputTarget.EveryOutput -> true
                OutputTarget.CompositorChoice -> false
                is OutputTarget.NamedOutput -> output.matchesName(target.name)
            }
        }.forEach { spec -> createSurface(spec, output, standing = true).getOrElse { return Err(it) } }
        return Ok(Unit)
    }

    private fun bindOutput(global: WaylandGlobal): ShellOutput {
        val proxy = display.bind(global, LibWayland.outputInterface, WlVersion.OUTPUT)
        // A wl_output proxy with no listener crashes on its first event.
        val listener = OutputListener()
        listener.install(proxy)
        return ShellOutput(global.name, proxy, listener).also { outputs[global.name] = it }
    }

    // An output is a monitor from its first done on, which the round trip after each bind waits for.
    private fun listMonitors() {
        listedMonitors.value = outputs.values
            .filter { it.listener.geometry != null }
            .map(::Monitor)
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
            loopQueue = loopQueue,
            onCrash = crashes::add,
            onInputSerial = clipboard::recordInputSerial,
            onKeyboardFocus = clipboard::recordKeyboardFocus,
        ).flatMap { surface ->
            val active = ActiveSurface(surface, spec, output, standing)
            // Built once per surface, not inside the content lambda: LocalKortexHost is static, so a fresh
            // instance handed to it on every recomposition would recompose everything the local reaches.
            val host = ShellHost(active)
            surface
                .setContent {
                    CompositionLocalProvider(LocalKortexHost provides host) {
                        ProvideClipboard(contentClipboard, spec.content)
                    }
                }
                // Never added to surfaces, so nothing else would close it.
                .onError { surface.close() }
                .map { surfaces += active }
        }
    }

    private inner class ShellHost(private val active: ActiveSurface) : KortexHost {
        override val output: OutputGeometry? get() = active.geometry

        override fun open(spec: SurfaceSpec) {
            pendingOpens += spec
            display.wake()
        }

        override val clipboard: KortexClipboard get() = hostClipboard
    }

    private fun removeSurface(active: ActiveSurface) {
        surfaces.remove(active)
        active.surface.close()
    }

    /** Makes the loop run a pass soon, from any thread. */
    internal fun wake() {
        display.wake()
    }

    /** [shown]'s `Show` entered composition, or its settings changed: its surface is placed in the next pass. */
    internal fun queuePlace(shown: ShownSurface, settings: SurfaceSettings) {
        shown.wanted = settings
        changedShows += shown
        display.wake()
    }

    /** [shown]'s `Show` left composition, or is about to be handed new settings. */
    internal fun queueRemove(shown: ShownSurface) {
        shown.wanted = null
        changedShows += shown
        display.wake()
    }

    private fun reconcileShows() {
        // Every placed Show, for an ending of its own, and every Show that changed.
        (placed.toList() + generateSequence(changedShows::poll)).forEach(::reconcile)
    }

    private fun reconcile(shown: ShownSurface) {
        if (shown.reported) return
        // Once the application's own code has thrown, surfaces only go: nothing more is placed.
        if (applicationCrash.get() != null) return end(shown, Ok(Unit))
        val ownEnding = shown.ownEnding
        val wanted = shown.wanted
        when {
            // First: a surface that ended by itself before its Show left reports how it ended.
            ownEnding != null -> end(shown, ownEnding)
            wanted == null -> end(shown, Ok(Unit))
            shown.surface == null -> place(shown, wanted)
            shown.placedWith != wanted -> replace(shown, wanted)
        }
    }

    // The Show is still in composition, so nothing has ended for its host, unless its content failed as it went.
    private fun replace(shown: ShownSurface, settings: SurfaceSettings) {
        val crash = takeDown(shown) ?: return place(shown, settings)
        report(shown, Err(SurfaceError.Failed(crash)))
    }

    private fun place(shown: ShownSurface, settings: SurfaceSettings) {
        val output = settings.monitor?.let { monitor ->
            // An unplugged monitor's proxy is already destroyed: the surface ends, as one on it does when it goes.
            monitor.output.takeIf { outputs[it.name] === it } ?: return report(shown, Ok(Unit))
        }
        KortexSurface
            .create(
                display,
                settings.config,
                platform = platform,
                output = output?.proxy ?: MemorySegment.NULL,
                loopQueue = loopQueue,
                // Only a wake: the next pass reads the scene's first failure, which this one may not be.
                onCrash = { wake() },
                onInputSerial = clipboard::recordInputSerial,
                onKeyboardFocus = clipboard::recordKeyboardFocus,
            )
            .flatMap { surface ->
                // Before the content composes, so its first composition already reads the surface's size.
                shown.surface = surface
                surface
                    .setContent { ProvideClipboard(contentClipboard) { shown.newest.value.invoke() } }
                    .onError {
                        shown.surface = null
                        surface.close()
                    }
            }
            .onSuccess {
                shown.placedWith = settings
                placed += shown
            }
            .onError { reason -> report(shown, Err(SurfaceError.Failed(reason))) }
    }

    private fun end(shown: ShownSurface, ending: EmptyResult<SurfaceError<IError>>) {
        // Content failing, its cleanup as the surface goes included, ends it as a crash whatever else ended it.
        val crash = takeDown(shown)
        report(shown, crash?.let { Err(SurfaceError.Failed(it)) } ?: ending)
    }

    /**
     * Closes [shown]'s surface, if any, and returns its content's crash, including one its cleanup threw while closing.
     */
    private fun takeDown(shown: ShownSurface): KortexError.SurfaceCrashed? {
        val surface = shown.surface ?: return null
        placed.remove(shown)
        shown.surface = null
        surface.close()
        return surface.crash
    }

    private fun report(shown: ShownSurface, ending: EmptyResult<SurfaceError<IError>>) {
        shown.reported = true
        // Once the application's own code has thrown, none of it runs again.
        if (applicationCrash.get() != null) return
        try {
            shown.newest.value.report(ending)
        } catch (cause: Throwable) {
            applicationFailed(cause)
        }
    }

    private fun applicationFailed(cause: Throwable) {
        applicationCrash.compareAndSet(null, KortexError.ApplicationCrashed(cause))
        display.wake()
    }

    private fun startApplication(content: @Composable KortexApplicationScope.() -> Unit) {
        val application = ApplicationComposition(loopQueue, display::wake, ::applicationFailed)
        this.application = application
        application.setContent {
            CompositionLocalProvider(LocalKortexShell provides this@KortexShell) { applicationScope.content() }
        }
    }

    /**
     * Closes every surface and output, runs what is left on the queue and gives the clipboard back, whatever
     * content throws meanwhile. Each crash not yet handed to `onCrashSurface` goes to it now, and the first is
     * returned.
     */
    public fun close(): EmptyResult<KortexError> {
        display.onGlobalAdded = null
        display.onGlobalRemoved = null
        application?.let { application ->
            // Every Show leaves composition here, and reconciling reports how each of their surfaces ended.
            application.close()
            reconcileShows()
            // A disposal that threw can leave Shows in place; that throw ended the application, so they go unreported.
            placed.toList().forEach(::takeDown)
        }
        surfaces.toList().forEach(::removeSurface)
        outputs.values.forEach(ShellOutput::destroy)
        outputs.clear()
        // No pass follows a close, so what reached the queue since the last one runs here.
        loopQueue.drain()
        // After the drain, which can still run a request content made of the clipboard.
        clipboard.close()
        // After the drain, since a closed scene's late work can be what crashed.
        return reportCrashes()?.let { Err(it) } ?: applicationCrash.get()?.let { Err(it) } ?: Ok(Unit)
    }

    public companion object {
        /**
         * Creates every surface [specs] asks for, in the order given, on the outputs connected now.
         *
         * A per-output surface takes the output's registry name as a namespace suffix, so one spec's
         * surfaces stay distinguishable to the compositor and to whoever reads its layer list.
         *
         * @param onCrashSurface called on the loop thread with every crash of a surface's content, once each,
         *   those while closing included.
         */
        public fun create(
            display: WaylandDisplay,
            vararg specs: SurfaceSpec,
            platform: KortexPlatform = KortexPlatform.None,
            onCrashSurface: (KortexError.SurfaceCrashed) -> Unit = {},
        ): Result<KortexShell, KortexError> = create(display, specs.toList(), platform, onCrashSurface) { it }

        /**
         * The public [create], with content copying and pasting through what [contentClipboard] makes of the
         * shell's own clipboard; a test hands it a stand-in.
         */
        internal fun create(
            display: WaylandDisplay,
            specs: List<SurfaceSpec>,
            platform: KortexPlatform,
            onCrashSurface: (KortexError.SurfaceCrashed) -> Unit,
            contentClipboard: (WaylandClipboard) -> TextClipboard,
        ): Result<KortexShell, KortexError> {
            val loopQueue = LoopQueue(display::wake)
            // Before any surface can take focus: the selection comes as focus arrives, to the devices there are then.
            val clipboard = WaylandClipboard.bind(display, loopQueue).getOrElse { return Err(it) }
            val shell = KortexShell(
                display = display,
                specs = specs,
                platform = platform,
                onCrashSurface = onCrashSurface,
                loopQueue = loopQueue,
                clipboard = clipboard,
                contentClipboard = contentClipboard(clipboard),
            )
            display.globals
                .filter { it.interfaceName == WL_OUTPUT }
                .forEach(shell::bindOutput)
            // A NamedOutput spec placed before any output's own done arrives would match no name at all.
            display.roundtrip()
            shell.listMonitors()
            for (spec in specs) {
                shell.placeSurfaces(spec, standing = true).getOrElse {
                    // Its crashes reach onCrashSurface; what this returns is why placing failed.
                    shell.close()
                    return Err(it)
                }
            }
            return Ok(shell)
        }

        /** A shell that runs [content] as an application composition, and no spec. */
        internal fun createApplication(
            display: WaylandDisplay,
            platform: KortexPlatform = KortexPlatform.None,
            content: @Composable KortexApplicationScope.() -> Unit,
        ): Result<KortexShell, KortexError> {
            // Before the content runs: its surfaces are placed in later passes, where a missing global would reach
            // each one's onClose instead of ending the run.
            missingSurfaceGlobal(display.globals)?.let { return Err(it) }
            val shell = create(display, emptyList(), platform, onCrashSurface = {}, contentClipboard = { it })
                .getOrElse { return Err(it) }
            shell.startApplication(content)
            // The first composition runs the application's content, which can already have thrown.
            shell.applicationCrash.get()?.let { crash ->
                shell.close()
                return Err(crash)
            }
            return Ok(shell)
        }

        /** The first global a surface binds as it is placed that [globals] lacks; null when there is none. */
        internal fun missingSurfaceGlobal(globals: List<WaylandGlobal>): KortexError.MissingGlobal? =
            SURFACE_GLOBALS
                .firstOrNull { interfaceName -> globals.none { it.interfaceName == interfaceName } }
                ?.let(KortexError::MissingGlobal)

        // What LayerShellSurface, Shm and WlCursorTheme bind for each surface. wl_seat is not here: the clipboard binds
        // it as the shell is created, which fails without it.
        private val SURFACE_GLOBALS = listOf("wl_compositor", "wl_shm", "zwlr_layer_shell_v1")

        private const val WL_OUTPUT = "wl_output"

        private const val NANOS_PER_MILLI = 1_000_000L
        private const val PUMP_INTERVAL_MILLIS = 16L
    }
}
