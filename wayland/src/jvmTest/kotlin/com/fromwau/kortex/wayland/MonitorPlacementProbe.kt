package com.fromwau.kortex.wayland

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue

// Read by MonitorTest, which runs this in a child JVM under WAYLAND_DEBUG=client: libwayland reads that once per
// process and never clears it, so in the JVM running the tests it would log every later connection.
internal const val MONITOR_PROBE_MARKER = "KORTEX-PROBE shown on "
internal const val MONITOR_PROBE_RESULT = "KORTEX-PROBE result "
internal const val MONITOR_PROBE_NAMESPACE = "kortex-monitor-probe"

/** Shows a speck on the first monitor its application lists, prints that monitor's name, and ends once it has drawn. */
object MonitorPlacementProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val result = kortexApplication {
            val application = this
            val monitors by rememberMonitors()
            monitors.firstOrNull()?.let { monitor ->
                TestSurface(MONITOR_PROBE_NAMESPACE, monitor = monitor) {
                    LaunchedEffect(Unit) {
                        System.err.println("$MONITOR_PROBE_MARKER${monitor.name}")
                        application.exitApplication()
                    }
                }
            }
        }
        System.err.println("$MONITOR_PROBE_RESULT$result")
    }
}
