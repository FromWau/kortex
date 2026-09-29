package com.fromwau.kortex.wayland

import androidx.compose.ui.unit.IntOffset
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

/**
 * The `uint32_t` entries of a `wl_array`, which is how `xdg_toplevel` sends its states and its capabilities
 * alike. Each is turned into what it names below, and a value kortex does not know is dropped.
 */
private fun wireValuesIn(array: MemorySegment): List<Int> {
    val header = array.reinterpret(WL_ARRAY_BYTES)
    val bytes = header.get(JAVA_LONG, WL_ARRAY_SIZE_OFFSET)
    val data = header.get(ADDRESS, WL_ARRAY_DATA_OFFSET).reinterpret(bytes)
    return (0 until bytes / Int.SIZE_BYTES).map { index -> data.getAtIndex(JAVA_INT, index) }
}

private fun statesIn(array: MemorySegment): Set<XdgToplevelState> =
    wireValuesIn(array).mapNotNullTo(mutableSetOf(), XdgToplevelState::fromOrNull)

private fun capabilitiesIn(array: MemorySegment): Set<XdgToplevelCapability> =
    wireValuesIn(array).mapNotNullTo(mutableSetOf(), XdgToplevelCapability::fromOrNull)

// struct wl_array { size_t size; size_t alloc; void *data; }, which an upcall hands over with no extent.
private const val WL_ARRAY_SIZE_OFFSET = 0L
private const val WL_ARRAY_DATA_OFFSET = 16L
private const val WL_ARRAY_BYTES = 24L

/** Gives back the `xdg_wm_base` a surface bound for itself; every xdg role here binds one of its own. */
private fun destroyWmBase(wmBase: MemorySegment) {
    LibWayland.marshal(wmBase, XdgShellProtocol.WM_BASE_DESTROY)
    LibWayland.proxyDestroy(wmBase)
}

/** The `xdg_shell` tables, from `wayland-scanner private-code xdg-shell.xml`. */
internal object XdgShellProtocol {
    // These two come first because a table resolves its `types` entries in declaration order, and both
    // xdg_surface and xdg_wm_base name them.
    val xdgPositionerInterface: MemorySegment = LibWayland.buildInterface(
        name = "xdg_positioner",
        version = WlVersion.XDG_SHELL,
        requests = listOf(
            WlMessage("destroy", ""),
            WlMessage("set_size", "ii", List(2) { MemorySegment.NULL }),
            WlMessage("set_anchor_rect", "iiii", List(4) { MemorySegment.NULL }),
            WlMessage("set_anchor", "u", listOf(MemorySegment.NULL)),
            WlMessage("set_gravity", "u", listOf(MemorySegment.NULL)),
            WlMessage("set_constraint_adjustment", "u", listOf(MemorySegment.NULL)),
            WlMessage("set_offset", "ii", List(2) { MemorySegment.NULL }),
            WlMessage("set_reactive", "3"),
            WlMessage("set_parent_size", "3ii", List(2) { MemorySegment.NULL }),
            WlMessage("set_parent_configure", "3u", listOf(MemorySegment.NULL)),
        ),
    )

    val xdgPopupInterface: MemorySegment = LibWayland.buildInterface(
        name = "xdg_popup",
        version = WlVersion.XDG_SHELL,
        requests = listOf(
            WlMessage("destroy", ""),
            WlMessage("grab", "ou", listOf(LibWayland.seatInterface, MemorySegment.NULL)),
            WlMessage("reposition", "3ou", listOf(xdgPositionerInterface, MemorySegment.NULL)),
        ),
        events = listOf(
            WlMessage("configure", "iiii", List(4) { MemorySegment.NULL }),
            WlMessage("popup_done", ""),
            WlMessage("repositioned", "3u", listOf(MemorySegment.NULL)),
        ),
    )

    // set_parent's parent is another xdg_toplevel, which only the interface being built can name.
    val xdgToplevelInterface: MemorySegment = LibWayland.buildInterface(
        name = "xdg_toplevel",
        version = WlVersion.XDG_SHELL,
        requests = { self ->
            listOf(
                WlMessage("destroy", ""),
                WlMessage("set_parent", "?o", listOf(self)),
                WlMessage("set_title", "s", listOf(MemorySegment.NULL)),
                WlMessage("set_app_id", "s", listOf(MemorySegment.NULL)),
                WlMessage(
                    "show_window_menu", "ouii",
                    listOf(LibWayland.seatInterface) + List(3) { MemorySegment.NULL },
                ),
                WlMessage("move", "ou", listOf(LibWayland.seatInterface, MemorySegment.NULL)),
                WlMessage("resize", "ouu", listOf(LibWayland.seatInterface) + List(2) { MemorySegment.NULL }),
                WlMessage("set_max_size", "ii", List(2) { MemorySegment.NULL }),
                WlMessage("set_min_size", "ii", List(2) { MemorySegment.NULL }),
                WlMessage("set_maximized", ""),
                WlMessage("unset_maximized", ""),
                WlMessage("set_fullscreen", "?o", listOf(LibWayland.outputInterface)),
                WlMessage("unset_fullscreen", ""),
                WlMessage("set_minimized", ""),
            )
        },
        events = listOf(
            WlMessage("configure", "iia", List(3) { MemorySegment.NULL }),
            WlMessage("close", ""),
            WlMessage("configure_bounds", "4ii", List(2) { MemorySegment.NULL }),
            WlMessage("wm_capabilities", "5a", listOf(MemorySegment.NULL)),
        ),
    )

