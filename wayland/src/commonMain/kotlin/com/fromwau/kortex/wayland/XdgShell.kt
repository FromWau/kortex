package com.fromwau.kortex.wayland

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
import java.lang.foreign.ValueLayout.JAVA_LONG

/** The `xdg_toplevel.state` values a `wl_array` of `uint32_t` holds; a value kortex does not know is dropped. */
private fun statesIn(array: MemorySegment): Set<XdgToplevelState> {
    val header = array.reinterpret(WL_ARRAY_BYTES)
    val bytes = header.get(JAVA_LONG, WL_ARRAY_SIZE_OFFSET)
    val data = header.get(ADDRESS, WL_ARRAY_DATA_OFFSET).reinterpret(bytes)
    return (0 until bytes / Int.SIZE_BYTES).mapNotNullTo(mutableSetOf()) { index ->
        XdgToplevelState.fromOrNull(data.getAtIndex(JAVA_INT, index))
    }
}

// struct wl_array { size_t size; size_t alloc; void *data; }, which an upcall hands over with no extent.
private const val WL_ARRAY_SIZE_OFFSET = 0L
private const val WL_ARRAY_DATA_OFFSET = 16L
private const val WL_ARRAY_BYTES = 24L

/** The `xdg_shell` tables, from `wayland-scanner private-code xdg-shell.xml`. */
internal object XdgShellProtocol {
    val xdgToplevelInterface: MemorySegment = LibWayland.buildInterface(
        name = "xdg_toplevel",
        version = WlVersion.XDG_SHELL,
        requests = listOf(
            WlMessage("destroy", ""),
            // Every object argument from here on belongs to a request nothing here sends; libwayland reads a
            // message's types only when that message is marshalled, so NULL is never dereferenced.
            WlMessage("set_parent", "?o", listOf(MemorySegment.NULL)),
            WlMessage("set_title", "s", listOf(MemorySegment.NULL)),
            WlMessage("set_app_id", "s", listOf(MemorySegment.NULL)),
            WlMessage("show_window_menu", "ouii", List(4) { MemorySegment.NULL }),
            WlMessage("move", "ou", List(2) { MemorySegment.NULL }),
            WlMessage("resize", "ouu", List(3) { MemorySegment.NULL }),
            WlMessage("set_max_size", "ii", List(2) { MemorySegment.NULL }),
            WlMessage("set_min_size", "ii", List(2) { MemorySegment.NULL }),
            WlMessage("set_maximized", ""),
            WlMessage("unset_maximized", ""),
            WlMessage("set_fullscreen", "?o", listOf(MemorySegment.NULL)),
            WlMessage("unset_fullscreen", ""),
            WlMessage("set_minimized", ""),
        ),
        events = listOf(
            WlMessage("configure", "iia", List(3) { MemorySegment.NULL }),
            WlMessage("close", ""),
            WlMessage("configure_bounds", "4ii", List(2) { MemorySegment.NULL }),
            WlMessage("wm_capabilities", "5a", listOf(MemorySegment.NULL)),
        ),
    )

    val xdgSurfaceInterface: MemorySegment = LibWayland.buildInterface(
        name = "xdg_surface",
        version = WlVersion.XDG_SHELL,
        requests = listOf(
            WlMessage("destroy", ""),
            WlMessage("get_toplevel", "n", listOf(xdgToplevelInterface)),
            // xdg_popup and xdg_positioner, which nothing here creates.
            WlMessage("get_popup", "n?oo", List(3) { MemorySegment.NULL }),
            WlMessage("set_window_geometry", "iiii", List(4) { MemorySegment.NULL }),
            WlMessage("ack_configure", "u", listOf(MemorySegment.NULL)),
        ),
        events = listOf(
            WlMessage("configure", "u", listOf(MemorySegment.NULL)),
        ),
    )

    val xdgWmBaseInterface: MemorySegment = LibWayland.buildInterface(
        name = "xdg_wm_base",
        version = WlVersion.XDG_SHELL,
        requests = listOf(
            WlMessage("destroy", ""),
            // xdg_positioner, which nothing here creates.
            WlMessage("create_positioner", "n", listOf(MemorySegment.NULL)),
            WlMessage("get_xdg_surface", "no", listOf(xdgSurfaceInterface, LibWayland.surfaceInterface)),
            WlMessage("pong", "u", listOf(MemorySegment.NULL)),
        ),
        events = listOf(
            WlMessage("ping", "u", listOf(MemorySegment.NULL)),
        ),
    )

