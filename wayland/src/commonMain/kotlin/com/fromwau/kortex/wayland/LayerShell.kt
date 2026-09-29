package com.fromwau.kortex.wayland

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.getOrElse
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import kotlin.math.roundToInt

/** The `set_size` dimension that leaves an axis to the compositor, which needs both of that axis's edges anchored. */
internal const val SPAN_ANCHORED_AXIS = 0

// The scene's density is set to the output scale, so 1.dp is exactly 1 logical pixel at every scale.
internal fun Dp.toLogicalPx(): Int = value.roundToInt()

internal fun Set<Edge>.toBits(): Int = fold(0) { bits, edge -> bits or edge.bit }

internal fun ExclusiveZone.toWireValue(): Int = when (this) {
    is ExclusiveZone.Reserve -> amount.toLogicalPx()
    ExclusiveZone.Yield -> 0
    ExclusiveZone.Overlap -> -1
}

/** `set_margin` takes its four insets in CSS's order, which is neither `set_anchor`'s nor `hyprctl`'s. */
internal fun Margins.toWireArgs(): List<WlArg> = listOf(
    WlArg.Num(top.toLogicalPx()),
    WlArg.Num(right.toLogicalPx()),
    WlArg.Num(bottom.toLogicalPx()),
    WlArg.Num(left.toLogicalPx()),
)

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
            WlMessage("get_popup", "o", listOf(XdgShellProtocol.xdgPopupInterface)),
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

    const val SET_SIZE = 0
    const val SET_ANCHOR = 1
    const val SET_EXCLUSIVE_ZONE = 2
    const val SET_MARGIN = 3
    const val SET_KEYBOARD_INTERACTIVITY = 4
    const val GET_POPUP = 5
    const val ACK_CONFIGURE = 6
    const val LAYER_SURFACE_DESTROY = 7
    const val SET_LAYER = 8

    const val SET_EXCLUSIVE_EDGE = 9

    /** Below this the compositor deduces the edge from the anchor, which at a corner it cannot disambiguate. */
}

/**
 * A `zwlr_layer_shell_v1` surface: the protocol side of every [LayerSurface].
 *
 * The compositor answers the first commit with `configure`, and a buffer must not be attached before
 * that serial is acknowledged: doing so is a protocol error and a disconnect.
 */