    // get_popup's parent is another xdg_surface, which only the interface being built can name.
    val xdgSurfaceInterface: MemorySegment = LibWayland.buildInterface(
        name = "xdg_surface",
        version = WlVersion.XDG_SHELL,
        requests = { self ->
            listOf(
                WlMessage("destroy", ""),
                WlMessage("get_toplevel", "n", listOf(xdgToplevelInterface)),
                WlMessage("get_popup", "n?oo", listOf(xdgPopupInterface, self, xdgPositionerInterface)),
                WlMessage("set_window_geometry", "iiii", List(4) { MemorySegment.NULL }),
                WlMessage("ack_configure", "u", listOf(MemorySegment.NULL)),
            )
        },
        events = listOf(
            WlMessage("configure", "u", listOf(MemorySegment.NULL)),
        ),
    )

    val xdgWmBaseInterface: MemorySegment = LibWayland.buildInterface(
        name = "xdg_wm_base",
        version = WlVersion.XDG_SHELL,
        requests = listOf(
            WlMessage("destroy", ""),
            WlMessage("create_positioner", "n", listOf(xdgPositionerInterface)),
            WlMessage("get_xdg_surface", "no", listOf(xdgSurfaceInterface, LibWayland.surfaceInterface)),
            WlMessage("pong", "u", listOf(MemorySegment.NULL)),
        ),
        events = listOf(
            WlMessage("ping", "u", listOf(MemorySegment.NULL)),
        ),
    )

    const val WM_BASE_DESTROY = 0
    const val CREATE_POSITIONER = 1
    const val GET_XDG_SURFACE = 2
    const val PONG = 3

    const val XDG_SURFACE_DESTROY = 0
    const val GET_TOPLEVEL = 1
    const val GET_POPUP = 2
    const val ACK_CONFIGURE = 4

    const val TOPLEVEL_DESTROY = 0
    const val SET_PARENT = 1
    const val SET_TITLE = 2
    const val SET_APP_ID = 3
    const val SET_MAXIMIZED = 9
    const val UNSET_MAXIMIZED = 10
    const val SET_FULLSCREEN = 11
    const val UNSET_FULLSCREEN = 12
    const val SET_MINIMIZED = 13

    const val POSITIONER_DESTROY = 0
    const val SET_POSITIONER_SIZE = 1
    const val SET_ANCHOR_RECT = 2
    const val SET_ANCHOR = 3
    const val SET_GRAVITY = 4
    const val SET_CONSTRAINT_ADJUSTMENT = 5

    const val POPUP_DESTROY = 0
}

/** The `xdg_decoration` tables, from `wayland-scanner private-code xdg-decoration-unstable-v1.xml`. */
internal object XdgDecorationProtocol {
    val toplevelDecorationInterface: MemorySegment = LibWayland.buildInterface(
        name = "zxdg_toplevel_decoration_v1",
        version = WlVersion.XDG_DECORATION,
        requests = listOf(
            WlMessage("destroy", ""),
            WlMessage("set_mode", "u", listOf(MemorySegment.NULL)),
            WlMessage("unset_mode", ""),
        ),
        events = listOf(
            WlMessage("configure", "u", listOf(MemorySegment.NULL)),
        ),
    )

    val decorationManagerInterface: MemorySegment = LibWayland.buildInterface(
        name = "zxdg_decoration_manager_v1",
        version = WlVersion.XDG_DECORATION,
        requests = listOf(
            WlMessage("destroy", ""),
            WlMessage(
                "get_toplevel_decoration", "no",
                listOf(toplevelDecorationInterface, XdgShellProtocol.xdgToplevelInterface),
            ),
        ),
    )

    const val MANAGER_DESTROY = 0
    const val GET_TOPLEVEL_DECORATION = 1

    const val DECORATION_DESTROY = 0
    const val SET_MODE = 1
}

