package com.fromwau.kortex.wayland

import kotlin.math.roundToInt
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

    /** Every monitor's layer-shell surfaces, keyed by monitor name. */
    fun layers(): Map<String, MonitorLayers> = JSON.decodeFromString(run("layers", "-j"))

    /** Every layer surface's namespace across every monitor and level, including one that has no buffer yet. */
    fun namespaces(): List<String> = layers()
        .values
        .flatMap { it.levels.values.flatten() }
        .map { it.namespace }

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