    const val WM_BASE_DESTROY = 0
    const val GET_XDG_SURFACE = 2
    const val PONG = 3

    const val XDG_SURFACE_DESTROY = 0
    const val GET_TOPLEVEL = 1
    const val ACK_CONFIGURE = 4

    const val TOPLEVEL_DESTROY = 0
    const val SET_TITLE = 2
    const val SET_APP_ID = 3
}

/**
 * Answers `xdg_wm_base.ping`, which the compositor sends to check the client is still responsive.
 *
 * It is an event on the shell global rather than on any surface, and leaving it unanswered has the
 * compositor treat every window of this client as hung.
 */
internal class XdgWmBaseListener(
    private val display: WaylandDisplay,
    private val wmBase: MemorySegment,
) {
    fun onPing(data: MemorySegment, proxy: MemorySegment, serial: Int) {
        LibWayland.marshal(wmBase, XdgShellProtocol.PONG, args = listOf(WlArg.Num(serial)))
        // Flushed here rather than with the next commit: an idle window never commits again, and a pong
        // left in the queue reads as no answer at all.
        display.flush()
    }

    /** [arena] is the owning surface's, which closes it once the `xdg_wm_base` proxy is destroyed. */
    fun install(arena: Arena) {
        val listener = arena.allocate(ADDRESS.byteSize() * EVENT_COUNT)
        listener.setAtIndex(ADDRESS, PING, LibWayland.upcall(arena, this, "onPing", PING_DESCRIPTOR))
        check(LibWayland.proxyAddListener(wmBase, listener, MemorySegment.NULL) == 0) {
            "wl_proxy_add_listener rejected the xdg_wm_base listener"
        }
    }

    private companion object {
        const val EVENT_COUNT = 1L
        const val PING = 0L

        val PING_DESCRIPTOR = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT)
    }
}

/**
 * Acknowledges `xdg_surface.configure`, the one event every xdg role shares.
 *
 * The serial is what lets a buffer be attached at all, so the surface counts as configured only once the
 * acknowledgement has gone out.
 */
internal class XdgSurfaceListener(private val xdgSurface: MemorySegment) {
    /** True once a configure has arrived and been acknowledged, from which point a buffer may be attached. */
    @Volatile var configured: Boolean = false
        private set

    fun onConfigure(data: MemorySegment, proxy: MemorySegment, serial: Int) {
        LibWayland.marshal(xdgSurface, XdgShellProtocol.ACK_CONFIGURE, args = listOf(WlArg.Num(serial)))
        configured = true
    }

    /** [arena] is the owning surface's, which closes it once the `xdg_surface` proxy is destroyed. */
    fun install(arena: Arena) {
        val listener = arena.allocate(ADDRESS.byteSize() * EVENT_COUNT)
        listener.setAtIndex(ADDRESS, CONFIGURE, LibWayland.upcall(arena, this, "onConfigure", CONFIGURE_DESCRIPTOR))
        check(LibWayland.proxyAddListener(xdgSurface, listener, MemorySegment.NULL) == 0) {
            "wl_proxy_add_listener rejected the xdg_surface listener"
        }
    }

    private companion object {
        const val EVENT_COUNT = 1L
        const val CONFIGURE = 0L

        val CONFIGURE_DESCRIPTOR = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT)
    }
}

/** One value of `xdg_toplevel.state`, which every `configure` carries an array of. */
internal enum class XdgToplevelState(val wireValue: Int) {
    Maximized(1),
    Fullscreen(2),
    Resizing(3),
    Activated(4),
    TiledLeft(5),
    TiledRight(6),
    TiledTop(7),
    TiledBottom(8),
    Suspended(9),
    ConstrainedLeft(10),
    ConstrainedRight(11),
    ConstrainedTop(12),
    ConstrainedBottom(13),
    ;

    companion object {
        /** @return the state [wireValue] names, or null for one this protocol version does not declare. */
        fun fromOrNull(wireValue: Int): XdgToplevelState? = entries.firstOrNull { it.wireValue == wireValue }
    }
}

