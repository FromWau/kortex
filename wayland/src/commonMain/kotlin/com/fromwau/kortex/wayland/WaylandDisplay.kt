package com.fromwau.kortex.wayland

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.util.concurrent.CopyOnWriteArrayList

/** A global the compositor advertises on the registry. */
public data class WaylandGlobal(
    public val name: Int,
    public val interfaceName: String,
    public val version: Int,
)

/** A connection to a Wayland compositor. */
public class WaylandDisplay private constructor(
    internal val display: MemorySegment,
    private val registry: MemorySegment,
) : AutoCloseable {
    private val mutableGlobals = CopyOnWriteArrayList<WaylandGlobal>()

    /** Live registry snapshot; safe to read from another thread while upcalls append or remove during dispatch. */
    public val globals: List<WaylandGlobal> get() = mutableGlobals

    /** Fires on the thread inside [dispatch]/[roundtrip] when the compositor advertises a new global. */
    public var onGlobalAdded: ((WaylandGlobal) -> Unit)? = null

    /** Fires on the thread inside [dispatch]/[roundtrip] when a previously advertised global goes away. */
    public var onGlobalRemoved: ((WaylandGlobal) -> Unit)? = null

    internal fun addGlobal(global: WaylandGlobal) {
        mutableGlobals += global
        onGlobalAdded?.invoke(global)
    }

    // global_remove carries only the numeric name, so the interface it belonged to has to be looked up.
    internal fun removeGlobal(name: Int) {
        val removed = mutableGlobals.firstOrNull { it.name == name } ?: return
        mutableGlobals.remove(removed)
        onGlobalRemoved?.invoke(removed)
    }

    public fun roundtrip(): Int = LibWayland.displayRoundtrip(display)

    public fun dispatch(): Int = LibWayland.displayDispatch(display)

    /** Dispatches for at most [timeoutMillis], so a loop can also do other work. */
    public fun dispatch(timeoutMillis: Long): Int = LibWayland.displayDispatchTimeout(display, timeoutMillis)

    public fun flush(): Int = LibWayland.displayFlush(display)

    /** Why the connection failed, or null while it is healthy. */
    public fun protocolError(): KortexError? {
        val errno = LibWayland.displayGetError(display)
        if (errno == 0) return null
        // Only EPROTO means the compositor rejected a request; any other errno is the socket dying,
        // and asking for protocol details would return meaningless zeros.
        if (errno != EPROTO) return KortexError.ConnectionError(errno)

        val raw = LibWayland.displayGetProtocolError(display)
        val name = if (raw.iface.equals(MemorySegment.NULL)) {
            null
        } else {
            // The name pointer comes back zero-length; it must be reinterpreted before it can be read as a string.
            LibWayland.interfaceName(raw.iface).reinterpret(Long.MAX_VALUE).getString(0)
        }
        return KortexError.ProtocolViolation(raw.code, name, raw.id)
    }

    public fun global(interfaceName: String): WaylandGlobal? = globals.firstOrNull { it.interfaceName == interfaceName }

    /** `wl_registry_bind`: binds a global at the lower of the compositor's version and [maxVersion]. */
    internal fun bind(global: WaylandGlobal, iface: MemorySegment, maxVersion: Int): MemorySegment {
        val version = minOf(global.version, maxVersion)
        return LibWayland.marshal(
            proxy = registry,
            opcode = WL_REGISTRY_BIND,
            iface = iface,
            version = version,
            args = listOf(
                WlArg.Num(global.name),
                WlArg.Ptr(LibWayland.interfaceName(iface)),
                WlArg.Num(version),
                WlArg.Ptr(MemorySegment.NULL),
            ),
        )
    }

    internal fun require(
        interfaceName: String,
        iface: MemorySegment,
        maxVersion: Int,
    ): Result<MemorySegment, KortexError> {
        val global = global(interfaceName)
            // A dead connection surfaces first as a missing global; the connection itself knows the real cause.
            ?: return Err(protocolError() ?: KortexError.MissingGlobal(interfaceName))
        return Ok(bind(global, iface, maxVersion))
    }

    override fun close(): Unit = LibWayland.displayDisconnect(display)

    public companion object {
        /** Connects to [name], or to `$WAYLAND_DISPLAY` when null. */
        public fun connect(name: String? = null): Result<WaylandDisplay, KortexError> {
            val target = if (name == null) MemorySegment.NULL else LibWayland.cString(name)
            val display = LibWayland.displayConnect(target)
            if (display.equals(MemorySegment.NULL)) return Err(KortexError.NoCompositorResponse)

            val registry = LibWayland.marshal(
                proxy = display,
                opcode = WL_DISPLAY_GET_REGISTRY,
                iface = LibWayland.registryInterface,
                version = LibWayland.proxyGetVersion(display),
                args = listOf(WlArg.Ptr(MemorySegment.NULL)),
            )

            val waylandDisplay = WaylandDisplay(display, registry)
            // The listener struct and its stubs are handed to the compositor for the life of the
            // registry, so they live in the global arena rather than a scope that could close first.
            val sink = RegistryListener(waylandDisplay)
            val listener = LibWayland.arena.allocate(ADDRESS.byteSize() * 2)
            listener.setAtIndex(ADDRESS, 0L, LibWayland.upcall(sink, "onGlobal", GLOBAL_DESCRIPTOR))
            listener.setAtIndex(ADDRESS, 1L, LibWayland.upcall(sink, "onGlobalRemove", GLOBAL_REMOVE_DESCRIPTOR))
            check(LibWayland.proxyAddListener(registry, listener, MemorySegment.NULL) == 0) {
                "wl_proxy_add_listener rejected the registry listener"
            }
            if (LibWayland.displayRoundtrip(display) < 0) {
                val protocolError = waylandDisplay.protocolError()
                return Err(protocolError ?: KortexError.NoCompositorResponse)
            }

            return Ok(waylandDisplay)
        }

        private const val WL_DISPLAY_GET_REGISTRY = 1
        private const val WL_REGISTRY_BIND = 0

        // errno.h's EPROTO; libwayland reports protocol errors through it but exposes no constant of its own.
        private const val EPROTO = 71

        private val GLOBAL_DESCRIPTOR = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT)
        private val GLOBAL_REMOVE_DESCRIPTOR = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT)
    }
}

internal class RegistryListener(private val display: WaylandDisplay) {
    fun onGlobal(data: MemorySegment, registry: MemorySegment, name: Int, iface: MemorySegment, version: Int) {
        // The char* arrives with zero length because C says nothing about its extent.
        display.addGlobal(WaylandGlobal(name, iface.reinterpret(Long.MAX_VALUE).getString(0), version))
    }

    fun onGlobalRemove(data: MemorySegment, registry: MemorySegment, name: Int) = display.removeGlobal(name)
}
