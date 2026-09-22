package com.fromwau.kortex.wayland

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One entry of `hyprctl monitors -j`. */
@Serializable
internal data class HyprMonitor(
    val name: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val scale: Float,
    val transform: Int,
    val reserved: List<Int>,
) {
    /** Mode size over scale, the logical space both `hyprctl layers` and `configure` report in. */
    val logicalWidth: Int get() = (width / scale).roundToInt()
    val logicalHeight: Int get() = (height / scale).roundToInt()

    /** What the exclusive zones held here leave over: the box the compositor arranges everything else in. */
    val usableX: Int get() = x + reserved[LEFT]
    val usableY: Int get() = y + reserved[TOP]
    val usableWidth: Int get() = logicalWidth - reserved[LEFT] - reserved[RIGHT]
    val usableHeight: Int get() = logicalHeight - reserved[TOP] - reserved[BOTTOM]

    /** What the exclusive zones held here reserve against [edge]. */
    fun reservedAgainst(edge: Edge): Int = when (edge) {
        Edge.Left -> reserved[LEFT]
        Edge.Top -> reserved[TOP]
        Edge.Right -> reserved[RIGHT]
        Edge.Bottom -> reserved[BOTTOM]
    }
}

// hyprctl reports `reserved` in this order, which is neither CSS's nor set_margin's.
private const val LEFT = 0
private const val TOP = 1
private const val RIGHT = 2
private const val BOTTOM = 3

/** One window of `hyprctl clients -j`, which lists only windows the compositor has mapped. */
internal data class HyprWindow(
    /** What the compositor calls this window, which is another one as soon as it is made again. */
    val address: String,
    val title: String,
    /** What Hyprland calls a window's class, which for a Wayland window is its `xdg_toplevel` app id. */
    val appId: String,
    /** The size the compositor settled on, which is the size the window's last `configure` carried. */
    val size: IntSize,
    val at: IntOffset,
    val floating: Boolean,
    val fullscreen: Boolean,
    val pinned: Boolean,
    val workspace: String,
)

/** One entry of `hyprctl clients -j`, in the shape Hyprland writes it. */
@Serializable
private data class ClientEntry(
    val address: String,
    val title: String,
    @SerialName("class") val appId: String,
    val at: List<Int>,
    val size: List<Int>,
    val floating: Boolean,
    val pinned: Boolean,
    val fullscreen: Int,
    val workspace: WorkspaceEntry,
)

/** The workspace a window of `hyprctl clients -j` is on. */
@Serializable
private data class WorkspaceEntry(val name: String)

private fun ClientEntry.toWindow(): HyprWindow = HyprWindow(
    address = address,
    title = title,
    appId = appId,
    size = IntSize(size[X], size[Y]),
    at = IntOffset(at[X], at[Y]),
    floating = floating,
    // hyprctl reports which of the compositor's fullscreen modes a window is in, not whether it is in one.
    fullscreen = fullscreen != WINDOWED,
    pinned = pinned,
    workspace = workspace.name,
)

// hyprctl writes a window's position and its size each as a two-element array.
private const val X = 0
private const val Y = 1
private const val WINDOWED = 0

/** One surface entry nested under a monitor's `levels` in `hyprctl layers -j`. */
@Serializable
internal data class LayerEntry(
    val namespace: String,
    /** What the compositor calls this layer surface, which is another one as soon as it is made again. */
    val address: String,
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
)

/** One monitor's report from `hyprctl layers -j`: its surfaces, keyed by [Layer]'s wire value as a string. */
@Serializable
internal data class MonitorLayers(val levels: Map<String, List<LayerEntry>>)

/**
 * Drives the compositor from a test.
 *
 * `hyprctl output create/remove headless` is the only way to add or remove a real `wl_output` here.
 *
 * This desktop's Hyprland reads a Lua configuration, so `hyprctl dispatch` runs `hl.dispatch(...)` over what it
 * is given rather than the keyword form: a dispatcher written the keyword way is a Lua syntax error.
 */
internal object Hyprctl {
    /** Creates a headless output, returning the name Hyprland assigned it. */
    fun createHeadlessOutput(): String {
        val before = monitorNames()
        val result = run("output", "create", "headless")
        check(result.trim().equals("ok", ignoreCase = true)) { "hyprctl output create headless failed: $result" }
        val added = monitorNames() - before
        check(added.size == 1) { "expected exactly one new monitor, got $added (before=$before)" }
        return added.single()
    }

    fun removeHeadlessOutput(name: String) {
        val result = run("output", "remove", name)
        check(result.trim().equals("ok", ignoreCase = true)) { "hyprctl output remove $name failed: $result" }
    }

    /** Every connected monitor, in the order Hyprland lists them. */
    fun monitors(): List<HyprMonitor> = JSON.decodeFromString(run("monitors", "-j"))

    fun monitorNames(): Set<String> = monitors().mapTo(mutableSetOf(), HyprMonitor::name)

    fun monitor(name: String): HyprMonitor =
        checkNotNull(monitors().firstOrNull { it.name == name }) { "hyprctl lost monitor $name" }

    /** Every window the compositor has mapped, in the order Hyprland lists them. */
    fun windows(): List<HyprWindow> = JSON
        .decodeFromString<List<ClientEntry>>(run("clients", "-j"))
        .map { it.toWindow() }

    /** The one window the compositor lists under [title], or null while it lists none. */
    fun window(title: String): HyprWindow? = windows().firstOrNull { it.title == title }

    /** Every monitor's layer-shell surfaces, keyed by monitor name. */
    fun layers(): Map<String, MonitorLayers> = JSON.decodeFromString(run("layers", "-j"))

    /** Every layer surface's namespace across every monitor and level, including one that has no buffer yet. */
    fun namespaces(): List<String> = layers()
        .values
        .flatMap { it.levels.values.flatten() }
        .map { it.namespace }

    /**
     * Runs one of the compositor's window dispatchers against the window at [address], never the active window,
     * which during a test run is whatever the user is working in.
     *
     * [dispatcher] names it as the compositor's own configuration language does, such as `window.float`, and
     * [fields] carry its other arguments, such as `direction = "left"`.
     */
    fun dispatch(dispatcher: String, vararg fields: String, address: String) {
        val arguments = (listOf("window = \"address:$address\"") + fields).joinToString(", ")
        val request = "hl.dsp.$dispatcher{ $arguments }"
        val result = run("dispatch", request)
        check(result.trim().equals("ok", ignoreCase = true)) { "hyprctl dispatch $request failed: $result" }
    }

    fun run(vararg args: String): String {
        val process = ProcessBuilder("hyprctl", *args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        check(exit == 0) { "hyprctl ${args.joinToString(" ")} exited $exit: $output" }
        return output
    }

    // hyprctl reports far more per monitor than any test reads, and adds fields between releases.
    private val JSON = Json { ignoreUnknownKeys = true }
}