/**
 * Tracks what `xdg_toplevel` reports about the window: the size and the states each configure carries, and the
 * compositor asking for it to close.
 *
 * [width] and [height] start at the size the window asked for, since a configure of 0 on an axis leaves that
 * axis to the client.
 */
internal class XdgToplevelListener(width: Int, height: Int) {
    @Volatile var width: Int = width
        private set

    @Volatile var height: Int = height
        private set

    @Volatile var states: Set<XdgToplevelState> = emptySet()
        private set

    @Volatile var closed: Boolean = false

    @Volatile private var configured: Boolean = false
    @Volatile private var resized: Boolean = false

    fun onConfigure(
        data: MemorySegment,
        proxy: MemorySegment,
        width: Int,
        height: Int,
        states: MemorySegment,
    ) {
        val newWidth = if (width == CLIENT_CHOOSES) this.width else width
        val newHeight = if (height == CLIENT_CHOOSES) this.height else height
        if (configured && (newWidth != this.width || newHeight != this.height)) resized = true
        this.width = newWidth
        this.height = newHeight
        this.states = statesIn(states)
        configured = true
    }

    fun onClose(data: MemorySegment, proxy: MemorySegment) {
        closed = true
    }

    fun onConfigureBounds(data: MemorySegment, proxy: MemorySegment, width: Int, height: Int) = Unit

    fun onWmCapabilities(data: MemorySegment, proxy: MemorySegment, capabilities: MemorySegment) = Unit

    fun consumeResize(): Boolean {
        if (!resized) return false
        resized = false
        return true
    }

    /** [arena] is the owning surface's, which closes it once the `xdg_toplevel` proxy is destroyed. */
    fun install(arena: Arena, toplevel: MemorySegment) {
        val listener = arena.allocate(ADDRESS.byteSize() * EVENT_COUNT)
        listener.setAtIndex(ADDRESS, CONFIGURE, LibWayland.upcall(arena, this, "onConfigure", CONFIGURE_DESCRIPTOR))
        listener.setAtIndex(ADDRESS, CLOSE, LibWayland.upcall(arena, this, "onClose", CLOSE_DESCRIPTOR))
        listener.setAtIndex(
            ADDRESS, CONFIGURE_BOUNDS,
            LibWayland.upcall(arena, this, "onConfigureBounds", CONFIGURE_BOUNDS_DESCRIPTOR),
        )
        listener.setAtIndex(
            ADDRESS, WM_CAPABILITIES,
            LibWayland.upcall(arena, this, "onWmCapabilities", WM_CAPABILITIES_DESCRIPTOR),
        )
        check(LibWayland.proxyAddListener(toplevel, listener, MemorySegment.NULL) == 0) {
            "wl_proxy_add_listener rejected the xdg_toplevel listener"
        }
    }

    private companion object {
        // xdg_toplevel v7 declares exactly these four events; every slot must be filled, because
        // libwayland indexes the struct and calls straight through it.
        const val EVENT_COUNT = 4L
        const val CONFIGURE = 0L
        const val CLOSE = 1L
        const val CONFIGURE_BOUNDS = 2L
        const val WM_CAPABILITIES = 3L

        /** A configure of 0 on an axis asks the client to pick that axis itself, as every shell reads it. */
        const val CLIENT_CHOOSES = 0

        val CONFIGURE_DESCRIPTOR = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS)
        val CLOSE_DESCRIPTOR = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS)
        val CONFIGURE_BOUNDS_DESCRIPTOR = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT)
        val WM_CAPABILITIES_DESCRIPTOR = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS)
    }
}

/**
 * An `xdg_toplevel`: a window the compositor places, sizes and may ask to close.
 *
 * The client commits once with no buffer, and the compositor answers with `xdg_surface.configure`. Attaching a
 * buffer before that serial is acknowledged is a protocol error and a disconnect.
 */
