package com.fromwau.kortex.wayland

import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.fail

/**
 * Moves the pointer to logical ([x], [y]) on [monitor], the space [Screen.geometry] reports in.
 *
 * The two spaces line up only because this suite runs against a single output pinned at the
 * compositor's origin. Driving the client afterwards is the caller's, since what has to be pumped to
 * see the motion differs per test.
 */
internal fun VirtualPointer.moveTo(monitor: Monitor, x: Int, y: Int) {
    motionAbsolute(x, y, monitor.logicalWidth, monitor.logicalHeight)
    frame()
}

/**
 * Where the compositor placed a layer surface, and which monitor and [Layer] it landed on.
 *
 * hyprctl reports the geometry in logical (surface-local) pixels, the same space `configure` uses —
 * not the buffer pixels a [KortexSurface] reports, which are larger by the output scale.
 */
internal data class LayerGeometry(
    val monitor: String,
    val layer: Layer,
    val x: Int,
    val y: Int,
    val logicalWidth: Int,
    val logicalHeight: Int,
) {
    /** The `x,y WxH` form grim's `-g` expects. */
    val grimArea: String get() = "$x,$y ${logicalWidth}x$logicalHeight"

    override fun toString(): String = grimArea
}

/** Reads back what a layer surface actually put on screen. */
internal object Screen {
    /** Where the layer named [namespace] is, or null if it is not mapped on any monitor. */
    fun geometry(namespace: String): LayerGeometry? {
        for ((monitor, monitorLayers) in Hyprctl.layers()) {
            for ((level, entries) in monitorLayers.levels) {
                val entry = entries.firstOrNull { it.namespace == namespace } ?: continue
                return LayerGeometry(
                    monitor = monitor,
                    layer = layerAt(level),
                    x = entry.x,
                    y = entry.y,
                    logicalWidth = entry.w,
                    logicalHeight = entry.h,
                )
            }
        }
        return null
    }

    private fun layerAt(level: String): Layer =
        Layer.entries.firstOrNull { it.wireValue == level.toInt() }
            ?: error("hyprctl layers reported level $level, which is no Layer")

    /**
     * The centre pixel, once it has held the same value long enough to be the finished one.
     *
     * Hyprland ramps a new layer surface up over roughly 850ms, one step per frame, and the tail of that
     * ramp is its slowest part: measured steps sit 70 to 125ms apart, and the value wobbles by a step
     * either way as it lands. Two reads that merely agree therefore prove nothing, so this waits for a
     * value to survive [STABLE_WINDOW_MILLIS], which is comfortably past the widest step seen.
     */
    fun settledPixel(geometry: LayerGeometry): Int {
        val deadline = System.nanoTime() + SETTLE_TIMEOUT_MILLIS * NANOS_PER_MILLI
        var value = readPixel(geometry)
        var heldSince = System.nanoTime()
        while (System.nanoTime() < deadline) {
            Thread.sleep(SETTLE_INTERVAL_MILLIS)
            val next = readPixel(geometry)
            if (next != value) {
                value = next
                heldSince = System.nanoTime()
            } else if (System.nanoTime() - heldSince >= STABLE_WINDOW_MILLIS * NANOS_PER_MILLI) {
                return value
            }
        }
        fail("the pixel never held one value for $STABLE_WINDOW_MILLIS ms; last read " + "0x%08X".format(value))
    }

    private fun readPixel(geometry: LayerGeometry): Int {
        val shot = File.createTempFile("kortex-capture", ".png")
        try {
            val grim = ProcessBuilder("grim", "-g", geometry.grimArea, shot.absolutePath)
                .redirectErrorStream(true).start()
            assertEquals(0, grim.waitFor(), "grim failed: " + grim.inputStream.bufferedReader().readText())
            val image = assertNotNull(ImageIO.read(shot), "grim produced no readable image")
            return image.getRGB(image.width / 2, image.height / 2)
        } finally {
            shot.delete()
        }
    }

    private const val SETTLE_INTERVAL_MILLIS = 20L
    private const val STABLE_WINDOW_MILLIS = 400L
    private const val SETTLE_TIMEOUT_MILLIS = 5000L
    private const val NANOS_PER_MILLI = 1_000_000L
}
