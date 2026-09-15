package com.fromwau.kortex.wayland

import com.fromwau.kern.result.getOrElse

// Read by OutputReleaseWireTest, which launches this file's main() in a child JVM under
// WAYLAND_DEBUG=client and greps its stderr for these between the wl_output bind/release lines.
internal const val PROBE_MARKER_BOUND = "KORTEX-PROBE outputs-bound"
internal const val PROBE_MARKER_HOTPLUG_DONE = "KORTEX-PROBE hotplug-done"
internal const val PROBE_MARKER_SHELL_CLOSED = "KORTEX-PROBE shell-closed"

/** Which of the probe's two wire sequences a run performs, selected by [main]'s one argument. */
internal enum class ProbeMode {
    ShellCloseOnly,
    HotplugThenShellClose,
    ;

    companion object {
        fun fromOrNull(raw: String): ProbeMode? = entries.firstOrNull { it.name == raw }
    }
}

/**
 * Binds every output, then either closes the shell directly or hotplugs a headless output and drops it
 * first, so [OutputReleaseWireTest] can read the wl_output.release requests this sends.
 */
public fun main(args: Array<String>) {
    val mode = args.singleOrNull()?.let(ProbeMode::fromOrNull)
        ?: error("probe: expected one argument, one of ${ProbeMode.entries}, got ${args.toList()}")

    val display = WaylandDisplay.connect().getOrElse { error("probe: no compositor answered: $it") }
    val shell = KortexShell.createApplication(display) { }.getOrElse { error("probe: shell create failed: $it") }
    System.err.println(PROBE_MARKER_BOUND)

    when (mode) {
        ProbeMode.HotplugThenShellClose -> {
            hotplugAndDrop(display, shell)
            System.err.println(PROBE_MARKER_HOTPLUG_DONE)
        }
        ProbeMode.ShellCloseOnly -> Unit
    }

    shell.close()
    System.err.println(PROBE_MARKER_SHELL_CLOSED)
    display.close()
}

private fun hotplugAndDrop(
    display: WaylandDisplay,
    shell: KortexShell,
) {
    val outputGlobalCount = { display.globals.count { it.interfaceName == "wl_output" } }
    val before = outputGlobalCount()
    var pendingHeadless: String? = null
    try {
        val headless = Hyprctl.createHeadlessOutput()
        pendingHeadless = headless
        check(shell.pumpOrFail(PROBE_PUMP_TIMEOUT_MILLIS) { outputGlobalCount() > before }) {
            "probe: shell never bound the headless output"
        }

        Hyprctl.removeHeadlessOutput(headless)
        pendingHeadless = null
        check(shell.pumpOrFail(PROBE_PUMP_TIMEOUT_MILLIS) { outputGlobalCount() == before }) {
            "probe: shell never applied removal of $headless"
        }
    } finally {
        pendingHeadless?.let(Hyprctl::removeHeadlessOutput)
    }
}

private const val PROBE_PUMP_TIMEOUT_MILLIS = 4000L
