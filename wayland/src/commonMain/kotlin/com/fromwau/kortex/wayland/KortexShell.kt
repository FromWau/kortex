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
import com.fromwau.kern.result.onError
import com.fromwau.kern.result.onSuccess
import com.fromwau.kortex.compose.KortexPlatform
import com.fromwau.kortex.compose.LocalKortexSurface
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

/** [clipboard] without its close, which is the shell's alone: what content reaches as [LocalKortexClipboard]. */
private class HostClipboard(clipboard: TextClipboard) : KortexClipboard by clipboard

/**
 * What the shell holds for one [Show]: the newest instance it was handed, what it asks for, its surface, and the
 * ending one of its instances asked for.
 *
 * @param parent the Show whose surface's content this Show is in; null for one in the application's own content.
 */
internal class ShownSurface(
    val newest: State<LayerSurface<*>>,
    private val wake: () -> Unit,
    private val parent: ShownSurface?,
) {
    // The parent's surface as this Show entered its content: a replace or an ending takes this Show out with it.
    private val parentSurface: KortexSurface? = parent?.surface

    /** Whether the surface whose content this Show is in has gone. */
    val parentGone: Boolean get() = parent != null && parent.surface !== parentSurface

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
     * else its content's crash, else the compositor's close, or a test's [KortexSurface.simulateCompositorClose]. Loop
     * thread only; calling it forgets an ending asked for by a class the Show no longer shows.
     */
    fun ownEnding(): EmptyResult<SurfaceError<IError>>? =
        standingRequest(ask = null)?.ending ?: surface?.let { placed ->
            placed.crash?.let { Err(SurfaceError.Failed(it)) } ?: Ok(Unit).takeIf { placed.closed }
        }

    /**
     * Asks for [ending] on behalf of [from], from any thread; the shell acts on it in its next pass. The first ask
     * from an instance of the class the Show shows decides. One from a class it no longer shows concerns a surface
     * already replaced, and does nothing.
     */
    fun requestEnd(
        from: LayerSurface<*>,
        ending: EmptyResult<SurfaceError<IError>>,
    ) {
        val ask = EndRequest(from::class, ending)
        if (standingRequest(ask) === ask) wake()
    }

    // Drops, not just skips, a request from a class the Show no longer shows: it must not count if that class returns.
    private fun standingRequest(ask: EndRequest?): EndRequest? = requested.updateAndGet { current ->
        val shownClass = newest.value::class
        listOfNotNull(current, ask).firstOrNull { it.kind == shownClass }
    }

    private class EndRequest(
        val kind: KClass<*>,
        val ending: EmptyResult<SurfaceError<IError>>,
    )
}

/**
 * The engine behind [kortexApplication]: one connection's outputs, clipboard and loop, the application composition,
 * and a surface for each [Show] in it.
 */