/** One value of `zxdg_toplevel_decoration_v1.mode`: which side draws a window's decoration. */
internal enum class XdgDecorationMode(val wireValue: Int) {
    ClientSide(1),
    ServerSide(2),
    ;

    companion object {
        /** @return the mode [wireValue] names, or null for one this protocol version does not declare. */
        fun fromOrNull(wireValue: Int): XdgDecorationMode? = entries.firstOrNull { it.wireValue == wireValue }
    }
}

/** Reads `zxdg_toplevel_decoration_v1.configure`, the compositor's answer to what the client asked for. */
internal class XdgDecorationListener {
    @Volatile var mode: XdgDecorationMode? = null
        private set

    fun onConfigure(data: MemorySegment, proxy: MemorySegment, mode: Int) {
        this.mode = XdgDecorationMode.fromOrNull(mode)
    }

    /** [arena] is the owning surface's, which closes it once the decoration proxy is destroyed. */
    fun install(arena: Arena, decoration: MemorySegment) {
        val listener = arena.allocate(ADDRESS.byteSize() * EVENT_COUNT)
        listener.setAtIndex(ADDRESS, CONFIGURE, LibWayland.upcall(arena, this, "onConfigure", CONFIGURE_DESCRIPTOR))
        check(LibWayland.proxyAddListener(decoration, listener, MemorySegment.NULL) == 0) {
            "wl_proxy_add_listener rejected the zxdg_toplevel_decoration_v1 listener"
        }
    }

    private companion object {
        const val EVENT_COUNT = 1L
        const val CONFIGURE = 0L

        val CONFIGURE_DESCRIPTOR = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT)
    }
}

/**
 * One window's `zxdg_toplevel_decoration_v1`, and the manager it came off.
 *
 * Creating it asks the compositor to draw the window's decoration; [mode] is what it answers.
 */
