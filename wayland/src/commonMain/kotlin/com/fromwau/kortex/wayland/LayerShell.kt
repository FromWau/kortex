package com.fromwau.kortex.wayland

import androidx.compose.ui.unit.Dp
import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.flatMap
import com.fromwau.kern.result.getOrElse
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import kotlin.math.roundToInt

// The scene's density is set to the output scale, so 1.dp is exactly 1 logical pixel at every scale.
internal fun Dp.toLogicalPx(): Int = value.roundToInt()

internal fun Set<Edge>.toBits(): Int = fold(0) { bits, edge -> bits or edge.bit }

internal fun ExclusiveZone.toWireValue(): Int = when (this) {
    is ExclusiveZone.Reserve -> amount.toLogicalPx()
    ExclusiveZone.Yield -> 0
    ExclusiveZone.Overlap -> -1
}

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

    /** `zwlr_layer_shell_v1.destroy`, which the interface table declares from version 3. */
    const val DESTROY = 1
    const val DESTROY_SINCE = 3

    const val SET_SIZE = 0
    const val SET_ANCHOR = 1
    const val SET_EXCLUSIVE_ZONE = 2
    const val SET_MARGIN = 3
    const val SET_KEYBOARD_INTERACTIVITY = 4
    const val ACK_CONFIGURE = 6
    const val LAYER_SURFACE_DESTROY = 7
    const val SET_EXCLUSIVE_EDGE = 9
}

/**
 * A `zwlr_layer_shell_v1` surface: the protocol side of every [LayerSurface].
 *
 * The compositor answers the first commit with `configure`, and a buffer must not be attached before
 * that serial is acknowledged: doing so is a protocol error and a disconnect.
 */