internal class LayerShellSurface(
    private val display: WaylandDisplay,
    override val surface: MemorySegment,
    private val layerSurface: MemorySegment,
    // What the compositor has been told, which apply() sends the difference from.
    private var config: SurfaceConfig,
    private val state: ConfigureState,
    private val surfaceListener: WlSurfaceListener,
    private val compositor: MemorySegment,
    private val shell: MemorySegment,
    // Holds the stubs of both listeners above, since one close() gives back the two proxies they hang off.
    private val arena: Arena,
) : SurfaceRole {

    // Paired with closed, which says the surface must be torn down rather than that it has been.
    private var disposed = false

    override val wantsKeyboard: Boolean get() = config.keyboard != KeyboardInteractivity.None

    /** A layer surface takes a popup the client made without one, which is how a bar shows a menu. */
    override val popupParent: PopupParent get() = PopupParent.Layer(this)

    /** Adopts [popup], a popup the client made with no parent, so it shows over this surface. */
    fun adoptPopup(popup: MemorySegment) {
        LibWayland.marshal(layerSurface, LayerShellProtocol.GET_POPUP, args = listOf(WlArg.Ptr(popup)))
    }

    override val logicalWidth: Int get() = state.width
    override val logicalHeight: Int get() = state.height
    override val closed: Boolean get() = state.closed

    override val preferredBufferScale: Int get() = surfaceListener.preferredBufferScale

    /**
     * Dispatches until `zwlr_layer_surface_v1.configure` arrives, and gives up if the compositor closes the
     * surface first or answers neither within the wait budget.
     *
     * @return `Ok` once configured; else the connection's error when it died before a configure came, else
     *   [KortexError.SurfaceNotConfigured], which is also what a surface the compositor closed ends with.
     */
    override fun waitForConfigure(): EmptyResult<KortexError> =
        awaitConfigure(display, { state.configured }, { state.closed })

    override fun consumeResize(): Boolean = state.consumeResize()

    override fun markClosed() {
        state.closed = true
    }

    override fun attach(buffer: ShmBuffer) = attachWholeBuffer(surface, buffer)

    override fun setBufferScale(scale: Int) {
        LibWayland.marshal(surface, WL_SURFACE_SET_BUFFER_SCALE, args = listOf(WlArg.Num(scale)))
    }

    /**
     * Requests a new size in logical (surface-local) pixels, which the compositor answers with a fresh configure.
     *
     * @return what [requirePlaceable] rejects the new size for, leaving the surface at
     *   the size it already has.
     */
    fun setSize(width: Int, height: Int): EmptyResult<KortexError> =
        apply(config.copy(width = width.dp, height = height.dp))

    /**
     * Sends only what [new] changes from the config this surface holds, then commits once.
     *
     * @return what [requirePlaceable] rejects [new] for, with nothing sent and the
     *   surface left as it was.
     */
    fun apply(new: SurfaceConfig): EmptyResult<KortexError> {
        check(new.namespace == config.namespace) { "get_layer_surface fixes the namespace for the surface's life" }
        requirePlaceable(new).getOrElse { return Err(it) }
        val known = config
        fun changed(setting: SurfaceConfig.() -> Any?): Boolean = new.setting() != known.setting()
        // First: Hyprland validates an exclusive edge against the anchor pending when that request arrives.
        if (changed { anchor }) {
            LibWayland.marshal(
                layerSurface, LayerShellProtocol.SET_ANCHOR,
                args = listOf(WlArg.Num(new.anchor.toBits())),
            )
        }
        if (changed { exclusiveEdge }) {
            LibWayland.marshal(layerSurface, LayerShellProtocol.SET_EXCLUSIVE_EDGE,
                args = listOf(WlArg.Num(new.exclusiveEdge?.bit ?: NO_EXCLUSIVE_EDGE)),
            )
        }
        if (changed { width } || changed { height }) {
            LibWayland.marshal(
                layerSurface, LayerShellProtocol.SET_SIZE,
                args = listOf(WlArg.Num(new.width.toLogicalPx()), WlArg.Num(new.height.toLogicalPx())),
            )
        }
        if (changed { margins }) {
            LibWayland.marshal(layerSurface, LayerShellProtocol.SET_MARGIN, args = new.margins.toWireArgs())
        }
        if (changed { exclusiveZone }) {
            LibWayland.marshal(
                layerSurface, LayerShellProtocol.SET_EXCLUSIVE_ZONE,
                args = listOf(WlArg.Num(new.exclusiveZone.toWireValue())),
            )
        }
        if (changed { layer }) {
            LibWayland.marshal(layerSurface, LayerShellProtocol.SET_LAYER,
                args = listOf(WlArg.Num(new.layer.wireValue)),
            )
        }
        if (changed { keyboard }) {
            LibWayland.marshal(
                layerSurface, LayerShellProtocol.SET_KEYBOARD_INTERACTIVITY,
                args = listOf(WlArg.Num(new.keyboard.wireValue)),
            )
        }
        config = new
        commit()
        return Ok(Unit)
    }

    override fun commit() {
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
        releaseLayerShell(shell)
        releaseCompositor(compositor)
        display.flush()
    }

    companion object {
        /**
         * Creates a layer surface of [config] on [output], and drives it to its first configure.
         *
         * @return what [requirePlaceable] rejects [config] for, with nothing sent and no surface made.
         */
        fun create(
            display: WaylandDisplay,
            config: SurfaceConfig,
            output: MemorySegment = MemorySegment.NULL,
        ): Result<LayerShellSurface, KortexError> {
            requirePlaceable(config).getOrElse { return Err(it) }
            val namespace = config.namespace
            val anchor = config.anchor

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
                        WlArg.Num(config.layer.wireValue),
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
                args = listOf(WlArg.Num(config.width.toLogicalPx()), WlArg.Num(config.height.toLogicalPx())),
            )
            LibWayland.marshal(
                layerSurface, LayerShellProtocol.SET_EXCLUSIVE_ZONE,
                args = listOf(WlArg.Num(config.exclusiveZone.toWireValue())),
            )
            config.exclusiveEdge?.let { edge ->
                LibWayland.marshal(
                    layerSurface, LayerShellProtocol.SET_EXCLUSIVE_EDGE,
                    args = listOf(WlArg.Num(edge.bit)),
                )
            }
            LibWayland.marshal(layerSurface, LayerShellProtocol.SET_MARGIN, args = config.margins.toWireArgs())
            LibWayland.marshal(
                layerSurface, LayerShellProtocol.SET_KEYBOARD_INTERACTIVITY,
                args = listOf(WlArg.Num(config.keyboard.wireValue)),
            )

            val result = LayerShellSurface(
                display, surface, layerSurface, config, state, surfaceListener, compositor, shell, arena,
            )
            result.commit()
            return Ok(result)
        }

        /**
         * Every rule a config must satisfy before any of it is sent: size, spanned axes, exclusive edge, zone.
         *
         * @return [KortexError.NegativeSize] when a dimension is below 0, which `set_size`'s unsigned arguments would
         *   read as a size above four billion; [KortexError.UnspannableAxis] when an axis is left 0 without both of
         *   its edges anchored, which the protocol allows only then and Hyprland answers by dropping the connection;
         *   [KortexError.InvalidExclusiveEdge] when the anchor does not pin the exclusive edge; or
         *   [KortexError.InvalidExclusiveZone] when an [ExclusiveZone.Reserve] reserves nothing.
         */
        /** Gives back a `zwlr_layer_shell_v1` bound by [create], which binds one per surface. */
        fun releaseLayerShell(shell: MemorySegment) {
            LibWayland.marshal(shell, LayerShellProtocol.DESTROY)
            LibWayland.proxyDestroy(shell)
        }

        fun requirePlaceable(config: SurfaceConfig): EmptyResult<KortexError> {
            val width = config.width.toLogicalPx()
            val height = config.height.toLogicalPx()
            val anchor = config.anchor
            return when {
                width < 0 -> Err(KortexError.NegativeSize(Axis.Horizontal, width))

                height < 0 -> Err(KortexError.NegativeSize(Axis.Vertical, height))

                width == SPAN_ANCHORED_AXIS && !anchor.containsAll(Axis.Horizontal.edges) ->
                    Err(KortexError.UnspannableAxis(Axis.Horizontal, anchor))

                height == SPAN_ANCHORED_AXIS && !anchor.containsAll(Axis.Vertical.edges) ->
                    Err(KortexError.UnspannableAxis(Axis.Vertical, anchor))

                config.exclusiveEdge != null && config.exclusiveEdge !in anchor ->
                    Err(KortexError.InvalidExclusiveEdge(config.exclusiveEdge, anchor))

                // Tested on the rounded wire value, not the Dp: 0 is Yield's sentinel and -1 is Overlap's,
                // so a Reserve that reaches either silently becomes the case it was not asking for.
                config.exclusiveZone is ExclusiveZone.Reserve && config.exclusiveZone.toWireValue() < 1 ->
                    Err(KortexError.InvalidExclusiveZone(config.exclusiveZone.amount))

                else -> Ok(Unit)
            }
        }

        private const val NO_EXCLUSIVE_EDGE = 0

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

/**
 * Builds a surface on a layer surface of [config], on [output].
 *
 * @return what [LayerShellSurface.create] refused [config] for, or what the engine around it failed on, with
 *   nothing of either left behind.
 */
internal fun KortexSurface.Companion.createOnLayer(
    display: WaylandDisplay,
    config: SurfaceConfig,
    // NULL leaves output selection to the compositor; a bound wl_output targets one directly.
    output: MemorySegment = MemorySegment.NULL,
    loopQueue: LoopQueue? = null,
    onInputSerial: (Int) -> Unit = {},
    onPointerGrab: (Int) -> Unit = {},
    onKeyboardFocus: (keyboard: KeyboardInput, focused: Boolean) -> Unit = { _, _ -> },
    onStartDrag: (clip: Clip, origin: MemorySegment) -> EmptyResult<ClipboardError> = ::refuseDrag,
): Result<KortexSurface, KortexError> = KortexSurface.create(
    display = display,
    loopQueue = loopQueue,
    onInputSerial = onInputSerial,
    onPointerGrab = onPointerGrab,
    onKeyboardFocus = onKeyboardFocus,
    onStartDrag = onStartDrag,
) { LayerShellSurface.create(display, config, output) }

/**
 * Applies [new] to a live surface built on a layer surface, keyboard included: everything changed reaches the
 * compositor in one commit, and the composition on it keeps running and keeps its state.
 *
 * @return what [LayerShellSurface.apply] rejected, leaving the surface with the settings it already had.
 */
internal fun KortexSurface.applyConfig(new: SurfaceConfig): EmptyResult<KortexError> {
    role.asLayerShell().apply(new).getOrElse { return Err(it) }
    followKeyboard()
    return Ok(Unit)
}

/**
 * Requests a new size from the compositor, on the loop thread like every other request to the surface; exposed
 * so a test can make the compositor configure the surface again.
 *
 * @return what [LayerShellSurface.setSize] rejected, leaving the surface at the size it already had.
 */
internal fun KortexSurface.requestSize(
    width: Dp,
    height: Dp,
): EmptyResult<KortexError> = role.asLayerShell().setSize(width.toLogicalPx(), height.toLogicalPx())

/** Only the factory above places a surface [LayerSettings] reaches, and it builds every one on a layer surface. */
private fun SurfaceRole.asLayerShell(): LayerShellSurface {
    check(this is LayerShellSurface) { "a layer-shell request reached a surface built on another role" }
    return this
}
