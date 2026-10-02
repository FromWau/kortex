package com.fromwau.kortex.hyprland

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.flatMap
import com.fromwau.kern.result.getOrElse
import com.fromwau.kern.result.map
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.builtins.ListSerializer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Hyprland's monitors and workspaces, the focused window, the submap and the keyboard layout, as data.
 *
 * ```kotlin
 * val hyprland = Hyprland(scope)
 * val monitors by hyprland.monitors.collectAsState()
 * ```
 *
 * Each flow listens on Hyprland's event socket while it has a collector and nothing runs while it has none.
 * An event only says that something changed: the flow then asks Hyprland for the whole current state, so
 * what it carries is always what Hyprland answered rather than a copy kept up to date by hand. Events that
 * arrive while an answer is being read are folded into one more request. If Hyprland closes the event
 * socket, the flow carries [HyprlandError.Disconnected] and keeps trying to connect again.
 */
public class Hyprland private constructor(
    scope: CoroutineScope,
    private val instance: Result<HyprlandInstance, HyprlandError>,
    private val retryAfter: Duration,
) {
    /** Follows the Hyprland this process was started under, or carries [HyprlandError.NoInstance]. */
    public constructor(scope: CoroutineScope) : this(scope, HyprlandInstance.fromEnvironment(), RETRY_AFTER)

    /** Follows the Hyprland whose sockets are in [instance]. */
    public constructor(
        scope: CoroutineScope,
        instance: HyprlandInstance,
    ) : this(scope, Ok(instance), RETRY_AFTER)

    internal constructor(
        scope: CoroutineScope,
        instance: HyprlandInstance,
        retryAfter: Duration,
    ) : this(scope, Ok(instance), retryAfter)

    /**
     * Every monitor with the workspaces on it, or why there are none.
     *
     * A workspace moved to another monitor, a monitor plugged in or out and a special workspace opened all
     * arrive here. The first value is [HyprlandError.NotConnected], since a flow always holds one and an
     * empty list would read as a Hyprland with no monitors.
     */
    public val monitors: StateFlow<Result<List<Monitor>, HyprlandError>> = flow {
        val urgent = UrgentWindows()
        val read = follow(MONITOR_EVENTS, observe = urgent::observe) { at -> readMonitors(at, urgent.addresses) }
        emitAll(read)
    }.stateIn(scope, SharingStarted.WhileSubscribed(), Err(HyprlandError.NotConnected))

    /** The window with keyboard focus, null while nothing has it, or why that cannot be known. */
    public val activeWindow: StateFlow<Result<ActiveWindow?, HyprlandError>> = follow(WINDOW_EVENTS) { at ->
        request(at.requests, ACTIVE_WINDOW).flatMap(::activeWindowFrom)
    }.stateIn(scope, SharingStarted.WhileSubscribed(), Err(HyprlandError.NotConnected))

    /**
     * The submap keybinds are in, null in the default one.
     *
     * Hyprland names the default `default` when asked, so a submap somebody named `default` reads as null too.
     */
    public val submap: StateFlow<Result<String?, HyprlandError>> = follow(setOf(SUBMAP_EVENT)) { at ->
        request(at.requests, SUBMAP).flatMap(::submapFrom)
    }.stateIn(scope, SharingStarted.WhileSubscribed(), Err(HyprlandError.NotConnected))

    /** The layout the main keyboard types in, null while Hyprland calls no keyboard main. */
    public val keyboardLayout: StateFlow<Result<KeyboardLayout?, HyprlandError>> =
        follow(setOf(LAYOUT_EVENT)) { at ->
            request(at.requests, DEVICES).flatMap(::keyboardLayoutFrom)
        }.stateIn(scope, SharingStarted.WhileSubscribed(), Err(HyprlandError.NotConnected))

    /**
     * Shows [workspace], one of those [monitors] lists: numbered, named or special.
     *
     * A special workspace is shown over the focused monitor's own rather than in its place.
     *
     * @return `Ok` once Hyprland has accepted the switch. The switch itself arrives through [monitors].
     */
    public suspend fun focusWorkspace(workspace: Workspace): EmptyResult<HyprlandError> =
        focus(workspace.selector())

    /**
     * Shows the workspace numbered [number] on the focused monitor, creating it if it does not exist.
     *
     * @return `Ok` once Hyprland has accepted the switch, or [HyprlandError.NotNumbered] for a [number] below 1,
     *   which Hyprland would read as something else: a leading `-` as a move relative to the current workspace.
     */
    public suspend fun focusWorkspace(number: Int): EmptyResult<HyprlandError> = when {
        number < 1 -> Err(HyprlandError.NotNumbered(number))
        else -> focus("$number")
    }

    private suspend fun focus(selector: String): EmptyResult<HyprlandError> =
        dispatch("hl.dsp.focus({ workspace = ${luaString(selector)} })")

    /**
     * Runs one of Hyprland's dispatchers, written as the Lua that `dispatch` takes from Hyprland 0.56 on.
     *
     * ```kotlin
     * hyprland.dispatch("hl.dsp.window.close()")
     * ```
     *
     * @param lua sent exactly as given: nothing in it is escaped or checked.
     * @return `Ok` once Hyprland answers `ok`, or [HyprlandError.Refused] with what it answered instead,
     *   which for Lua it cannot run is the error Lua gave.
     */
    public suspend fun dispatch(lua: String): EmptyResult<HyprlandError> {
        val at = instance.getOrElse { return Err(it) }
        val command = "dispatch $lua"
        return request(at.requests, command).flatMap { answer ->
            when (val said = answer.trim()) {
                ACCEPTED -> Ok(Unit)
                else -> Err(HyprlandError.Refused(command, said))
            }
        }
    }

    private suspend fun readMonitors(
        at: HyprlandInstance,
        urgentWindows: Set<WindowAddress>,
    ): Result<List<Monitor>, HyprlandError> {
        val workspaces = request(at.requests, WORKSPACES)
            .flatMap { decode(WORKSPACES, it, ListSerializer(WorkspaceReply.serializer())) }
            .getOrElse { return Err(it) }
        val monitors = request(at.requests, MONITORS)
            .flatMap { decode(MONITORS, it, ListSerializer(MonitorReply.serializer())) }
            .getOrElse { return Err(it) }
        // Asked only while something is urgent, which is rarely, rather than on every workspace switch.
        val urgent = when {
            urgentWindows.isEmpty() -> emptySet()
            else -> request(at.requests, CLIENTS)
                .flatMap { decode(CLIENTS, it, ListSerializer(ClientReply.serializer())) }
                .map { clients -> workspacesHolding(urgentWindows, clients) }
                .getOrElse { return Err(it) }
        }
        return Ok(monitorsFrom(workspaces, monitors, urgent))
    }

    /**
     * [read] once the event socket is listening, and again after every event named in [refreshOn] or that
     * [observe] says changed something.
     *
     * [observe] sees every event, ahead of the folding, so one that only changes state is never folded away.
     */
    private fun <T> follow(
        refreshOn: Set<String>,
        observe: (Tick.Event) -> Boolean = { false },
        read: suspend (HyprlandInstance) -> Result<T, HyprlandError>,
    ): Flow<Result<T, HyprlandError>> = flow {
        val at = instance.getOrElse {
            emit(Err(it))
            return@flow
        }
        while (true) {
            events(at.events)
                .filter { tick -> tick.calls(refreshOn, observe) }
                .conflate()
                .collect { tick ->
                    when (tick) {
                        is Ok -> emit(read(at))
                        is Err -> emit(tick)
                    }
                }
            delay(retryAfter)
        }
    }

    /** Whether this calls for a read: a connection or an error always does, an event if it is named or observed. */
    private fun Result<Tick, HyprlandError>.calls(
        refreshOn: Set<String>,
        observe: (Tick.Event) -> Boolean,
    ): Boolean {
        val event = (this as? Ok)?.value as? Tick.Event ?: return true
        // Observed first and always, so the state an event changes is kept even when its name also refreshes.
        val changed = observe(event)
        return changed || event.name in refreshOn
    }

    private companion object {
        val RETRY_AFTER = 1.seconds

        const val ACCEPTED = "ok"

        // The v2 name wherever Hyprland sends two versions of one event, so a change is read once, not twice.
        val WINDOW_COUNTS = setOf("openwindow", "closewindow", "movewindowv2")

        val MONITOR_EVENTS = WINDOW_COUNTS + setOf(
            "workspacev2",
            "createworkspacev2",
            "destroyworkspacev2",
            "moveworkspacev2",
            "renameworkspace",
            // workspacev2 fires only when a workspace is asked for, not when the pointer crosses monitors.
            "focusedmonv2",
            "activespecialv2",
            "monitoraddedv2",
            "monitorremovedv2",
            // A reload applies the config's monitor and workspace rules over again.
            "configreloaded",
        )

        val WINDOW_EVENTS = WINDOW_COUNTS + setOf("activewindowv2", "windowtitlev2")

        const val SUBMAP_EVENT = "submap"
        const val LAYOUT_EVENT = "activelayout"
    }
}
