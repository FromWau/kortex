package com.fromwau.kortex.wayland

/** A monitor connected to the desktop, as [rememberMonitors] lists it. */
public class Monitor internal constructor(internal val output: ShellOutput) {
    /** The monitor's position, mode and scale. Content that reads it recomposes when its mode or scale changes. */
    public val geometry: OutputGeometry
        get() = checkNotNull(output.listener.geometry) { "a monitor is listed only once its output described itself" }

    /** What the compositor calls the monitor, as `hyprctl monitors` prints it, e.g. `HDMI-A-1`. */
    public val name: String = geometry.name
}
