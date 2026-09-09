package com.fromwau.kortex.wayland

import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Where the compositor placed a layer surface, and which monitor and [Layer] it landed on.
 *
 * hyprctl reports the geometry in logical (surface-local) pixels, the same space `configure` uses —
 * not the buffer pixels a [KortexBar] reports, which are larger by the output scale.
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
                    layer = Layer.entries.first { it.value == level.toInt() },
                    x = entry.x,
                    y = entry.y,
                    logicalWidth = entry.w,
                    logicalHeight = entry.h,
                )
            }
        }
        return null
    }

    /**
     * The centre pixel, sampled until two consecutive reads agree.
     *
     * Hyprland's `fadeLayersIn` animation ramps a new layer surface up over several frames, so a single
     * capture reads a blended value.
     */
    fun settledPixel(geometry: LayerGeometry): Int {
        var previous = Int.MIN_VALUE
        repeat(MAX_SETTLE_READS) {
            Thread.sleep(SETTLE_INTERVAL_MILLIS)
            val shot = File.createTempFile("kortex-capture", ".png")
            try {
                val grim = ProcessBuilder("grim", "-g", geometry.grimArea, shot.absolutePath)
                    .redirectErrorStream(true).start()
                assertEquals(0, grim.waitFor(), "grim failed: " + grim.inputStream.bufferedReader().readText())
                val image = assertNotNull(ImageIO.read(shot), "grim produced no readable image")
                val pixel = image.getRGB(image.width / 2, image.height / 2)
                if (pixel == previous) return pixel
                previous = pixel
            } finally {
                shot.delete()
            }
        }
        return previous
    }

    private const val SETTLE_INTERVAL_MILLIS = 90L
    private const val MAX_SETTLE_READS = 25
}
