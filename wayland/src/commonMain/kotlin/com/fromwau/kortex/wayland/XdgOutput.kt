package com.fromwau.kortex.wayland

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT

/** What the compositor advertises `zxdg_output_manager_v1` as on the registry. */
internal const val XDG_OUTPUT_MANAGER: String = "zxdg_output_manager_v1"

/** The `zxdg_output_manager_v1` tables, from `wayland-scanner private-code xdg-output-unstable-v1.xml`. */
internal object XdgOutputProtocol {
    val xdgOutputInterface: MemorySegment = LibWayland.buildInterface(
        name = "zxdg_output_v1",
        version = WlVersion.XDG_OUTPUT,
        requests = listOf(WlMessage("destroy", "")),
        events = listOf(
            WlMessage("logical_position", "ii", List(2) { MemorySegment.NULL }),
            WlMessage("logical_size", "ii", List(2) { MemorySegment.NULL }),
            WlMessage("done", ""),
            WlMessage("name", "2s", listOf(MemorySegment.NULL)),
            WlMessage("description", "2s", listOf(MemorySegment.NULL)),
        ),
    )

    val xdgOutputManagerInterface: MemorySegment = LibWayland.buildInterface(
        name = XDG_OUTPUT_MANAGER,
        version = WlVersion.XDG_OUTPUT,
        requests = listOf(
            WlMessage("destroy", ""),
            WlMessage("get_xdg_output", "no", listOf(xdgOutputInterface, LibWayland.outputInterface)),
        ),
    )

    const val MANAGER_DESTROY = 0
    const val GET_XDG_OUTPUT = 1

    const val DESTROY = 0
}

/** Gives a `zxdg_output_manager_v1` back and frees it; taking it back never touches an output it handed out. */
internal fun destroyXdgOutputManager(manager: MemorySegment) {
    LibWayland.marshal(manager, XdgOutputProtocol.MANAGER_DESTROY)
    LibWayland.proxyDestroy(manager)
}

/**
 * What a `zxdg_output_v1` reports about one output: where the compositor puts it in the space it arranges
 * surfaces in, and how large it is there. Both are exact at a fractional scale, where `wl_output`'s integer
 * scale is not, and both already account for the output's transform.
 *
 * Nothing is kept from `done`, `name` and `description`: at version 3 all three are deprecated in favour of
 * `wl_output`'s own, and Hyprland answers `get_xdg_output` with a `wl_output.done` instead. Their slots are
 * filled all the same, because libwayland indexes the listener struct positionally and calls straight through
 * an empty one.
 */
internal class XdgOutputListener {
    /** The last `logical_position`, or null until one arrives. */
    var logicalPosition: IntOffset? = null
        private set

    /** The last `logical_size`, or null until one arrives. */
    var logicalSize: IntSize? = null
        private set

    fun onLogicalPosition(data: MemorySegment, proxy: MemorySegment, x: Int, y: Int) {
        logicalPosition = IntOffset(x, y)
    }

    fun onLogicalSize(data: MemorySegment, proxy: MemorySegment, width: Int, height: Int) {
        logicalSize = IntSize(width, height)
    }

    fun onDone(data: MemorySegment, proxy: MemorySegment) = Unit

    fun onName(data: MemorySegment, proxy: MemorySegment, name: MemorySegment) = Unit

    fun onDescription(data: MemorySegment, proxy: MemorySegment, description: MemorySegment) = Unit

    /** [arena] is the [XdgOutput]'s own, which closes it once the `zxdg_output_v1` is destroyed. */
    fun install(arena: Arena, xdgOutput: MemorySegment) {
        val listener = arena.allocate(ADDRESS.byteSize() * EVENT_COUNT)
        listener.setAtIndex(
            ADDRESS, LOGICAL_POSITION,
            LibWayland.upcall(arena, this, "onLogicalPosition", POINT_DESCRIPTOR),
        )
        listener.setAtIndex(
            ADDRESS, LOGICAL_SIZE,
            LibWayland.upcall(arena, this, "onLogicalSize", POINT_DESCRIPTOR),
        )
        listener.setAtIndex(ADDRESS, DONE, LibWayland.upcall(arena, this, "onDone", DONE_DESCRIPTOR))
        listener.setAtIndex(ADDRESS, NAME, LibWayland.upcall(arena, this, "onName", STRING_DESCRIPTOR))
        listener.setAtIndex(
            ADDRESS, DESCRIPTION,
            LibWayland.upcall(arena, this, "onDescription", STRING_DESCRIPTOR),
        )
        check(LibWayland.proxyAddListener(xdgOutput, listener, MemorySegment.NULL) == 0) {
            "wl_proxy_add_listener rejected the zxdg_output_v1 listener"
        }
    }

    private companion object {
        const val EVENT_COUNT = 5L
        const val LOGICAL_POSITION = 0L
        const val LOGICAL_SIZE = 1L
        const val DONE = 2L
        const val NAME = 3L
        const val DESCRIPTION = 4L

        val POINT_DESCRIPTOR = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT)
        val DONE_DESCRIPTOR = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS)
        val STRING_DESCRIPTOR = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS)
    }
}

/** The `zxdg_output_v1` taken for one `wl_output`, and the arena holding the five stubs it dispatches into. */
internal class XdgOutput private constructor(
    private val proxy: MemorySegment,
    private val arena: Arena,
) {
    /** Gives the object back. The order is the point: the proxy first, then the stubs it dispatches into. */
    fun destroy() {
        LibWayland.marshal(proxy, XdgOutputProtocol.DESTROY)
        LibWayland.proxyDestroy(proxy)
        arena.close()
    }

    companion object {
        /**
         * Asks [manager] for [output]'s `zxdg_output_v1`, reporting what it publishes into [reports].
         *
         * Send this before the round trip that waits for the output's first `wl_output.done`: the compositor
         * answers `get_xdg_output` with the logical position and size and then that very `done`.
         */
        fun take(manager: MemorySegment, output: MemorySegment, reports: XdgOutputListener): XdgOutput {
            val proxy = LibWayland.marshal(
                manager, XdgOutputProtocol.GET_XDG_OUTPUT, XdgOutputProtocol.xdgOutputInterface,
                LibWayland.proxyGetVersion(manager),
                listOf(WlArg.Ptr(MemorySegment.NULL), WlArg.Ptr(output)),
            )
            // Closed by the XdgOutput this ends up in, which is the one owner of the proxy the stubs serve.
            val arena = Arena.ofShared()
            reports.install(arena, proxy)
            return XdgOutput(proxy, arena)
        }
    }
}
