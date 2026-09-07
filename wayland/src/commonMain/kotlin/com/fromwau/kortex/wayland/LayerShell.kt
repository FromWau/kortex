package com.fromwau.kortex.wayland

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.getOrElse
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT

/** Which layer a surface sits in. Other windows tile around anything below `Overlay`. */
public enum class Layer { Background, Bottom, Top, Overlay }

/** Edges a layer surface is anchored to. Anchoring both edges of an axis spans that axis. */
public object Anchor {
    public const val TOP: Int = 1
    public const val BOTTOM: Int = 2
    public const val LEFT: Int = 4
    public const val RIGHT: Int = 8
}

public enum class KeyboardInteractivity { None, Exclusive, OnDemand }

/** The `zwlr_layer_shell_v1` tables, from `wayland-scanner private-code wlr-layer-shell-unstable-v1.xml`. */
internal object LayerShellProtocol {
    val layerSurfaceInterface: MemorySegment = LibWayland.buildInterface(
        name = "zwlr_layer_surface_v1",
        version = WlVersion.LAYER_SHELL,
        requests = listOf(
            WlMessage("set_size", "uu", listOf(MemorySegment.NULL, MemorySegment.NULL)),
            WlMessage("set_anchor", "u", listOf(MemorySegment.NULL)),
            WlMessage("set_exclusive_zone", "i", listOf(MemorySegment.NULL)),
            WlMessage("set_margin", "iiii", List(4) { MemorySegment.NULL }),
            WlMessage("set_keyboard_interactivity", "u", listOf(MemorySegment.NULL)),
            // xdg_popup, which kortex never creates; libwayland reads a message's types only when that
            // message is marshalled, so NULL here is never dereferenced.
            WlMessage("get_popup", "o", listOf(MemorySegment.NULL)),
            WlMessage("ack_configure", "u", listOf(MemorySegment.NULL)),
            WlMessage("destroy", ""),
            WlMessage("set_layer", "2u", listOf(MemorySegment.NULL)),
            WlMessage("set_exclusive_edge", "5u", listOf(MemorySegment.NULL)),
        ),
        events = listOf(
            WlMessage("configure", "uuu", List(3) { MemorySegment.NULL }),
            WlMessage("closed", ""),
        ),
    )

    val layerShellInterface: MemorySegment = LibWayland.buildInterface(
        name = "zwlr_layer_shell_v1",
        version = WlVersion.LAYER_SHELL,
        requests = listOf(
            WlMessage(
                "get_layer_surface", "no?ous",
                listOf(
                    layerSurfaceInterface,
                    LibWayland.surfaceInterface,
                    LibWayland.outputInterface,
                    MemorySegment.NULL,
                    MemorySegment.NULL,
                ),
            ),
            WlMessage("destroy", "3"),
        ),
    )

    const val GET_LAYER_SURFACE = 0

    const val SET_SIZE = 0
    const val SET_ANCHOR = 1
    const val SET_EXCLUSIVE_ZONE = 2
    const val SET_KEYBOARD_INTERACTIVITY = 4
    const val ACK_CONFIGURE = 6
    const val LAYER_SURFACE_DESTROY = 7
}

/**
 * A `zwlr_layer_shell_v1` surface: a panel the compositor places and other windows tile around.
 *
 * The compositor answers the first commit with `configure`, and a buffer must not be attached before
 * that serial is acknowledged — doing so is a protocol error and a disconnect.
 */