private class XdgDecoration private constructor(
    private val manager: MemorySegment,
    private val decoration: MemorySegment,
    private val listener: XdgDecorationListener,
) {
    /** Which side the compositor settled on, and null until it has answered. */
    val mode: XdgDecorationMode? get() = listener.mode

    /** Called before the toplevel this decorates is destroyed; the other order is a protocol error. */
    fun close() {
        LibWayland.marshal(decoration, XdgDecorationProtocol.DECORATION_DESTROY)
        LibWayland.proxyDestroy(decoration)
        // Bound per window like the shell is, and giving it back never touches a decoration it handed out.
        LibWayland.marshal(manager, XdgDecorationProtocol.MANAGER_DESTROY)
        LibWayland.proxyDestroy(manager)
    }

    companion object {
        /** Asks [manager] for [toplevel]'s decoration and for the compositor to draw it. */
        fun create(
            arena: Arena,
            manager: MemorySegment,
            toplevel: MemorySegment,
        ): XdgDecoration {
            val decoration = LibWayland.marshal(
                manager, XdgDecorationProtocol.GET_TOPLEVEL_DECORATION,
                XdgDecorationProtocol.toplevelDecorationInterface, LibWayland.proxyGetVersion(manager),
                listOf(WlArg.Ptr(MemorySegment.NULL), WlArg.Ptr(toplevel)),
            )
            val listener = XdgDecorationListener()
            listener.install(arena, decoration)
            LibWayland.marshal(
                decoration, XdgDecorationProtocol.SET_MODE,
                args = listOf(WlArg.Num(XdgDecorationMode.ServerSide.wireValue)),
            )
            return XdgDecoration(manager, decoration, listener)
        }
    }
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
 * What `xdg_toplevel.wm_capabilities` says the compositor will honour; an ask outside it is ignored rather than
 * refused, so nothing here is a hazard, only a gap content would otherwise have to guess at.
 */
internal enum class XdgToplevelCapability(val wireValue: Int) {
    WindowMenu(1),
    Maximize(2),
    Fullscreen(3),
    Minimize(4),
    ;

    companion object {
        /** What a compositor that never sends the event is taken to honour: the event arrives only from v5. */
        val ALL: Set<XdgToplevelCapability> = entries.toSet()

        /** @return the capability [wireValue] names, or null for one this protocol version does not declare. */
        fun fromOrNull(wireValue: Int): XdgToplevelCapability? = entries.firstOrNull { it.wireValue == wireValue }
    }
}

/**
 * Tracks what `xdg_toplevel` reports about the window: the size and the states each configure carries, what the
 * compositor says it will honour, and the compositor asking for it to close.
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

    // Every capability until the compositor says otherwise: it says so only from v5, and one that cannot honour
    // an ask ignores it, so hiding what a silent compositor would have honoured is the worse guess of the two.
    @Volatile var capabilities: Set<XdgToplevelCapability> = XdgToplevelCapability.ALL
        private set

    /** The compositor has asked for the window to close; whether it does is the client's to decide. */
    @Volatile var closeRequested: Boolean = false
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
        // A call can change a window's size too, which no configure knows about, so only the surface can say
        // whether it is already the size this one carries.
        if (configured) resized = true
        this.width = newWidth
        this.height = newHeight
        this.states = statesIn(states)
        configured = true
    }

    fun onClose(data: MemorySegment, proxy: MemorySegment) {
        closeRequested = true
    }

    /** Takes back the ask the last `close` made, so the next one the compositor sends stands as its own. */
    fun declineClose() {
        closeRequested = false
    }

    fun onConfigureBounds(data: MemorySegment, proxy: MemorySegment, width: Int, height: Int) = Unit

    fun onWmCapabilities(data: MemorySegment, proxy: MemorySegment, capabilities: MemorySegment) {
        this.capabilities = capabilitiesIn(capabilities)
    }

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
    // Exposed, unlike the proxies around it: it's the toplevel a dialog reads to hang off this window.
    val toplevel: MemorySegment,
    private val wmBase: MemorySegment,
    private val compositor: MemorySegment,
    private val wmBaseListener: XdgWmBaseListener,
    private val surfaceListener: WlSurfaceListener,
    private val xdgSurfaceListener: XdgSurfaceListener,
    private val toplevelListener: XdgToplevelListener,
    private val decoration: XdgDecoration,
    // Holds the stubs of every listener above, since one close() gives back every proxy they hang off.
    private val arena: Arena,
) : SurfaceRole {

    // Paired with closed, which says the surface must be torn down rather than that it has been.
    private var disposed = false

    /** A window takes the keyboard whenever the compositor gives it focus. */
    override val wantsKeyboard: Boolean get() = true

    override val popupParent: PopupParent get() = PopupParent.Xdg(xdgSurface)

    override val logicalWidth: Int get() = toplevelListener.width
    override val logicalHeight: Int get() = toplevelListener.height
    override val closed: Boolean get() = toplevelListener.closed

    override val preferredBufferScale: Int get() = surfaceListener.preferredBufferScale

    /** The states the last configure carried. */
    val maximized: Boolean get() = XdgToplevelState.Maximized in toplevelListener.states
    val fullscreen: Boolean get() = XdgToplevelState.Fullscreen in toplevelListener.states
    val tiled: Boolean get() = toplevelListener.states.any { it in TILED }
    val activated: Boolean get() = XdgToplevelState.Activated in toplevelListener.states

    /** The asks the compositor said it would honour, through `wm_capabilities`. */
    val capabilities: Set<XdgToplevelCapability> get() = toplevelListener.capabilities

    /** The compositor has asked for this window to close, which by itself ends nothing. */
    val closeRequested: Boolean get() = toplevelListener.closeRequested

    /** Takes back that ask, so the compositor asking again is an ask of its own. The loop thread only. */
    fun declineClose() = toplevelListener.declineClose()

    /** What the compositor shows for this window wherever it names it, such as a task bar. */
    fun setTitle(title: String) = sendString(XdgShellProtocol.SET_TITLE, title)

    /** What the compositor matches this window by, which is how a window rule finds it. */
    fun setAppId(appId: String) = sendString(XdgShellProtocol.SET_APP_ID, appId)

    /**
     * Asks the compositor to maximize this window, or to take that back; it answers with a configure carrying
     * whatever states it settled on, which need not be the ones asked for. The loop thread only.
     */
    fun askMaximized(maximized: Boolean) =
        send(if (maximized) XdgShellProtocol.SET_MAXIMIZED else XdgShellProtocol.UNSET_MAXIMIZED)

    /** As [askMaximized], for the whole of a monitor; NULL leaves the compositor to choose which monitor. */
    fun askFullscreen(fullscreen: Boolean) = when {
        fullscreen -> send(XdgShellProtocol.SET_FULLSCREEN, listOf(WlArg.Ptr(MemorySegment.NULL)))
        else -> send(XdgShellProtocol.UNSET_FULLSCREEN)
    }

    /** As [askMaximized], except that the protocol carries no state for it and no request back. */
    fun askMinimized() = send(XdgShellProtocol.SET_MINIMIZED)

    /**
     * Dispatches until `xdg_surface.configure` has arrived and been acknowledged, and gives up if the compositor
     * closes the window first, then settles which side draws the decoration.
     *
     * @return `Ok` once configured and decorated by the compositor; else the connection's error when it died
     *   before a configure came, else [KortexError.SurfaceNotConfigured], else
     *   [KortexError.ClientSideDecorationRequired].
     */
    override fun waitForConfigure(): EmptyResult<KortexError> {
        awaitConfigure(display, { xdgSurfaceListener.configured }, { closed }).getOrElse { return Err(it) }
        // Silence counts as client side, which is what the decoration protocol says an unanswered ask means.
        // Hyprland 0.56.2 answers server side to every ask, so this is read rather than run on this desktop.
        if (decoration.mode != XdgDecorationMode.ServerSide) return Err(KortexError.ClientSideDecorationRequired)
        return Ok(Unit)
    }

    override fun consumeResize(): Boolean = toplevelListener.consumeResize()

    override fun markClosed() {
        toplevelListener.closed = true
    }

    override fun attach(buffer: ShmBuffer) = attachWholeBuffer(surface, buffer)

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
        // Innermost first: a decoration destroyed after the toplevel it decorates, an xdg_surface destroyed
        // before its role object, or a shell destroyed before an xdg_surface it handed out, is a protocol error.
        decoration.close()
        LibWayland.marshal(toplevel, XdgShellProtocol.TOPLEVEL_DESTROY)
        LibWayland.proxyDestroy(toplevel)
        LibWayland.marshal(xdgSurface, XdgShellProtocol.XDG_SURFACE_DESTROY)
        LibWayland.proxyDestroy(xdgSurface)
        LibWayland.marshal(surface, WL_SURFACE_DESTROY)
        LibWayland.proxyDestroy(surface)
        // Bound per surface like everything else here, so it goes with it rather than at disconnect.
        destroyWmBase(wmBase)
        // After every destroy, never before: closing the arena frees the code their stubs are.
        arena.close()
        releaseCompositor(compositor)
        display.flush()
    }

    // wl_proxy_marshal copies a string argument into the message it builds, so the value is only borrowed
    // for the call and has no business in an arena that outlives it.
    private fun sendString(opcode: Int, value: String) {
        Arena.ofConfined().use { request -> send(opcode, listOf(WlArg.Ptr(request.allocateFrom(value)))) }
    }

    private fun send(
        opcode: Int,
        args: List<WlArg> = emptyList(),
    ) {
        LibWayland.marshal(toplevel, opcode, args = args)
        // None of these requests is double-buffered, so none waits for a commit the window may never make again.
        display.flush()
    }

    companion object {
        /**
         * Creates a window of [title] and [appId], [width] by [height] logical pixels, and commits it with no
         * buffer, which is what the compositor answers with the first configure.
         *
         * @param parent another `xdg_toplevel` this one hangs off, which is what makes it a dialog; left NULL
         *   the window stands on its own.
         * @return what binding `wl_compositor`, `xdg_wm_base` or `zxdg_decoration_manager_v1` failed with,
         *   leaving nothing behind.
         */
        fun create(
            display: WaylandDisplay,
            title: String,
            appId: String,
            width: Int,
            height: Int,
            parent: MemorySegment = MemorySegment.NULL,
        ): Result<XdgToplevelSurface, KortexError> {
            val compositor = display.require("wl_compositor", LibWayland.compositorInterface, WlVersion.COMPOSITOR)
                .getOrElse { return Err(it) }
            val wmBase = display
                .require("xdg_wm_base", XdgShellProtocol.xdgWmBaseInterface, WlVersion.XDG_SHELL)
                .getOrElse {
                    releaseCompositor(compositor)
                    return Err(it)
                }
            // Bound before any object is created, so the one step here that can fail has nothing to unwind.
            // Absent, the protocol itself says the client self-decorates, which is what this error means.
            val decorationManager = display.global("zxdg_decoration_manager_v1")
                ?.let { display.bind(it, XdgDecorationProtocol.decorationManagerInterface, WlVersion.XDG_DECORATION) }
                ?: run {
                    destroyWmBase(wmBase)
                    releaseCompositor(compositor)
                    return display.requireAlive().flatMap { Err(KortexError.ClientSideDecorationRequired) }
                }

            // Closed by the XdgToplevelSurface this all ends up in, which is the one owner of every proxy.
            val arena = Arena.ofShared()
            // Before any dispatch, since the compositor may ping as soon as the bind reaches it.
            val wmBaseListener = XdgWmBaseListener(display, wmBase)
            wmBaseListener.install(arena)

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

            // Before the commit that describes the window, since the compositor reads the parent as it places it.
            if (parent != MemorySegment.NULL) {
                LibWayland.marshal(toplevel, XdgShellProtocol.SET_PARENT, args = listOf(WlArg.Ptr(parent)))
            }

            val decoration = XdgDecoration.create(arena, decorationManager, toplevel)

            val result = XdgToplevelSurface(
                display, surface, xdgSurface, toplevel, wmBase, compositor, wmBaseListener,
                surfaceListener, xdgSurfaceListener, toplevelListener, decoration, arena,
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
    }
}

/**
 * Builds a surface on an xdg toplevel of [settings], hanging off [parent] where that names another toplevel.
 *
 * @return what [XdgToplevelSurface.create] could not bind for, or what the engine around it failed on, with
 *   nothing of either left behind.
 */
internal fun KortexSurface.Companion.createOnToplevel(
    display: WaylandDisplay,
    settings: ToplevelSettings,
    parent: MemorySegment,
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
) {
    XdgToplevelSurface.create(
        display,
        title = settings.title,
        appId = settings.appId,
        width = settings.width.toLogicalPx(),
        height = settings.height.toLogicalPx(),
        parent = parent,
    )
}

/**
 * Applies [new] to a live surface built on an xdg toplevel, sending only what differs from [placed]: a changed
 * title or app id takes effect on its own rather than at the next commit, and a changed size draws content at
 * that size until the compositor configures one of its own.
 *
 * @return `Ok` once every change has reached the window, or why a changed size could not be drawn, which leaves a
 *   changed title and app id in place and the window at the size it had.
 */
internal fun KortexSurface.applyToplevel(
    placed: ToplevelSettings,
    new: ToplevelSettings,
): EmptyResult<KortexError> {
    val toplevel = role.asToplevel()
    if (new.title != placed.title) toplevel.setTitle(new.title)
    if (new.appId != placed.appId) toplevel.setAppId(new.appId)
    if (new.width == placed.width && new.height == placed.height) return Ok(Unit)
    return resizeTo(new.width.toLogicalPx(), new.height.toLogicalPx())
}

/** Only the factory above places a surface [ToplevelSettings] reaches, and it builds every one on a toplevel. */
private fun SurfaceRole.asToplevel(): XdgToplevelSurface {
    check(this is XdgToplevelSurface) { "a toplevel request reached a surface built on another role" }
    return this
}

/**
 * Tracks what `xdg_popup` reports: where the compositor put the popup and how big it made it, and the
 * compositor dismissing it.
 *
 * [width] and [height] start at the size the positioner asked for, which is what a compositor answers with
 * unless it has to shrink the popup to keep it on screen.
 */
internal class XdgPopupListener(width: Int, height: Int) {
    @Volatile var x: Int = 0
        private set

    @Volatile var y: Int = 0
        private set

    @Volatile var width: Int = width
        private set

    @Volatile var height: Int = height
        private set

    @Volatile var closed: Boolean = false

    @Volatile private var configured: Boolean = false
    @Volatile private var resized: Boolean = false

    fun onConfigure(
        data: MemorySegment,
        proxy: MemorySegment,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ) {
        // Nothing but a configure moves or resizes a popup, so one carrying the size it already has is no
        // resize at all.
        if (configured && (width != this.width || height != this.height)) resized = true
        this.x = x
        this.y = y
        this.width = width
        this.height = height
        configured = true
    }

    fun onPopupDone(data: MemorySegment, proxy: MemorySegment) {
        closed = true
    }

    fun onRepositioned(data: MemorySegment, proxy: MemorySegment, token: Int) = Unit

    fun consumeResize(): Boolean {
        if (!resized) return false
        resized = false
        return true
    }

    /** [arena] is the owning surface's, which closes it once the `xdg_popup` proxy is destroyed. */
    fun install(arena: Arena, popup: MemorySegment) {
        val listener = arena.allocate(ADDRESS.byteSize() * EVENT_COUNT)
        listener.setAtIndex(ADDRESS, CONFIGURE, LibWayland.upcall(arena, this, "onConfigure", CONFIGURE_DESCRIPTOR))
        listener.setAtIndex(ADDRESS, POPUP_DONE, LibWayland.upcall(arena, this, "onPopupDone", POPUP_DONE_DESCRIPTOR))
        listener.setAtIndex(
            ADDRESS, REPOSITIONED,
            LibWayland.upcall(arena, this, "onRepositioned", REPOSITIONED_DESCRIPTOR),
        )
        check(LibWayland.proxyAddListener(popup, listener, MemorySegment.NULL) == 0) {
            "wl_proxy_add_listener rejected the xdg_popup listener"
        }
    }

    private companion object {
        // xdg_popup v7 declares exactly these three events; every slot must be filled, because libwayland
        // indexes the struct and calls straight through it.
        const val EVENT_COUNT = 3L
        const val CONFIGURE = 0L
        const val POPUP_DONE = 1L
        const val REPOSITIONED = 2L

        val CONFIGURE_DESCRIPTOR =
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT)
        val POPUP_DONE_DESCRIPTOR = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS)
        val REPOSITIONED_DESCRIPTOR = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT)
    }
}

