package com.fromwau.kortex.wayland

import kotlin.math.roundToInt
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One entry of `hyprctl monitors -j`. */
@Serializable
internal data class Monitor(
    val name: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val scale: Float,
    val transform: Int,
) {
    /** Mode size over scale, the logical space both `hyprctl layers` and `configure` report in. */
    val logicalWidth: Int get() = (width / scale).roundToInt()
    val logicalHeight: Int get() = (height / scale).roundToInt()
}

/** One surface entry nested under a monitor's `levels` in `hyprctl layers -j`. */
@Serializable
internal data class LayerEntry(
    val namespace: String,
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
)

/** One monitor's report from `hyprctl layers -j`: its surfaces, keyed by `Layer` ordinal as a string. */
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
    fun monitors(): List<Monitor> = JSON.decodeFromString(run("monitors", "-j"))

    fun monitorNames(): Set<String> = monitors().mapTo(mutableSetOf(), Monitor::name)

    /** Every monitor's layer-shell surfaces, keyed by monitor name. */
    fun layers(): Map<String, MonitorLayers> = JSON.decodeFromString(run("layers", "-j"))

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