public class LayerSurface internal constructor(
    private val display: WaylandDisplay,
    internal val surface: MemorySegment,
    private val layerSurface: MemorySegment,
    private val state: ConfigureState,
) : AutoCloseable {

    /** The logical (surface-local) size the compositor assigned, available once [waitForConfigure] returns true. */
    public val logicalWidth: Int get() = state.width
    public val logicalHeight: Int get() = state.height
    public val closed: Boolean get() = state.closed

    /** Blocks until the compositor has configured this surface, acknowledging the serial it sent. */
    public fun waitForConfigure(): Boolean {
        display.roundtrip()
        var spins = 0
        while (!state.configured && !state.closed && spins < MAX_SPINS) {
            display.dispatch()
            spins++
        }
        return state.configured
    }

    /** True once after a configure changed the size, and only once; a configure at the same size reports nothing. */
    internal fun consumeResize(): Boolean = state.consumeResize()

    /** Attaches [buffer] and marks the whole surface damaged. Must follow an acknowledged configure. */
    public fun attach(buffer: ShmBuffer) {
        LibWayland.marshal(
            surface, WL_SURFACE_ATTACH,
            args = listOf(WlArg.Ptr(buffer.buffer), WlArg.Num(0), WlArg.Num(0)),
        )
        LibWayland.marshal(
            surface, WL_SURFACE_DAMAGE_BUFFER,
            args = listOf(WlArg.Num(0), WlArg.Num(0), WlArg.Num(buffer.width), WlArg.Num(buffer.height)),
        )
    }

    /** Double-buffered like every pending surface state: takes effect only at the next [commit]. */
    public fun setBufferScale(scale: Int) {
        LibWayland.marshal(surface, WL_SURFACE_SET_BUFFER_SCALE, args = listOf(WlArg.Num(scale)))
    }

    /**
     * Requests a new size in logical (surface-local) pixels; pending until [commit], which the
     * compositor answers with a fresh configure.
     */
    public fun setSize(width: Int, height: Int) {
        LibWayland.marshal(
            layerSurface, LayerShellProtocol.SET_SIZE,
            args = listOf(WlArg.Num(width), WlArg.Num(height)),
        )
    }

    public fun commit() {
        LibWayland.marshal(surface, WL_SURFACE_COMMIT)
        display.flush()
    }

    override fun close() {
        LibWayland.marshal(layerSurface, LayerShellProtocol.LAYER_SURFACE_DESTROY)
        LibWayland.proxyDestroy(layerSurface)
        LibWayland.marshal(surface, WL_SURFACE_DESTROY)
        LibWayland.proxyDestroy(surface)
        display.flush()
    }

    public companion object {
        /**
         * Creates a layer surface and drives it to its first configure.
         *
         * @param height logical (surface-local) pixels; the surface spans whichever axis [anchor] pins
         *   both edges of.
         */
        public fun create(
            display: WaylandDisplay,
            namespace: String,
            height: Int,
            layer: Layer = Layer.Top,
            anchor: Int = Anchor.TOP or Anchor.LEFT or Anchor.RIGHT,
            exclusiveZone: Int = height,
            keyboard: KeyboardInteractivity = KeyboardInteractivity.None,
            output: MemorySegment = MemorySegment.NULL,
        ): Result<LayerSurface, KortexError> {
            val compositor = display.require("wl_compositor", LibWayland.compositorInterface, WlVersion.COMPOSITOR)
                .getOrElse { return Err(it) }
            val shell = display
                .require("zwlr_layer_shell_v1", LayerShellProtocol.layerShellInterface, WlVersion.LAYER_SHELL)
                .getOrElse { return Err(it) }

            val surface = LibWayland.marshal(
                compositor, WL_COMPOSITOR_CREATE_SURFACE, LibWayland.surfaceInterface,
                LibWayland.proxyGetVersion(compositor), listOf(WlArg.Ptr(MemorySegment.NULL)),
            )

            val layerSurface = LibWayland.marshal(
                shell, LayerShellProtocol.GET_LAYER_SURFACE, LayerShellProtocol.layerSurfaceInterface,
                LibWayland.proxyGetVersion(shell),
                listOf(
                    WlArg.Ptr(MemorySegment.NULL),
                    WlArg.Ptr(surface),
                    WlArg.Ptr(output),
                    WlArg.Num(layer.ordinal),
                    WlArg.Ptr(LibWayland.cString(namespace)),
                ),
            )

            val state = ConfigureState(layerSurface)
            val listener = LibWayland.arena.allocate(ADDRESS.byteSize() * 2)
            listener.setAtIndex(ADDRESS, 0L, LibWayland.upcall(state, "onConfigure", CONFIGURE_DESCRIPTOR))
            listener.setAtIndex(ADDRESS, 1L, LibWayland.upcall(state, "onClosed", CLOSED_DESCRIPTOR))
            check(LibWayland.proxyAddListener(layerSurface, listener, MemorySegment.NULL) == 0) {
                "wl_proxy_add_listener rejected the layer surface listener"
            }

            LibWayland.marshal(layerSurface, LayerShellProtocol.SET_ANCHOR, args = listOf(WlArg.Num(anchor)))
            LibWayland.marshal(
                layerSurface, LayerShellProtocol.SET_SIZE,
                args = listOf(WlArg.Num(SPAN_ANCHORED_AXIS), WlArg.Num(height)),
            )
            LibWayland.marshal(
                layerSurface, LayerShellProtocol.SET_EXCLUSIVE_ZONE, args = listOf(WlArg.Num(exclusiveZone)),
            )
            LibWayland.marshal(
                layerSurface, LayerShellProtocol.SET_KEYBOARD_INTERACTIVITY,
                args = listOf(WlArg.Num(keyboard.ordinal)),
            )

            val result = LayerSurface(display, surface, layerSurface, state)
            result.commit()
            return Ok(result)
        }

        private const val WL_COMPOSITOR_CREATE_SURFACE = 0
        private const val WL_SURFACE_DESTROY = 0
        private const val WL_SURFACE_ATTACH = 1
        private const val WL_SURFACE_COMMIT = 6
        private const val WL_SURFACE_SET_BUFFER_SCALE = 8
        private const val WL_SURFACE_DAMAGE_BUFFER = 9
        private const val MAX_SPINS = 32
        private const val SPAN_ANCHORED_AXIS = 0

        private val CONFIGURE_DESCRIPTOR =
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT)
        private val CLOSED_DESCRIPTOR = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS)
    }
}

internal class ConfigureState(private val layerSurface: MemorySegment) {
    @Volatile var width: Int = 0
    @Volatile var height: Int = 0
    @Volatile var configured: Boolean = false
    @Volatile var closed: Boolean = false
    @Volatile private var resized: Boolean = false

    fun onConfigure(data: MemorySegment, proxy: MemorySegment, serial: Int, width: Int, height: Int) {
        if (configured && (width != this.width || height != this.height)) resized = true
        this.width = width
        this.height = height
        // Must precede any attach; the compositor treats a buffer on an unacknowledged configure as a
        // protocol error and drops the connection.
        LibWayland.marshal(layerSurface, LayerShellProtocol.ACK_CONFIGURE, args = listOf(WlArg.Num(serial)))
        configured = true
    }

    fun onClosed(data: MemorySegment, proxy: MemorySegment) {
        closed = true
    }

    fun consumeResize(): Boolean {
        if (!resized) return false
        resized = false
        return true
    }
}
