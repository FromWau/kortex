package com.fromwau.kortex.wayland

import com.fromwau.kern.result.Result
import com.fromwau.kern.result.map
import java.lang.foreign.MemorySegment

/** The `zwlr_virtual_pointer_v1` tables, from `wayland-scanner private-code wlr-virtual-pointer-unstable-v1.xml`. */
internal object VirtualPointerProtocol {
    val virtualPointerInterface: MemorySegment = LibWayland.buildInterface(
        name = "zwlr_virtual_pointer_v1",
        version = WlVersion.VIRTUAL_POINTER,
        requests = listOf(
            WlMessage("motion", "uff", List(3) { MemorySegment.NULL }),
            WlMessage("motion_absolute", "uuuuu", List(5) { MemorySegment.NULL }),
            WlMessage("button", "uuu", List(3) { MemorySegment.NULL }),
            WlMessage("axis", "uuf", List(3) { MemorySegment.NULL }),
            WlMessage("frame", ""),
            WlMessage("axis_source", "u", listOf(MemorySegment.NULL)),
            WlMessage("axis_stop", "uu", List(2) { MemorySegment.NULL }),
            WlMessage("axis_discrete", "uufi", List(4) { MemorySegment.NULL }),
            WlMessage("destroy", ""),
        ),
    )

    val virtualPointerManagerInterface: MemorySegment = LibWayland.buildInterface(
        name = "zwlr_virtual_pointer_manager_v1",
        version = WlVersion.VIRTUAL_POINTER,
        requests = listOf(
            WlMessage(
                "create_virtual_pointer", "?on",
                listOf(LibWayland.seatInterface, virtualPointerInterface),
            ),
            WlMessage("destroy", ""),
            WlMessage(
                "create_virtual_pointer_with_output", "2?o?on",
                listOf(LibWayland.seatInterface, LibWayland.outputInterface, virtualPointerInterface),
            ),
        ),
    )

    const val MOTION_ABSOLUTE = 1
    const val BUTTON = 2
    const val FRAME = 4
    const val POINTER_DESTROY = 8

    const val CREATE_VIRTUAL_POINTER = 0
}

/**
 * A `zwlr_virtual_pointer_v1`: lets this client synthesise pointer input as if a real device produced it.
 *
 * Every request is buffered by the compositor until [frame] commits the group, so a [motionAbsolute]
 * or [button] with no [frame] after it does nothing.
 */
internal class VirtualPointer internal constructor(private val pointer: MemorySegment) : AutoCloseable {

    /** Moves to `(x, y)`, both normalised against `(extentWidth, extentHeight)` in compositor space. */
    fun motionAbsolute(x: Int, y: Int, extentWidth: Int, extentHeight: Int, timeMillis: Int = 0) {
        LibWayland.marshal(
            pointer, VirtualPointerProtocol.MOTION_ABSOLUTE,
            args = listOf(
                WlArg.Num(timeMillis), WlArg.Num(x), WlArg.Num(y),
                WlArg.Num(extentWidth), WlArg.Num(extentHeight),
            ),
        )
    }

    /** [code] is a `linux/input-event-codes.h` button code, e.g. `BTN_LEFT` (0x110). */
    fun button(code: Int, pressed: Boolean, timeMillis: Int = 0) {
        LibWayland.marshal(
            pointer, VirtualPointerProtocol.BUTTON,
            args = listOf(WlArg.Num(timeMillis), WlArg.Num(code), WlArg.Num(if (pressed) 1 else 0)),
        )
    }

    /** Commits every request sent since the last [frame] as one logical event group. */
    fun frame() {
        LibWayland.marshal(pointer, VirtualPointerProtocol.FRAME)
    }

    override fun close() {
        LibWayland.marshal(pointer, VirtualPointerProtocol.POINTER_DESTROY)
        LibWayland.proxyDestroy(pointer)
    }
}

/** Binds `zwlr_virtual_pointer_manager_v1` to create [VirtualPointer]s. */
internal class VirtualPointerManager private constructor(private val manager: MemorySegment) {

    /** [seat] is only a suggestion to the compositor; NULL lets it assign the default seat. */
    fun createVirtualPointer(seat: MemorySegment = MemorySegment.NULL): VirtualPointer {
        val pointer = LibWayland.marshal(
            manager, VirtualPointerProtocol.CREATE_VIRTUAL_POINTER, VirtualPointerProtocol.virtualPointerInterface,
            LibWayland.proxyGetVersion(manager), args = listOf(WlArg.Ptr(seat), WlArg.Ptr(MemorySegment.NULL)),
        )
        return VirtualPointer(pointer)
    }

    companion object {
        fun bind(display: WaylandDisplay): Result<VirtualPointerManager, KortexError> =
            display.require(
                "zwlr_virtual_pointer_manager_v1",
                VirtualPointerProtocol.virtualPointerManagerInterface,
                WlVersion.VIRTUAL_POINTER,
            ).map { manager -> VirtualPointerManager(manager) }
    }
}
