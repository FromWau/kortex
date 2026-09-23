package com.fromwau.kortex.wayland

/**
 * A monitor connected to the desktop, as [rememberMonitors] lists it. Pass one as [LayerSurface.monitor] to put a
 * surface on it.
 *
 * Two `Monitor`s are equal when they stand for the same monitor, so a new [geometry] makes no new monitor and replaces
 * no surface shown on it. A monitor unplugged and plugged in again is a new `Monitor`.
 */
public class Monitor internal constructor(internal val output: ShellOutput) {
    /**
     * The monitor's position, mode, scale and logical size. Content that reads it recomposes when any of them
     * changes.
     */
    public val geometry: OutputGeometry
        get() = checkNotNull(output.listener.geometry) { "a monitor is listed only once its output described itself" }

    /** What the compositor calls the monitor, as `hyprctl monitors` prints it, e.g. `HDMI-A-1`. */
    public val name: String = geometry.name

    override fun equals(other: Any?): Boolean = other is Monitor && other.output === output

    override fun hashCode(): Int = output.hashCode()

    override fun toString(): String = "Monitor($name)"
}
