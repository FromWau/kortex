package com.fromwau.kortex.wayland

import kotlin.math.roundToInt

/** One entry of `hyprctl monitors -j`. */
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
    fun monitors(): List<Monitor> = topLevelObjects(run("monitors", "-j")).map { entry ->
        Monitor(
            name = field(entry, "name"),
            x = int(entry, "x"),
            y = int(entry, "y"),
            width = int(entry, "width"),
            height = int(entry, "height"),
            scale = field(entry, "scale").toFloat(),
            transform = int(entry, "transform"),
        )
    }

    fun monitorNames(): Set<String> = monitors().mapTo(mutableSetOf(), Monitor::name)

    fun run(vararg args: String): String {
        val process = ProcessBuilder("hyprctl", *args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        check(exit == 0) { "hyprctl ${args.joinToString(" ")} exited $exit: $output" }
        return output
    }

    // Splitting on brace depth keeps each monitor's fields together; scanning the whole document for
    // a field name instead would read the first monitor's value for every monitor.
    private fun topLevelObjects(json: String): List<String> {
        val objects = mutableListOf<String>()
        var depth = 0
        var start = 0
        json.forEachIndexed { index, char ->
            when (char) {
                '{' -> if (depth++ == 0) start = index
                '}' -> if (--depth == 0) objects += json.substring(start, index + 1)
            }
        }
        return objects
    }

    private fun field(entry: String, name: String): String =
        checkNotNull(Regex("\"$name\": *\"?([^,\"}\\s]+)").find(entry)?.groupValues?.get(1)) {
            "hyprctl monitors -j has no $name field in: $entry"
        }

    private fun int(entry: String, name: String): Int = field(entry, name).toInt()
}