internal class KortexShell private constructor(
    private val display: WaylandDisplay,
    private val platform: KortexPlatform,
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

    // Registry callbacks fire mid-dispatch; touching `outputs` or `placed` there would race the loop iterating them.
    private val pendingAdds = mutableListOf<WaylandGlobal>()
    private val pendingRemoves = mutableListOf<Int>()

    // Set from any thread by exitApplication; the run ends at its next pass.
    @Volatile
    private var exitRequested = false

    // The first throw of the application's own code, which ends the run; no onClose is called after it.
    private val applicationCrash = AtomicReference<KortexError.ApplicationCrashed?>(null)

    private val application = ApplicationComposition(loopQueue, display::wake, ::applicationFailed)

    private val applicationScope = object : KortexApplicationScope {
        override fun exitApplication() {
            exitRequested = true
            display.wake()
        }
    }

    // Each Show whose surface is on screen, in the order placed: one in a surface's content after that surface's own.
    private val placed = mutableListOf<ShownSurface>()

    // Filled by Show's effects, which run inside a composition's apply, and acted on in the next pass.
    private val changedShows = ConcurrentLinkedQueue<ShownSurface>()

    /** Every surface a [Show] holds on screen, in the order they were placed; a test reads them. */
    internal val shownSurfaces: List<KortexSurface> get() = placed.mapNotNull { it.surface }

    /** What [rememberMonitors] hands content: each bound output that has described itself, in the order bound. */
    internal val monitors: State<List<Monitor>> get() = listedMonitors

    init {
        display.onGlobalAdded = { global -> if (global.interfaceName == WL_OUTPUT) pendingAdds += global }
        display.onGlobalRemoved = { global -> if (global.interfaceName == WL_OUTPUT) pendingRemoves += global.name }
    }

    /**
     * Runs the application until `exitApplication()` is called, returning `Ok`. Ends early with
     * [KortexError.ApplicationCrashed] once the application's own code throws, and with the connection's error once
     * the connection dies. Nothing else ends it: an application with nothing on screen keeps running.
     *
     * Blocks, and owns the connection for as long as it does. Content runs on the thread that runs the loop,
     * so create the shell on that same thread.
     */
    fun runEventLoop(): EmptyResult<KortexError> {
        while (true) {
            applyPendingChanges().getOrElse { return Err(it) }
            if (exitRequested) break
            if (!display.awaitWork(nextDeadlineNanos())) {
                // The application did not ask to stop, so the connection dying is its error.
                return Err(display.protocolError() ?: KortexError.NoCompositorResponse)
            }
            serviceSurfaces().getOrElse { return Err(it) }
        }
        return Ok(Unit)
    }

    /** The earliest deadline across the surfaces that no event announces; not private because a test asserts it. */
    internal fun nextDeadlineNanos(): Long? = shownSurfaces.mapNotNull { it.nextDeadlineNanos }.minOrNull()

    /**
     * Pumps the connection until [predicate] holds or [timeoutMillis] elapses; exposed so a test can drive the shell,
     * where an application runs [runEventLoop].
     *
     * @return whether [predicate] held, or the error that ended the run first, as [runEventLoop] would return it.
     */
    fun pump(
        timeoutMillis: Long,
        predicate: () -> Boolean = { false },
    ): Result<Boolean, KortexError> {
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

    private fun runResult(): EmptyResult<KortexError> = applicationCrash.get()?.let { Err(it) } ?: Ok(Unit)

    private fun applyPendingChanges(): EmptyResult<KortexError> {
        applicationCrash.get()?.let { return Err(it) }
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
        reconcileShows()
        // Reconciling calls each onClose, and one that threw there ends the run here.
        return runResult()
    }

    private fun serviceSurfaces(): EmptyResult<KortexError> {
        // First: content running here posts to the surface queues each tick drains.
        loopQueue.runPass()
        // After the pass, which runs the snapshot pump that asks the application for a frame.
        application.frame()
        // A shown surface that fails its tick ends by itself, as Failed; the run goes on.
        placed.forEach { shown ->
            shown.surface?.serviceTick()?.onError { reason ->
                shown.requestEnd(shown.newest.value, Err(SurfaceError.Failed(reason)))
            }
        }
        return runResult()
    }

    private fun addOutput(global: WaylandGlobal) {
        bindOutput(global)
        display.roundtrip()
        listMonitors()
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

    private fun removeOutput(name: Int) {
        val output = outputs.remove(name) ?: return
        listMonitors()
        // A monitor's surfaces end with it, whether or not the compositor closes them, and before its output goes.
        placed
            .filter { it.placedWith?.monitor?.output === output }
            .forEach { shown -> end(shown, shown.ownEnding() ?: Ok(Unit)) }
        output.destroy()
    }

    /** Makes the loop run a pass soon, from any thread. */
    internal fun wake() {
        display.wake()
    }

    /** [shown]'s `Show` entered composition, or its settings changed: its surface is placed in the next pass. */
    internal fun queuePlace(
        shown: ShownSurface,
        settings: SurfaceSettings,
    ) {
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
        val ownEnding = shown.ownEnding()
        // Not left to the Show's own dispose, which Compose skips once an earlier cleanup in that content throws.
        val wanted = shown.wanted.takeUnless { shown.parentGone }
        when {
            // First: a surface that ended by itself before its Show left reports how it ended.
            ownEnding != null -> end(shown, ownEnding)
            wanted == null -> end(shown, Ok(Unit))
            shown.surface == null -> place(shown, wanted)
            shown.placedWith != wanted -> replace(shown, wanted)
        }
    }

    // The Show is still in composition, so nothing has ended for its host, unless its content failed as it went.
    private fun replace(
        shown: ShownSurface,
        settings: SurfaceSettings,
    ) {
        val crash = takeDown(shown) ?: return place(shown, settings)
        report(shown, Err(SurfaceError.Failed(crash)))
    }

    private fun place(
        shown: ShownSurface,
        settings: SurfaceSettings,
    ) {
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
                    .setContent { ShownContent(shown) }
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

    /** What a shown surface's scene composes: its newest instance's content, and around it what content reaches. */
    @Composable
    private fun ShownContent(shown: ShownSurface) {
        val instance = shown.newest.value
        CompositionLocalProvider(
            LocalKortexShell provides this,
            LocalShownSurface provides shown,
            LocalKortexSurface provides instance,
            LocalKortexClipboard provides hostClipboard,
        ) {
            ProvideClipboard(contentClipboard) { instance.invoke() }
        }
    }

    private fun end(
        shown: ShownSurface,
        ending: EmptyResult<SurfaceError<IError>>,
    ) {
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

    private fun report(
        shown: ShownSurface,
        ending: EmptyResult<SurfaceError<IError>>,
    ) {
        shown.reported = true
        // Once the application's own code has thrown, none of it runs again.
        if (applicationCrash.get() != null) return
        runHostCode(::applicationFailed) { shown.newest.value.report(ending) }
    }

    private fun applicationFailed(cause: Throwable) {
        applicationCrash.compareAndSet(null, KortexError.ApplicationCrashed(cause))
        display.wake()
    }

    private fun startApplication(content: @Composable KortexApplicationScope.() -> Unit) {
        application.setContent {
            CompositionLocalProvider(LocalKortexShell provides this@KortexShell) { applicationScope.content() }
        }
    }

    /**
     * Takes every [Show] out of composition, each reporting how its surface ended, then closes every output, runs
     * what is left on the queue and gives the clipboard back, whatever content throws meanwhile.
     *
     * @return [KortexError.ApplicationCrashed] once the application's own code has thrown, an `onClose` called here
     *   included.
     */
    fun close(): EmptyResult<KortexError> {
        display.onGlobalAdded = null
        display.onGlobalRemoved = null
        // Every Show leaves composition here, and reconciling reports how each of their surfaces ended.
        application.close()
        reconcileShows()
        // A disposal that threw can leave Shows in place; that throw ended the application, so they go unreported.
        placed.toList().forEach(::takeDown)
        outputs.values.forEach(ShellOutput::destroy)
        outputs.clear()
        // No pass follows a close, so what reached the queue since the last one runs here.
        loopQueue.drain()
        // After the drain, which can still run a request content made of the clipboard.
        clipboard.close()
        return runResult()
    }

    companion object {
        /**
         * A shell that runs [content] as an application composition. Its surfaces' content copies and pastes through
         * what [contentClipboard] makes of the shell's own clipboard; a test hands it a stand-in.
         */
        fun createApplication(
            display: WaylandDisplay,
            platform: KortexPlatform = KortexPlatform.None,
            contentClipboard: (WaylandClipboard) -> TextClipboard = { it },
            content: @Composable KortexApplicationScope.() -> Unit,
        ): Result<KortexShell, KortexError> {
            // Before the content runs: its surfaces are placed in later passes, where a missing global would reach
            // each one's onClose instead of ending the run.
            requireSurfaceGlobals(display.globals).getOrElse { return Err(it) }
            val loopQueue = LoopQueue(display::wake)
            // Before any surface can take focus: the selection comes as focus arrives, to the devices there are then.
            val clipboard = WaylandClipboard.bind(display, loopQueue).getOrElse { return Err(it) }
            val shell = KortexShell(
                display = display,
                platform = platform,
                loopQueue = loopQueue,
                clipboard = clipboard,
                contentClipboard = contentClipboard(clipboard),
            )
            display.globals
                .filter { it.interfaceName == WL_OUTPUT }
                .forEach(shell::bindOutput)
            // Before the first composition, so rememberMonitors() already lists every output connected now.
            display.roundtrip()
            shell.listMonitors()
            shell.startApplication(content)
            // The first composition runs the application's content, which can already have thrown.
            shell.applicationCrash.get()?.let { crash ->
                shell.close()
                return Err(crash)
            }
            return Ok(shell)
        }

        /** Checks that [globals] has every global a surface binds as it is placed, failing with the first it lacks. */
        internal fun requireSurfaceGlobals(globals: List<WaylandGlobal>): EmptyResult<KortexError.MissingGlobal> =
            SURFACE_GLOBALS
                .firstOrNull { interfaceName -> globals.none { it.interfaceName == interfaceName } }
                ?.let { Err(KortexError.MissingGlobal(it)) }
                ?: Ok(Unit)

        // What LayerShellSurface, Shm and WlCursorTheme bind for each surface. wl_seat is not here: the clipboard binds
        // it as the shell is created, which fails without it.
        private val SURFACE_GLOBALS = listOf("wl_compositor", "wl_shm", "zwlr_layer_shell_v1")

        private const val WL_OUTPUT = "wl_output"

        private const val NANOS_PER_MILLI = 1_000_000L
        private const val PUMP_INTERVAL_MILLIS = 16L
    }
}
