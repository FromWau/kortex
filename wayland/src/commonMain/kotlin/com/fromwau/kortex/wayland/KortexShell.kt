package com.fromwau.kortex.wayland

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
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
 * The engine behind [kortexApplication]: one connection's outputs, clipboard and loop, the application composition,
 * and a surface for each surface call in it.
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

    // Once the connection dies, every call leaves because of it, and reports its error. Loop thread only.
    private var endingOnLeave: Result<SurfaceEnd, SurfaceError<IError>> = Ok(SurfaceEnd.LeftComposition)

    private val application = ApplicationComposition(loopQueue, display::wake, ::applicationFailed)

    private val applicationScope = object : KortexApplicationScope {
        override fun exitApplication() {
            exitRequested = true
            display.wake()
        }
    }

    // Each slot whose surface is on screen, in the order placed: one in a surface's content after that surface's own.
    private val placed = mutableListOf<SurfaceSlot>()

    // Filled by each surface call's effects, which run inside a composition's apply, and acted on in the next pass.
    private val changedSlots = ConcurrentLinkedQueue<SurfaceSlot>()

    /** Every surface a call holds on screen, in the order they were placed; a test reads them. */
    internal val shownSurfaces: List<KortexSurface> get() = placed.mapNotNull { it.surface }

    /** What each slot queued since the last pass asks for, before that pass places anything; a test reads them. */
    internal val queuedSettings: List<SurfaceSettings> get() = changedSlots.mapNotNull { it.wanted }

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
                return display.requireAlive()
                    .flatMap { Err(KortexError.NoCompositorResponse) }
                    .onError { lost -> endingOnLeave = Err(SurfaceError.Failed(lost)) }
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
        reconcileSlots()
        // Reconciling calls each onClose, and one that threw there ends the run here.
        return runResult()
    }

    private fun serviceSurfaces(): EmptyResult<KortexError> {
        // First: content running here posts to the surface queues each tick drains.
        loopQueue.runPass()
        // After the pass, which runs the snapshot pump that asks the application for a frame.
        application.frame()
        // A shown surface that fails its tick ends by itself, as Failed; the run goes on.
        placed.forEach { slot ->
            slot.surface?.serviceTick()?.onError { reason -> slot.requestEnd(Err(SurfaceError.Failed(reason))) }
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
            .forEach { slot ->
                when (val own = slot.ownEnding()) {
                    is OwnEnding.Ended -> end(slot, own.ending)
                    OwnEnding.NotEnded -> end(slot, Ok(SurfaceEnd.MonitorUnplugged))
                }
            }
        output.destroy()
    }

    /** Makes the loop run a pass soon, from any thread. */
    internal fun wake() {
        display.wake()
    }

    /** [slot]'s call entered composition, or its settings changed: its surface is placed in the next pass. */
    internal fun queueUpdate(
        slot: SurfaceSlot,
        settings: SurfaceSettings,
    ) {
        slot.wanted = settings
        changedSlots += slot
        display.wake()
    }

    /** [slot]'s call left composition. */
    internal fun queueRemove(slot: SurfaceSlot) {
        slot.wanted = null
        changedSlots += slot
        display.wake()
    }

    private fun reconcileSlots() {
        // Every placed slot, for an ending of its own, and every slot that changed.
        (placed.toList() + generateSequence(changedSlots::poll)).forEach(::reconcile)
    }

    private fun reconcile(slot: SurfaceSlot) {
        if (slot.reported) return
        // Once the application's own code has thrown, surfaces only go: nothing more is placed.
        if (applicationCrash.get() != null) return end(slot, endingOnLeave)
        val ownEnding = slot.ownEnding()
        // Not left to the call's own dispose, which Compose skips once an earlier cleanup in that content throws.
        val wanted = slot.wanted.takeUnless { slot.parentGone }
        when {
            // First: a surface that ended by itself before its call left reports how it ended.
            ownEnding is OwnEnding.Ended -> end(slot, ownEnding.ending)
            wanted == null -> end(slot, endingOnLeave)
            slot.surface == null -> place(slot, wanted)
            slot.placedWith != wanted -> replace(slot, wanted)
        }
    }

    // The call is still in composition, so nothing has ended for its host, unless its content failed as it went.
    private fun replace(
        slot: SurfaceSlot,
        settings: SurfaceSettings,
    ) {
        val crash = takeDown(slot) ?: return place(slot, settings)
        report(slot, Err(SurfaceError.Failed(crash)))
    }

    private fun place(
        slot: SurfaceSlot,
        settings: SurfaceSettings,
    ) {
        val output = settings.monitor?.let { monitor ->
            // An unplugged monitor's proxy is already destroyed: the surface ends, as one on it does when it goes.
            monitor.output.takeIf { outputs[it.name] === it } ?: return report(slot, Ok(SurfaceEnd.MonitorUnplugged))
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
                slot.surface = surface
                surface
                    .setContent { ShownContent(slot) }
                    .onError {
                        slot.surface = null
                        surface.close()
                    }
            }
            .onSuccess {
                slot.placedWith = settings
                placed += slot
            }
            .onError { reason -> report(slot, Err(SurfaceError.Failed(reason))) }
    }

    /** What a shown surface's scene composes: its call's newest content, and around it what content reaches. */
    @Composable
    private fun ShownContent(slot: SurfaceSlot) {
        CompositionLocalProvider(
            LocalKortexShell provides this,
            LocalSurfaceSlot provides slot,
            LocalKortexSurface provides slot.scope,
            LocalKortexClipboard provides hostClipboard,
        ) {
            ProvideClipboard(contentClipboard) { slot.content.value(slot.scope) }
        }
    }

    private fun end(
        slot: SurfaceSlot,
        ending: Result<SurfaceEnd, SurfaceError<IError>>,
    ) {
        // Content failing, its cleanup as the surface goes included, ends it as a crash whatever else ended it.
        val crash = takeDown(slot)
        report(slot, crash?.let { Err(SurfaceError.Failed(it)) } ?: ending)
    }

    /**
     * Closes [slot]'s surface, if any, and returns its content's crash, including one its cleanup threw while closing.
     */
    private fun takeDown(slot: SurfaceSlot): KortexError.SurfaceCrashed? {
        val surface = slot.surface ?: return null
        placed.remove(slot)
        slot.surface = null
        surface.close()
        return surface.crash
    }

    private fun report(
        slot: SurfaceSlot,
        ending: Result<SurfaceEnd, SurfaceError<IError>>,
    ) {
        slot.reported = true
        // Once the application's own code has thrown, none of it runs again.
        if (applicationCrash.get() != null) return
        runHostCode(::applicationFailed) { slot.report(ending) }
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
     * Takes every surface call out of composition, each reporting how its surface ended, then closes every output,
     * runs what is left on the queue and gives the clipboard back, whatever content throws meanwhile.
     *
     * @return [KortexError.ApplicationCrashed] once the application's own code has thrown, an `onClose` called here
     *   included.
     */
    fun close(): EmptyResult<KortexError> {
        display.onGlobalAdded = null
        display.onGlobalRemoved = null
        // Every surface call leaves composition here, and reconciling reports how each of their surfaces ended.
        application.close()
        reconcileSlots()
        // A disposal that threw can leave slots in place; that throw ended the application, so they go unreported.
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
