package com.fromwau.kortex.wayland

import com.fromwau.kern.result.Result
import com.fromwau.kern.result.map
import com.fromwau.kortex.compose.KortexCursor
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.StructLayout
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT

/**
 * libwayland-cursor: resolves an XCursor theme name to the `wl_buffer` and hotspot `wl_pointer.set_cursor` needs.
 */
internal object LibWaylandCursor {
    private val linker = Linker.nativeLinker()
    private val lookup = SymbolLookup.libraryLookup("libwayland-cursor.so.0", LibWayland.arena)

    private fun downcall(name: String, descriptor: FunctionDescriptor) =
        linker.downcallHandle(
            lookup.find(name).orElseThrow { UnsatisfiedLinkError("libwayland-cursor exports no $name") },
            descriptor,
        )

    private val themeLoad =
        downcall("wl_cursor_theme_load", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, ADDRESS))
    private val themeGetCursor =
        downcall("wl_cursor_theme_get_cursor", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS))
    private val imageGetBuffer =
        downcall("wl_cursor_image_get_buffer", FunctionDescriptor.of(ADDRESS, ADDRESS))

    fun themeLoad(name: MemorySegment, size: Int, shm: MemorySegment): MemorySegment =
        themeLoad.invoke(name, size, shm) as MemorySegment

    fun themeGetCursor(theme: MemorySegment, name: MemorySegment): MemorySegment =
        themeGetCursor.invoke(theme, name) as MemorySegment

    /** The cursor's first (and, for every shape kortex uses, only) animation frame. */
    fun firstImage(cursor: MemorySegment, scale: Int): CursorImage? {
        val struct = cursor.reinterpret(CURSOR.byteSize())
        if (struct.get(JAVA_INT, IMAGE_COUNT_OFFSET) <= 0) return null
        // A pointer field read out of a struct comes back zero-length too, same as a SymbolLookup segment.
        val images = struct.get(ADDRESS, IMAGES_OFFSET).reinterpret(ADDRESS.byteSize())
        val image = images.get(ADDRESS, 0L).reinterpret(CURSOR_IMAGE.byteSize())
        val buffer = imageGetBuffer.invoke(image) as MemorySegment
        return CursorImage(
            buffer = buffer,
            width = image.get(JAVA_INT, WIDTH_OFFSET),
            height = image.get(JAVA_INT, HEIGHT_OFFSET),
            // wl_pointer.set_cursor's hotspot is surface-local, but the image itself is buffer (physical) pixels.
            hotspotX = image.get(JAVA_INT, HOTSPOT_X_OFFSET) / scale,
            hotspotY = image.get(JAVA_INT, HOTSPOT_Y_OFFSET) / scale,
        )
    }

    // struct wl_cursor { unsigned int image_count; <4 bytes padding> wl_cursor_image **images; char *name; }
    private val CURSOR: StructLayout = MemoryLayout.structLayout(
        JAVA_INT.withName("image_count"),
        MemoryLayout.paddingLayout(4),
        ADDRESS.withName("images"),
        ADDRESS.withName("name"),
    )

    // struct wl_cursor_image { uint32_t width, height, hotspot_x, hotspot_y, delay; }
    private val CURSOR_IMAGE: StructLayout = MemoryLayout.structLayout(
        JAVA_INT.withName("width"),
        JAVA_INT.withName("height"),
        JAVA_INT.withName("hotspot_x"),
        JAVA_INT.withName("hotspot_y"),
        JAVA_INT.withName("delay"),
    )

    private const val IMAGE_COUNT_OFFSET = 0L
    private const val IMAGES_OFFSET = 8L
    private const val WIDTH_OFFSET = 0L
    private const val HEIGHT_OFFSET = 4L
    private const val HOTSPOT_X_OFFSET = 8L
    private const val HOTSPOT_Y_OFFSET = 12L
}

/**
 * One resolved cursor image: the `wl_buffer` to attach plus the geometry a caller needs.
 *
 * [width]/[height] are buffer (physical) pixels, matching `wl_surface.damage_buffer`; [hotspotX]/[hotspotY]
 * are already converted to surface-local units, matching what `wl_pointer.set_cursor` expects.
 */
internal data class CursorImage(
    val buffer: MemorySegment,
    val width: Int,
    val height: Int,
    val hotspotX: Int,
    val hotspotY: Int,
)

/**
 * Loads an XCursor theme and resolves [KortexCursor] shapes against it, caching every lookup.
 *
 * `wl_cursor_theme_load` hits the disk, so it runs on construction and [rescale] only.
 */
