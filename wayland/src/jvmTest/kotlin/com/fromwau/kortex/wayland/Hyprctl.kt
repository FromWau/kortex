package com.fromwau.kortex.wayland

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

    fun monitorNames(): Set<String> = MONITOR_NAME.findAll(run("monitors")).map { it.groupValues[1] }.toSet()

    fun run(vararg args: String): String {
        val process = ProcessBuilder("hyprctl", *args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        check(exit == 0) { "hyprctl ${args.joinToString(" ")} exited $exit: $output" }
        return output
    }

    private val MONITOR_NAME = Regex("""^Monitor (\S+) \(ID""", RegexOption.MULTILINE)
}