/**
 * An `xdg_popup`: a short-lived surface the compositor places against an anchor rectangle inside its parent.
 *
 * Its parent and everything its positioner carries are fixed as it is created, because the compositor copies
 * the positioner's rules there and then. As with every xdg role, the client commits once with no buffer and
 * the compositor answers with `xdg_surface.configure`; attaching before that serial is acknowledged is a
 * protocol error and a disconnect.
 */
internal class XdgPopupSurface private constructor(
    private val display: WaylandDisplay,
    override val surface: MemorySegment,
    private val xdgSurface: MemorySegment,
    private val popup: MemorySegment,
    private val wmBase: MemorySegment,
    private val compositor: MemorySegment,
    private val wmBaseListener: XdgWmBaseListener,
    private val surfaceListener: WlSurfaceListener,
    private val xdgSurfaceListener: XdgSurfaceListener,
    private val popupListener: XdgPopupListener,
    // Holds the stubs of every listener above, since one close() gives back every proxy they hang off.
    private val arena: Arena,
) : SurfaceRole {

    // Paired with closed, which says the surface must be torn down rather than that it has been.
    private var disposed = false

    /** A popup takes no keyboard: kortex asks for none of the explicit grab that would give it one. */
    override val wantsKeyboard: Boolean get() = false

    /** A popup of a popup is how menus nest, and the protocol parents one to the other's `xdg_surface`. */
    override val popupParent: PopupParent get() = PopupParent.Xdg(xdgSurface)

    override val logicalWidth: Int get() = popupListener.width
    override val logicalHeight: Int get() = popupListener.height
    override val closed: Boolean get() = popupListener.closed

    override val preferredBufferScale: Int get() = surfaceListener.preferredBufferScale

    /** Where the compositor put the popup, in logical pixels from its parent's top-left; a test reads it. */
    val placedAt: IntOffset get() = IntOffset(popupListener.x, popupListener.y)

    /**
     * Dispatches until `xdg_surface.configure` has arrived and been acknowledged, and gives up if the
     * compositor dismisses the popup first.
     *
     * @return `Ok` once configured; else the connection's error when it died before a configure came, else
     *   [KortexError.SurfaceNotConfigured], which is also what a popup the compositor never took a parent for
     *   ends with.
     */
    override fun waitForConfigure(): EmptyResult<KortexError> =
        awaitConfigure(display, { xdgSurfaceListener.configured }, { closed })

    override fun consumeResize(): Boolean = popupListener.consumeResize()

    override fun markClosed() {
        popupListener.closed = true
    }

    override fun attach(buffer: ShmBuffer) = attachWholeBuffer(surface, buffer)

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
        LibWayland.marshal(popup, XdgShellProtocol.POPUP_DESTROY)
        LibWayland.proxyDestroy(popup)
        LibWayland.marshal(xdgSurface, XdgShellProtocol.XDG_SURFACE_DESTROY)
        LibWayland.proxyDestroy(xdgSurface)
        LibWayland.marshal(surface, WL_SURFACE_DESTROY)
        LibWayland.proxyDestroy(surface)
        // Bound per surface like everything else here, so it goes with it rather than at disconnect.
        destroyWmBase(wmBase)
        // After every destroy, never before: closing the arena frees the code their stubs are.
        arena.close()
        releaseCompositor(compositor)
        display.flush()
    }

    companion object {
        /**
         * Creates a popup of [width] by [height] logical pixels whose top-left corner sits at [at] inside
         * [parent], and commits it with no buffer, which is what the compositor answers with the first
         * configure.
         *
         * @return what binding `wl_compositor` or `xdg_wm_base` failed with, leaving nothing behind.
         */
        fun create(
            display: WaylandDisplay,
            parent: PopupParent,
            at: IntOffset,
            width: Int,
            height: Int,
        ): Result<XdgPopupSurface, KortexError> {
            val compositor = display.require("wl_compositor", LibWayland.compositorInterface, WlVersion.COMPOSITOR)
                .getOrElse { return Err(it) }
            val wmBase = display
                .require("xdg_wm_base", XdgShellProtocol.xdgWmBaseInterface, WlVersion.XDG_SHELL)
                .getOrElse {
                    releaseCompositor(compositor)
                    return Err(it)
                }

            // Closed by the XdgPopupSurface this all ends up in, which is the one owner of every proxy.
            val arena = Arena.ofShared()
            // Before any dispatch, since the compositor may ping as soon as the bind reaches it.
            val wmBaseListener = XdgWmBaseListener(display, wmBase)
            wmBaseListener.install(arena)

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

            val positioner = createPositioner(wmBase, at, width, height)
            val popup = LibWayland.marshal(
                xdgSurface, XdgShellProtocol.GET_POPUP, XdgShellProtocol.xdgPopupInterface,
                LibWayland.proxyGetVersion(xdgSurface),
                listOf(
                    WlArg.Ptr(MemorySegment.NULL),
                    WlArg.Ptr(parent.xdgSurfaceOrNull),
                    WlArg.Ptr(positioner),
                ),
            )
            // The compositor copied the rules as get_popup carried the positioner, so it is done with.
            LibWayland.marshal(positioner, XdgShellProtocol.POSITIONER_DESTROY)
            LibWayland.proxyDestroy(positioner)

            val popupListener = XdgPopupListener(width, height)
            popupListener.install(arena, popup)

            // Before the commit below, which is the point by which the protocol requires a popup to have a
            // parent, and which is what draws the first configure out.
            if (parent is PopupParent.Layer) parent.surface.adoptPopup(popup)

            val result = XdgPopupSurface(
                display, surface, xdgSurface, popup, wmBase, compositor, wmBaseListener,
                surfaceListener, xdgSurfaceListener, popupListener, arena,
            )
            result.commit()
            return Ok(result)
        }

        /** The parent `get_popup` takes directly, and NULL for one a layer surface adopts afterwards. */
        private val PopupParent.xdgSurfaceOrNull: MemorySegment
            get() = when (this) {
                is PopupParent.Xdg -> xdgSurface
                is PopupParent.Layer -> MemorySegment.NULL
            }

        /**
         * A positioner asking for a [width] by [height] popup whose top-left corner sits at [at] in its
         * parent, opening down and to the right and flipping to the other side of [at] on whichever axis
         * would otherwise run off the screen.
         */
        private fun createPositioner(
            wmBase: MemorySegment,
            at: IntOffset,
            width: Int,
            height: Int,
        ): MemorySegment {
            val positioner = LibWayland.marshal(
                wmBase, XdgShellProtocol.CREATE_POSITIONER, XdgShellProtocol.xdgPositionerInterface,
                LibWayland.proxyGetVersion(wmBase), listOf(WlArg.Ptr(MemorySegment.NULL)),
            )
            LibWayland.marshal(
                positioner, XdgShellProtocol.SET_POSITIONER_SIZE,
                args = listOf(WlArg.Num(width), WlArg.Num(height)),
            )
            LibWayland.marshal(
                positioner, XdgShellProtocol.SET_ANCHOR_RECT,
                args = listOf(WlArg.Num(at.x), WlArg.Num(at.y), WlArg.Num(ANCHOR_SPAN), WlArg.Num(ANCHOR_SPAN)),
            )
            LibWayland.marshal(positioner, XdgShellProtocol.SET_ANCHOR, args = listOf(WlArg.Num(ANCHOR_TOP_LEFT)))
            LibWayland.marshal(
                positioner, XdgShellProtocol.SET_GRAVITY, args = listOf(WlArg.Num(GRAVITY_BOTTOM_RIGHT)),
            )
            LibWayland.marshal(
                positioner, XdgShellProtocol.SET_CONSTRAINT_ADJUSTMENT, args = listOf(WlArg.Num(FLIP_BOTH_AXES)),
            )
            return positioner
        }

        /** `set_anchor_rect` rejects an empty rectangle, so the point asked for is one pixel on each side. */
        private const val ANCHOR_SPAN = 1

        /** `xdg_positioner.anchor`'s `top_left`: the popup hangs off the anchor rectangle's top-left corner. */
        private const val ANCHOR_TOP_LEFT = 5

        /** `xdg_positioner.gravity`'s `bottom_right`: the popup opens down and to the right of that corner. */
        private const val GRAVITY_BOTTOM_RIGHT = 8

        /** `xdg_positioner.constraint_adjustment`'s `flip_x | flip_y`, each axis decided on its own. */
        private const val FLIP_BOTH_AXES = 4 or 8
    }
}

/**
 * Builds a surface on an xdg popup of [settings], parented to [parent].
 *
 * @return what [XdgPopupSurface.create] could not bind for, or what the engine around it failed on, with
 *   nothing of either left behind.
 */
internal fun KortexSurface.Companion.createOnPopup(
    display: WaylandDisplay,
    settings: PopupSettings,
    parent: PopupParent,
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
) {
    XdgPopupSurface.create(
        display,
        parent = parent,
        at = settings.at,
        width = settings.width.toLogicalPx(),
        height = settings.height.toLogicalPx(),
    )
}