internal class WlCursorTheme private constructor(
    private val shm: MemorySegment,
    private val name: MemorySegment,
    private val baseSize: Int,
) {
    private var theme: MemorySegment? = null
    private var scale = 1
    private val cache = HashMap<KortexCursor, CursorImage?>()

    fun imageFor(cursor: KortexCursor): CursorImage? {
        if (cache.containsKey(cursor)) return cache[cursor]
        val resolved = resolve(cursor)
        cache[cursor] = resolved
        return resolved
    }

    /** Reloads the theme at `baseSize * scale` physical pixels so cursors match the output's buffer scale. */
    fun rescale(scale: Int) {
        if (scale == this.scale && theme != null) return
        val loaded = LibWaylandCursor.themeLoad(name, baseSize * scale, shm)
        // Every wl_buffer handed out so far belongs to the old theme handle, and the compositor may still be
        // scanning one out (no release tracking exists for these, unlike ShmBuffer.busy); leak the old theme
        // handle rather than risk a use-after-free by destroying it.
        theme = if (loaded.equals(MemorySegment.NULL)) null else loaded
        this.scale = scale
        cache.clear()
    }

    private fun resolve(cursor: KortexCursor): CursorImage? {
        val theme = theme ?: return null
        // Conventional XCursor names; every list falls back to "left_ptr", present in essentially every theme.
        val candidates = when (cursor) {
            KortexCursor.Default -> listOf("left_ptr")
            KortexCursor.Crosshair -> listOf("crosshair", "left_ptr")
            KortexCursor.Text -> listOf("xterm", "text", "left_ptr")
            KortexCursor.Hand -> listOf("hand2", "pointer", "left_ptr")
        }
        for (name in candidates) {
            val native = LibWaylandCursor.themeGetCursor(theme, LibWayland.cString(name))
            if (native.equals(MemorySegment.NULL)) continue
            return LibWaylandCursor.firstImage(native, scale) ?: continue
        }
        return null
    }

    companion object {
        /** Honours `XCURSOR_THEME`/`XCURSOR_SIZE`, falling back to the compositor's default theme at size 24. */
        fun load(display: WaylandDisplay, scale: Int): Result<WlCursorTheme, KortexError> =
            display.require("wl_shm", LibWayland.shmInterface, WlVersion.SHM).map { shm ->
                val name = System.getenv("XCURSOR_THEME")?.let { LibWayland.cString(it) } ?: MemorySegment.NULL
                val baseSize = System.getenv("XCURSOR_SIZE")?.toIntOrNull() ?: DEFAULT_SIZE
                WlCursorTheme(shm, name, baseSize).also { it.rescale(scale) }
            }

        private const val DEFAULT_SIZE = 24
    }
}

/** The dedicated `wl_surface` a cursor image is attached to; created once and reused for every shape. */
internal class WlCursorSurface private constructor(private val surface: MemorySegment) {
    val proxy: MemorySegment get() = surface

    /** Double-buffered like every pending surface state: only takes effect on the next [commit]. */
    fun setBufferScale(scale: Int) {
        LibWayland.marshal(surface, WL_SURFACE_SET_BUFFER_SCALE, args = listOf(WlArg.Num(scale)))
    }

    fun commit() {
        LibWayland.marshal(surface, WL_SURFACE_COMMIT)
    }

    fun show(image: CursorImage) {
        LibWayland.marshal(
            surface, WL_SURFACE_ATTACH, args = listOf(WlArg.Ptr(image.buffer), WlArg.Num(0), WlArg.Num(0)),
        )
        // damage_buffer takes buffer (physical) coordinates, matching CursorImage.width/height directly.
        LibWayland.marshal(
            surface, WL_SURFACE_DAMAGE_BUFFER,
            args = listOf(WlArg.Num(0), WlArg.Num(0), WlArg.Num(image.width), WlArg.Num(image.height)),
        )
        commit()
    }

    companion object {
        fun create(display: WaylandDisplay): Result<WlCursorSurface, KortexError> =
            display.require("wl_compositor", LibWayland.compositorInterface, WlVersion.COMPOSITOR).map { compositor ->
                val surface = LibWayland.marshal(
                    compositor, WL_COMPOSITOR_CREATE_SURFACE, LibWayland.surfaceInterface,
                    LibWayland.proxyGetVersion(compositor), listOf(WlArg.Ptr(MemorySegment.NULL)),
                )
                WlCursorSurface(surface)
            }

        private const val WL_COMPOSITOR_CREATE_SURFACE = 0
        private const val WL_SURFACE_ATTACH = 1
        private const val WL_SURFACE_COMMIT = 6
        private const val WL_SURFACE_SET_BUFFER_SCALE = 8
        private const val WL_SURFACE_DAMAGE_BUFFER = 9
    }
}
