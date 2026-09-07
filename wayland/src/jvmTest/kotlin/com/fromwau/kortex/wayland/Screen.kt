package com.fromwau.kortex.wayland

import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Where the compositor placed a layer surface.
 *
 * hyprctl reports these in logical (surface-local) pixels, the same space `configure` uses — not the
 * buffer pixels a [KortexBar] reports, which are larger by the output scale.
 */
internal data class LayerGeometry(val x: Int, val y: Int, val logicalWidth: Int, val logicalHeight: Int) {
    /** The `x,y WxH` form grim's `-g` expects. */
    val grimArea: String get() = "$x,$y ${logicalWidth}x$logicalHeight"

    override fun toString(): String = grimArea
}

/** Reads back what a layer surface actually put on screen. */
internal object Screen {
    /** Where the layer named [namespace] is, or null if it is not mapped. */
    fun geometry(namespace: String): LayerGeometry? {
        val json = Hyprctl.run("layers", "-j")
        val at = json.indexOf("\"namespace\": \"$namespace\"")
        if (at < 0) return null
        val block = json.substring(maxOf(0, at - BLOCK_LOOKBEHIND), at)
        fun field(name: String): Int? =
            Regex("\"$name\": (-?\\d+)").findAll(block).lastOrNull()?.groupValues?.get(1)?.toInt()
        val x = field("x") ?: return null
        val y = field("y") ?: return null
        val w = field("w") ?: return null
        val h = field("h") ?: return null
        return LayerGeometry(x, y, w, h)
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
    private const val BLOCK_LOOKBEHIND = 400
}
