package com.fromwau.kortex.wayland

import androidx.compose.ui.unit.Dp
import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.getOrElse
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
 * A `zwlr_layer_shell_v1` surface: a panel the compositor places and other windows tile around.
 *
 * The compositor answers the first commit with `configure`, and a buffer must not be attached before
 * that serial is acknowledged — doing so is a protocol error and a disconnect.
 */
public class LayerSurface internal constructor(
    private val display: WaylandDisplay,
    internal val surface: MemorySegment,
    private val layerSurface: MemorySegment,
    private val anchor: Set<Edge>,
    private val state: ConfigureState,
    private val surfaceListener: WlSurfaceListener,
    private val compositor: MemorySegment,
    private val shell: MemorySegment,
) : AutoCloseable {

    // Paired with the public closed, which is the compositor's word rather than this teardown latch.
    private var disposed = false

    /** The logical (surface-local) size the compositor assigned, available once [waitForConfigure] returns true. */
    public val logicalWidth: Int get() = state.width
    public val logicalHeight: Int get() = state.height
    public val closed: Boolean get() = state.closeReason != null
    internal val closeReason: CloseReason? get() = state.closeReason

    /**
     * The buffer scale the compositor wants for this surface, from `wl_surface.preferred_buffer_scale`.
     *
     * It reflects the output this surface is actually on, so two surfaces on a mixed-DPI setup report
     * different scales. Reads 1 until the compositor says otherwise, as the protocol prescribes.
     */
    public val preferredBufferScale: Int get() = surfaceListener.preferredBufferScale

    /** Blocks until the compositor has configured this surface, acknowledging the serial it sent. */
    public fun waitForConfigure(): Boolean {
        display.roundtrip()
        var spins = 0
        while (!state.configured && state.closeReason == null && spins < MAX_SPINS) {
            display.dispatch()
            spins++
        }
        return state.configured
    }

    /** True once after a configure changed the size, and only once; a configure at the same size reports nothing. */
    internal fun consumeResize(): Boolean = state.consumeResize()

    /** Sets the same reason a real `closed` event would, so a self-close reaps through that one path. */
    internal fun markClosed() {
        state.closeReason = CloseReason.Content
    }

    // A test seam: in production only the compositor's own closed event sets this reason.
    internal fun simulateCompositorClose() {
        state.closeReason = CloseReason.Compositor
    }

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
     *
     * @return [KortexError.UnspannableAxis] under the same rule [create] applies, since the anchor this
     *   surface was created with is fixed for its lifetime.
     */
    public fun setSize(width: Int, height: Int): EmptyResult<KortexError> {
        unspannableAxis(width, height, anchor)?.let { return Err(it) }
        LibWayland.marshal(
            layerSurface, LayerShellProtocol.SET_SIZE,
            args = listOf(WlArg.Num(width), WlArg.Num(height)),
        )
        return Ok(Unit)
    }

    public fun commit() {
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
        // Bound per surface like everything else here, so they go with it rather than at disconnect.
        LibWayland.marshalIfSince(shell, LayerShellProtocol.DESTROY, LayerShellProtocol.DESTROY_SINCE)
        LibWayland.proxyDestroy(shell)
        releaseCompositor(compositor)
        display.flush()
    }

    public companion object {
        /**
         * Creates a layer surface and drives it to its first configure.
         *
         * @param height logical (surface-local) pixels; 0 means "you choose" and requires [anchor] to
         *   pin both [Edge.Top] and [Edge.Bottom].
         * @param width logical (surface-local) pixels, like [height]; 0 (the default) requires [anchor]
         *   to pin both [Edge.Left] and [Edge.Right].
         * @param exclusiveZone how much screen space this surface reserves, measured inward from the
         *   anchored edge; a top or bottom bar reserves its [height], a side dock its [width], which is
         *   why it has no default.
         * @param margins measured from the anchor point; an edge [anchor] does not pin ignores its margin.
         * @param exclusiveEdge the anchored edge [exclusiveZone] reserves space against; only needed when
         *   [anchor] pins a corner, since the protocol cannot deduce one edge from two perpendicular ones.
         *   Sent only when non-null.
         * @return [KortexError.UnspannableAxis] when an axis is left 0 without both of its edges anchored
         *   — a request the compositor answers by dropping the connection —
         *   [KortexError.InvalidExclusiveEdge] when [anchor] does not pin [exclusiveEdge], or
         *   [KortexError.InvalidExclusiveZone] when an [ExclusiveZone.Reserve] reserves nothing.
         */
        public fun create(
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
        ): Result<LayerSurface, KortexError> {
            unspannableAxis(width, height, anchor)?.let { return Err(it) }
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
            // Before get_layer_surface below: the compositor answers that with preferred_buffer_scale.
            val surfaceListener = WlSurfaceListener()
            surfaceListener.install(surface)

            val layerSurface = LibWayland.marshal(
                shell, LayerShellProtocol.GET_LAYER_SURFACE, LayerShellProtocol.layerSurfaceInterface,
                LibWayland.proxyGetVersion(shell),
                listOf(
                    WlArg.Ptr(MemorySegment.NULL),
                    WlArg.Ptr(surface),
                    WlArg.Ptr(output),
                    WlArg.Num(layer.wireValue),
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

            val result =
                LayerSurface(display, surface, layerSurface, anchor, state, surfaceListener, compositor, shell)
            result.commit()
            return Ok(result)
        }

        /**
         * Which axis, if either, was left for the compositor to size without both of its edges anchored.
         *
         * Omitting a dimension asks the compositor to pick it, which the protocol allows only when both
         * of that axis's edges are anchored; anything else it answers by dropping the connection.
         */
        private fun unspannableAxis(
            width: Int,
            height: Int,
            anchor: Set<Edge>,
        ): KortexError.UnspannableAxis? = when {
            width == SPAN_ANCHORED_AXIS && !anchor.containsAll(Axis.Horizontal.edges) ->
                KortexError.UnspannableAxis(Axis.Horizontal, anchor)

            height == SPAN_ANCHORED_AXIS && !anchor.containsAll(Axis.Vertical.edges) ->
                KortexError.UnspannableAxis(Axis.Vertical, anchor)

            else -> null
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

/** Why a layer surface stopped being usable: the compositor took it away, or its own content did. */
internal enum class CloseReason { Compositor, Content }

internal class ConfigureState(private val layerSurface: MemorySegment) {
    @Volatile var width: Int = 0
    @Volatile var height: Int = 0
    @Volatile var configured: Boolean = false
    @Volatile var closeReason: CloseReason? = null
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
        closeReason = CloseReason.Compositor
    }

    fun consumeResize(): Boolean {
        if (!resized) return false
        resized = false
        return true
    }
}

/**
 * Tracks the `wl_surface` events for one surface.
 *
 * Only `preferred_buffer_scale` carries state. The other three exist because libwayland dispatches by
 * indexing the listener struct with the event's opcode and calls straight through an empty slot.
 */
internal class WlSurfaceListener {
    @Volatile var preferredBufferScale: Int = DEFAULT_SCALE
        private set

    fun onEnter(data: MemorySegment, proxy: MemorySegment, output: MemorySegment) = Unit

    fun onLeave(data: MemorySegment, proxy: MemorySegment, output: MemorySegment) = Unit

    fun onPreferredBufferScale(data: MemorySegment, proxy: MemorySegment, factor: Int) {
        preferredBufferScale = factor
    }

    fun onPreferredBufferTransform(data: MemorySegment, proxy: MemorySegment, transform: Int) = Unit

    fun install(surface: MemorySegment) {
        val listener = LibWayland.arena.allocate(ADDRESS.byteSize() * EVENT_COUNT)
        listener.setAtIndex(ADDRESS, ENTER, LibWayland.upcall(this, "onEnter", ENTER_DESCRIPTOR))
        listener.setAtIndex(ADDRESS, LEAVE, LibWayland.upcall(this, "onLeave", LEAVE_DESCRIPTOR))
        listener.setAtIndex(
            ADDRESS, PREFERRED_BUFFER_SCALE,
            LibWayland.upcall(this, "onPreferredBufferScale", PREFERRED_BUFFER_SCALE_DESCRIPTOR),
        )
        listener.setAtIndex(
            ADDRESS, PREFERRED_BUFFER_TRANSFORM,
            LibWayland.upcall(this, "onPreferredBufferTransform", PREFERRED_BUFFER_TRANSFORM_DESCRIPTOR),
        )
        check(LibWayland.proxyAddListener(surface, listener, MemorySegment.NULL) == 0) {
            "wl_proxy_add_listener rejected the surface listener"
        }
    }

    companion object {
        // wl_surface v6 declares exactly these four events; every slot must be filled, because
        // libwayland indexes the struct and calls straight through it.
        private const val EVENT_COUNT = 4L
        private const val ENTER = 0L
        private const val LEAVE = 1L
        private const val PREFERRED_BUFFER_SCALE = 2L
        private const val PREFERRED_BUFFER_TRANSFORM = 3L

        /** The protocol's own starting value: a surface renders at scale 1 until the compositor says otherwise. */
        private const val DEFAULT_SCALE = 1

        private val ENTER_DESCRIPTOR = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS)
        private val LEAVE_DESCRIPTOR = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS)
        private val PREFERRED_BUFFER_SCALE_DESCRIPTOR = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT)
        private val PREFERRED_BUFFER_TRANSFORM_DESCRIPTOR = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT)
    }
}
