package com.fromwau.kortex.wayland

import com.fromwau.kern.result.getOrElse

// Read by OutputReleaseWireTest, which launches this file's main() in a child JVM under
// WAYLAND_DEBUG=client and greps its stderr for these between the wl_output bind/release lines.
internal const val PROBE_MARKER_BOUND = "KORTEX-PROBE outputs-bound"
internal const val PROBE_MARKER_HOTPLUG_DONE = "KORTEX-PROBE hotplug-done"
internal const val PROBE_MARKER_SHELL_CLOSED = "KORTEX-PROBE shell-closed"

/**
 * Binds every output, hotplugs a headless one and drops it again, then closes the shell and the
 * display, so [OutputReleaseWireTest] can read the wl_output.release requests this sends; nothing
 * in-process shows a request leaving the client.
 */
public fun main() {
    val display = WaylandDisplay.connect().getOrElse { error("probe: no compositor answered: $it") }
    val shell = KortexShell.create(display).getOrElse { error("probe: shell create failed: $it") }
    System.err.println(PROBE_MARKER_BOUND)

    val outputGlobalCount = { display.globals.count { it.interfaceName == "wl_output" } }
    val before = outputGlobalCount()
    var pendingHeadless: String? = null
    try {
        val headless = Hyprctl.createHeadlessOutput()
        pendingHeadless = headless
        check(shell.pump(PROBE_PUMP_TIMEOUT_MILLIS) { outputGlobalCount() > before }) {
            "probe: shell never bound the headless output"
        }

        Hyprctl.removeHeadlessOutput(headless)
        pendingHeadless = null
        check(shell.pump(PROBE_PUMP_TIMEOUT_MILLIS) { outputGlobalCount() == before }) {
            "probe: shell never applied removal of $headless"
        }
    } finally {
        pendingHeadless?.let(Hyprctl::removeHeadlessOutput)
    }

    System.err.println(PROBE_MARKER_HOTPLUG_DONE)
    shell.close()
    System.err.println(PROBE_MARKER_SHELL_CLOSED)
    display.close()
}

private const val PROBE_PUMP_TIMEOUT_MILLIS = 4000L
