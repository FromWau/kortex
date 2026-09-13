package com.fromwau.kortex.wayland

import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

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
 * Clicks the left button at logical ([x], [y]) on [monitor]. The move and both buttons reach the compositor
 * together, so no other pointer device's motion can come between them and carry the click off its target.
 */
internal fun VirtualPointer.clickAt(monitor: Monitor, x: Int, y: Int) {
    moveTo(monitor, x, y)
    button(BTN_LEFT, pressed = true)
    frame()
    button(BTN_LEFT, pressed = false)
    frame()
}

// linux/input-event-codes.h
private const val BTN_LEFT = 0x110

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
    /** Where the layer named [namespace] is, or null if no monitor lists it. */
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

    /** [geometry], polled at the same interval and within the same budget [pixelReaching] settles for. */
    fun awaitGeometry(namespace: String): LayerGeometry? {
        val deadline = System.nanoTime() + SETTLE_TIMEOUT_MILLIS * NANOS_PER_MILLI
        var found = geometry(namespace)
        while (found == null && System.nanoTime() < deadline) {
            Thread.sleep(SETTLE_INTERVAL_MILLIS)
            found = geometry(namespace)
        }
        return found
    }

    private fun layerAt(level: String): Layer =
        Layer.entries.firstOrNull { it.wireValue == level.toInt() }
            ?: error("hyprctl layers reported level $level, which is no Layer")

    /**
     * The centre pixel once it reads [target], or the last value read within the budget if it never does.
     *
     * Waiting for the value the caller expects, rather than for any value that holds still, is what makes
     * this immune to Hyprland's fade: a new layer surface ramps up to its own colour over roughly 850ms,
     * one step per frame, and the slow tail of that ramp holds each step long enough to pass for settled.
     *
     * It reads the composited screen rather than the surface's buffer, so anything drawn over the sampled
     * point fails it as well. A notification or a popup opened during a run reads as its own colour, held
     * for the whole budget, where the fade only ever reads as a step on the way to [target].
     */
    fun pixelReaching(geometry: LayerGeometry, target: Int): Int {
        val deadline = System.nanoTime() + SETTLE_TIMEOUT_MILLIS * NANOS_PER_MILLI
        var value = readPixel(geometry)
        while (value != target && System.nanoTime() < deadline) {
            Thread.sleep(SETTLE_INTERVAL_MILLIS)
            value = readPixel(geometry)
        }
        return value
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

    private const val SETTLE_TIMEOUT_MILLIS = 5000L
    private const val NANOS_PER_MILLI = 1_000_000L
}