internal class LayerShellSurface(
    private val display: WaylandDisplay,
    internal val surface: MemorySegment,
    private val layerSurface: MemorySegment,
    private val anchor: Set<Edge>,
    private val state: ConfigureState,
    private val surfaceListener: WlSurfaceListener,
    private val compositor: MemorySegment,
    private val shell: MemorySegment,
    // Holds the stubs of both listeners above, since one close() gives back the two proxies they hang off.
    private val arena: Arena,
) : AutoCloseable {

    // Paired with closed, which says the surface must be torn down rather than that it has been.
    private var disposed = false

    /** The logical (surface-local) size the compositor assigned, available once [waitForConfigure] returns `Ok`. */
    val logicalWidth: Int get() = state.width
    val logicalHeight: Int get() = state.height
    val closed: Boolean get() = state.closed

    /**
     * The buffer scale the compositor wants for this surface, from `wl_surface.preferred_buffer_scale`.
     *
     * It reflects the output this surface is actually on, so two surfaces on a mixed-DPI setup report
     * different scales. Reads 1 until the compositor says otherwise, as the protocol prescribes.
     */
    val preferredBufferScale: Int get() = surfaceListener.preferredBufferScale

    /**
     * Blocks until the compositor has configured this surface, acknowledging the serial it sent.
     *
     * @return `Ok` once configured; else the connection's error when it died before a configure came, else
     *   [KortexError.SurfaceNotConfigured].
     */
    fun waitForConfigure(): EmptyResult<KortexError> {
        display.roundtrip()
        var spins = 0
        while (!state.configured && !state.closed && spins < MAX_SPINS) {
            display.dispatch()
            spins++
        }
        if (state.configured) return Ok(Unit)
        // A dead connection surfaces first as an unconfigured surface; prefer the real cause.
        return display.requireAlive().flatMap { Err(KortexError.SurfaceNotConfigured) }
    }

    /** True once after a configure changed the size, and only once; a configure at the same size reports nothing. */
    internal fun consumeResize(): Boolean = state.consumeResize()

    /** Sets the flag a real `closed` event sets, as though the compositor had closed the surface. */
    internal fun markClosed() {
        state.closed = true
    }

    /** Attaches [buffer] and marks the whole surface damaged. Must follow an acknowledged configure. */
    fun attach(buffer: ShmBuffer) {
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
    fun setBufferScale(scale: Int) {
        LibWayland.marshal(surface, WL_SURFACE_SET_BUFFER_SCALE, args = listOf(WlArg.Num(scale)))
    }

    /**
     * Requests a new size in logical (surface-local) pixels; pending until [commit], which the
     * compositor answers with a fresh configure.
     *
     * @return [KortexError.NegativeSize] or [KortexError.UnspannableAxis] under the same rules [create] applies,
     *   since the anchor this surface was created with is fixed for its lifetime.
     */
    fun setSize(width: Int, height: Int): EmptyResult<KortexError> {
        requirePlaceableSize(width, height, anchor).getOrElse { return Err(it) }
        LibWayland.marshal(
            layerSurface, LayerShellProtocol.SET_SIZE,
            args = listOf(WlArg.Num(width), WlArg.Num(height)),
        )
        return Ok(Unit)
    }

    fun commit() {
        LibWayland.marshal(surface, WL_SURFACE_COMMIT)
        display.flush()
    }

    override fun close() {
        if (disposed) return
        disposed = true
        LibWayland.marshal(layerSurface, LayerShellProtocol.LAYER_SURFACE_DESTROY)
        LibWayland.proxyDestroy(layerSurface)
        LibWayland.marshal(surface, WL_SURFACE_DESTROY)
        LibWayland.proxyDestroy(surface)
        // After both destroys, never before: closing the arena frees the code their six stubs are.
        arena.close()
        // Bound per surface like everything else here, so they go with it rather than at disconnect.
        LibWayland.marshalIfSince(shell, LayerShellProtocol.DESTROY, LayerShellProtocol.DESTROY_SINCE)
        LibWayland.proxyDestroy(shell)
        releaseCompositor(compositor)
        display.flush()
    }

    companion object {
        /**
         * Creates a layer surface and drives it to its first configure.
         *
         * @param height logical (surface-local) pixels; 0 means "you choose" and requires [anchor] to
         *   pin both [Edge.Top] and [Edge.Bottom].
         * @param width logical (surface-local) pixels, like [height]; 0 (the default) requires [anchor]
         *   to pin both [Edge.Left] and [Edge.Right].
         * @param exclusiveZone how much screen space this surface reserves, measured inward from the
         *   anchored edge; a top or bottom bar reserves its [height], a side dock its [width].
         * @param margins measured from the anchor point; an edge [anchor] does not pin ignores its margin.
         * @param exclusiveEdge the anchored edge [exclusiveZone] reserves space against; only needed when
         *   [anchor] pins a corner, since the protocol cannot deduce one edge from two perpendicular ones.
         *   Sent only when non-null.
         * @return [KortexError.NegativeSize] when [width] or [height] is below 0; [KortexError.UnspannableAxis] when an
         *   axis is left 0 without both of its edges anchored, a request the compositor answers by dropping the
         *   connection; [KortexError.InvalidExclusiveEdge] when [anchor] does not pin [exclusiveEdge]; or
         *   [KortexError.InvalidExclusiveZone] when an [ExclusiveZone.Reserve] reserves nothing.
         */
        fun create(
            display: WaylandDisplay,
            namespace: String,
            height: Int,
            width: Int = SPAN_ANCHORED_AXIS,
            layer: Layer = Layer.Top,
            anchor: Set<Edge> = setOf(Edge.Top, Edge.Left, Edge.Right),
            exclusiveZone: ExclusiveZone,
            margins: Margins = Margins.None,
            keyboard: KeyboardInteractivity = KeyboardInteractivity.None,
            output: MemorySegment = MemorySegment.NULL,
            exclusiveEdge: Edge? = null,
        ): Result<LayerShellSurface, KortexError> {
            requirePlaceableSize(width, height, anchor).getOrElse { return Err(it) }
            if (exclusiveEdge != null && exclusiveEdge !in anchor) {
                return Err(KortexError.InvalidExclusiveEdge(exclusiveEdge, anchor))
            }
            // Tested on the rounded wire value, not the Dp: 0 is Yield's sentinel and -1 is Overlap's,
            // so a Reserve that reaches either silently becomes the case it was not asking for.
            if (exclusiveZone is ExclusiveZone.Reserve && exclusiveZone.toWireValue() < 1) {
                return Err(KortexError.InvalidExclusiveZone(exclusiveZone.amount))
            }

            val compositor = display.require("wl_compositor", LibWayland.compositorInterface, WlVersion.COMPOSITOR)
                .getOrElse { return Err(it) }
            val shell = display
                .require("zwlr_layer_shell_v1", LayerShellProtocol.layerShellInterface, WlVersion.LAYER_SHELL)
                .getOrElse {
                    releaseCompositor(compositor)
                    return Err(it)
                }

            val surface = LibWayland.marshal(
                compositor, WL_COMPOSITOR_CREATE_SURFACE, LibWayland.surfaceInterface,
                LibWayland.proxyGetVersion(compositor), listOf(WlArg.Ptr(MemorySegment.NULL)),
            )
            // Closed by the LayerShellSurface this all ends up in, which is the one owner of both proxies.
            val arena = Arena.ofShared()
            // Before get_layer_surface below: the compositor answers that with preferred_buffer_scale.
            val surfaceListener = WlSurfaceListener()
            surfaceListener.install(arena, surface)

            // wl_proxy_marshal copies a string argument into the message it builds, so the namespace is
            // only borrowed for the call and has no business in an arena that outlives it.
            val layerSurface = Arena.ofConfined().use { request ->
                LibWayland.marshal(
                    shell, LayerShellProtocol.GET_LAYER_SURFACE, LayerShellProtocol.layerSurfaceInterface,
                    LibWayland.proxyGetVersion(shell),
                    listOf(
                        WlArg.Ptr(MemorySegment.NULL),
                        WlArg.Ptr(surface),
                        WlArg.Ptr(output),
                        WlArg.Num(layer.wireValue),
                        WlArg.Ptr(request.allocateFrom(namespace)),
                    ),
                )
            }

            val state = ConfigureState(layerSurface)
            val listener = arena.allocate(ADDRESS.byteSize() * 2)
            listener.setAtIndex(ADDRESS, 0L, LibWayland.upcall(arena, state, "onConfigure", CONFIGURE_DESCRIPTOR))
            listener.setAtIndex(ADDRESS, 1L, LibWayland.upcall(arena, state, "onClosed", CLOSED_DESCRIPTOR))
            check(LibWayland.proxyAddListener(layerSurface, listener, MemorySegment.NULL) == 0) {
                "wl_proxy_add_listener rejected the layer surface listener"
            }

            LibWayland.marshal(layerSurface, LayerShellProtocol.SET_ANCHOR, args = listOf(WlArg.Num(anchor.toBits())))
            LibWayland.marshal(
                layerSurface, LayerShellProtocol.SET_SIZE,
                args = listOf(WlArg.Num(width), WlArg.Num(height)),
            )
            LibWayland.marshal(
                layerSurface, LayerShellProtocol.SET_EXCLUSIVE_ZONE,
                args = listOf(WlArg.Num(exclusiveZone.toWireValue())),
            )
            if (exclusiveEdge != null) {
                LibWayland.marshal(
                    layerSurface, LayerShellProtocol.SET_EXCLUSIVE_EDGE, args = listOf(WlArg.Num(exclusiveEdge.bit)),
                )
            }
            LibWayland.marshal(
                layerSurface, LayerShellProtocol.SET_MARGIN,
                args = listOf(
                    WlArg.Num(margins.top.toLogicalPx()),
                    WlArg.Num(margins.right.toLogicalPx()),
                    WlArg.Num(margins.bottom.toLogicalPx()),
                    WlArg.Num(margins.left.toLogicalPx()),
                ),
            )
            LibWayland.marshal(
                layerSurface, LayerShellProtocol.SET_KEYBOARD_INTERACTIVITY,
                args = listOf(WlArg.Num(keyboard.wireValue)),
            )

            val result = LayerShellSurface(
                display, surface, layerSurface, anchor, state, surfaceListener, compositor, shell, arena,
            )
            result.commit()
            return Ok(result)
        }

        /**
         * Checks that a surface anchored to [anchor] can ask for [width] by [height]. Neither may be below 0, which
         * `set_size`'s unsigned arguments would read as a size above four billion. An axis left 0 for the compositor
         * to size needs both of its edges anchored: the protocol allows omitting a dimension only then, and answers
         * anything else by dropping the connection.
         */
        private fun requirePlaceableSize(
            width: Int,
            height: Int,
            anchor: Set<Edge>,
        ): EmptyResult<KortexError> = when {
            width < 0 -> Err(KortexError.NegativeSize(Axis.Horizontal, width))

            height < 0 -> Err(KortexError.NegativeSize(Axis.Vertical, height))

            width == SPAN_ANCHORED_AXIS && !anchor.containsAll(Axis.Horizontal.edges) ->
                Err(KortexError.UnspannableAxis(Axis.Horizontal, anchor))

            height == SPAN_ANCHORED_AXIS && !anchor.containsAll(Axis.Vertical.edges) ->
                Err(KortexError.UnspannableAxis(Axis.Vertical, anchor))

            else -> Ok(Unit)
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