internal class XdgToplevelSurface private constructor(
    private val display: WaylandDisplay,
    override val surface: MemorySegment,
    private val xdgSurface: MemorySegment,
    private val toplevel: MemorySegment,
    private val wmBase: MemorySegment,
    private val compositor: MemorySegment,
    private val surfaceListener: WlSurfaceListener,
    private val xdgSurfaceListener: XdgSurfaceListener,
    private val toplevelListener: XdgToplevelListener,
    // Holds the stubs of all four listeners above, since one close() gives back every proxy they hang off.
    private val arena: Arena,
) : SurfaceRole {

    // Paired with closed, which says the surface must be torn down rather than that it has been.
    private var disposed = false

    /** A window takes the keyboard whenever the compositor gives it focus. */
    override val wantsKeyboard: Boolean get() = true

    override val logicalWidth: Int get() = toplevelListener.width
    override val logicalHeight: Int get() = toplevelListener.height
    override val closed: Boolean get() = toplevelListener.closed

    override val preferredBufferScale: Int get() = surfaceListener.preferredBufferScale

    /** The states the last configure carried. */
    val maximized: Boolean get() = XdgToplevelState.Maximized in toplevelListener.states
    val fullscreen: Boolean get() = XdgToplevelState.Fullscreen in toplevelListener.states
    val tiled: Boolean get() = toplevelListener.states.any { it in TILED }
    val activated: Boolean get() = XdgToplevelState.Activated in toplevelListener.states

    /** What the compositor shows for this window wherever it names it, such as a task bar. */
    fun setTitle(title: String) = sendString(XdgShellProtocol.SET_TITLE, title)

    /** What the compositor matches this window by, which is how a window rule finds it. */
    fun setAppId(appId: String) = sendString(XdgShellProtocol.SET_APP_ID, appId)

    /**
     * Dispatches until `xdg_surface.configure` has arrived and been acknowledged, and gives up if the compositor
     * closes the window first.
     *
     * @return `Ok` once configured; else the connection's error when it died before a configure came, else
     *   [KortexError.SurfaceNotConfigured].
     */
    override fun waitForConfigure(): EmptyResult<KortexError> {
        display.roundtrip()
        val deadline = System.nanoTime() + CONFIGURE_TIMEOUT_MILLIS * NANOS_PER_MILLI
        // Dispatched in slices rather than blocking: a compositor that answers nothing at all must still
        // leave this call, and the whole build behind it, with an error rather than a hang.
        while (!xdgSurfaceListener.configured && !closed && System.nanoTime() < deadline) {
            display.dispatch(DISPATCH_SLICE_MILLIS)
        }
        if (xdgSurfaceListener.configured) return Ok(Unit)
        // A dead connection surfaces first as an unconfigured surface; prefer the real cause.
        return display.requireAlive().flatMap { Err(KortexError.SurfaceNotConfigured) }
    }

    override fun consumeResize(): Boolean = toplevelListener.consumeResize()

    override fun markClosed() {
        toplevelListener.closed = true
    }

    override fun attach(buffer: ShmBuffer) {
        LibWayland.marshal(
            surface, WL_SURFACE_ATTACH,
            args = listOf(WlArg.Ptr(buffer.buffer), WlArg.Num(0), WlArg.Num(0)),
        )
        LibWayland.marshal(
            surface, WL_SURFACE_DAMAGE_BUFFER,
            args = listOf(WlArg.Num(0), WlArg.Num(0), WlArg.Num(buffer.width), WlArg.Num(buffer.height)),
        )
    }

    override fun setBufferScale(scale: Int) {
        LibWayland.marshal(surface, WL_SURFACE_SET_BUFFER_SCALE, args = listOf(WlArg.Num(scale)))
    }

    override fun commit() {
        LibWayland.marshal(surface, WL_SURFACE_COMMIT)
        display.flush()
    }

    override fun close() {
        if (disposed) return
        disposed = true
        // Innermost first: an xdg_surface destroyed before its role object, or a shell destroyed before an
        // xdg_surface it handed out, is a protocol error.
        LibWayland.marshal(toplevel, XdgShellProtocol.TOPLEVEL_DESTROY)
        LibWayland.proxyDestroy(toplevel)
        LibWayland.marshal(xdgSurface, XdgShellProtocol.XDG_SURFACE_DESTROY)
        LibWayland.proxyDestroy(xdgSurface)
        LibWayland.marshal(surface, WL_SURFACE_DESTROY)
        LibWayland.proxyDestroy(surface)
        // Bound per surface like everything else here, so it goes with it rather than at disconnect.
        LibWayland.marshal(wmBase, XdgShellProtocol.WM_BASE_DESTROY)
        LibWayland.proxyDestroy(wmBase)
        // After every destroy, never before: closing the arena frees the code their stubs are.
        arena.close()
        releaseCompositor(compositor)
        display.flush()
    }

    // wl_proxy_marshal copies a string argument into the message it builds, so the value is only borrowed
    // for the call and has no business in an arena that outlives it.
    private fun sendString(opcode: Int, value: String) {
        Arena.ofConfined().use { request ->
            LibWayland.marshal(toplevel, opcode, args = listOf(WlArg.Ptr(request.allocateFrom(value))))
        }
        // Neither request is double-buffered, so neither waits for a commit the window may never make again.
        display.flush()
    }

    companion object {
        /**
         * Creates a window of [title] and [appId], [width] by [height] logical pixels, and commits it with no
         * buffer, which is what the compositor answers with the first configure.
         *
         * @return what binding `wl_compositor` or `xdg_wm_base` failed with, leaving nothing behind.
         */
        fun create(
            display: WaylandDisplay,
            title: String,
            appId: String,
            width: Int,
            height: Int,
        ): Result<XdgToplevelSurface, KortexError> {
            val compositor = display.require("wl_compositor", LibWayland.compositorInterface, WlVersion.COMPOSITOR)
                .getOrElse { return Err(it) }
            val wmBase = display
                .require("xdg_wm_base", XdgShellProtocol.xdgWmBaseInterface, WlVersion.XDG_SHELL)
                .getOrElse {
                    releaseCompositor(compositor)
                    return Err(it)
                }

            // Closed by the XdgToplevelSurface this all ends up in, which is the one owner of every proxy.
            val arena = Arena.ofShared()
            // Before any dispatch, since the compositor may ping as soon as the bind reaches it.
            XdgWmBaseListener(display, wmBase).install(arena)

            val surface = LibWayland.marshal(
                compositor, WL_COMPOSITOR_CREATE_SURFACE, LibWayland.surfaceInterface,
                LibWayland.proxyGetVersion(compositor), listOf(WlArg.Ptr(MemorySegment.NULL)),
            )
            // Before the commit below: the compositor answers it with preferred_buffer_scale and the rest of
            // the surface's initial state.
            val surfaceListener = WlSurfaceListener()
            surfaceListener.install(arena, surface)

            val xdgSurface = LibWayland.marshal(
                wmBase, XdgShellProtocol.GET_XDG_SURFACE, XdgShellProtocol.xdgSurfaceInterface,
                LibWayland.proxyGetVersion(wmBase),
                listOf(WlArg.Ptr(MemorySegment.NULL), WlArg.Ptr(surface)),
            )
            val xdgSurfaceListener = XdgSurfaceListener(xdgSurface)
            xdgSurfaceListener.install(arena)

            val toplevel = LibWayland.marshal(
                xdgSurface, XdgShellProtocol.GET_TOPLEVEL, XdgShellProtocol.xdgToplevelInterface,
                LibWayland.proxyGetVersion(xdgSurface), listOf(WlArg.Ptr(MemorySegment.NULL)),
            )
            val toplevelListener = XdgToplevelListener(width, height)
            toplevelListener.install(arena, toplevel)

            val result = XdgToplevelSurface(
                display, surface, xdgSurface, toplevel, wmBase, compositor,
                surfaceListener, xdgSurfaceListener, toplevelListener, arena,
            )
            result.setTitle(title)
            result.setAppId(appId)
            // The window is described before this commit, and carries no buffer until the configure it draws out
            // has been acknowledged.
            result.commit()
            return Ok(result)
        }

        private val TILED = setOf(
            XdgToplevelState.TiledLeft,
            XdgToplevelState.TiledRight,
            XdgToplevelState.TiledTop,
            XdgToplevelState.TiledBottom,
        )

        private const val WL_COMPOSITOR_CREATE_SURFACE = 0
        private const val WL_SURFACE_DESTROY = 0
        private const val WL_SURFACE_ATTACH = 1
        private const val WL_SURFACE_COMMIT = 6
        private const val WL_SURFACE_SET_BUFFER_SCALE = 8
        private const val WL_SURFACE_DAMAGE_BUFFER = 9

        private const val CONFIGURE_TIMEOUT_MILLIS = 4_000L
        private const val DISPATCH_SLICE_MILLIS = 50L
        private const val NANOS_PER_MILLI = 1_000_000L
    }
}
