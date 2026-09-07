package com.fromwau.kortex.wayland

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.StructLayout
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles

/** One argument of a marshalled request. */
internal sealed interface WlArg {
    @JvmInline value class Num(val value: Int) : WlArg
    @JvmInline value class Ptr(val value: MemorySegment) : WlArg
}

/** One entry of a hand-built `wl_message` table. */
internal data class WlMessage(
    val name: String,
    val signature: String,
    val types: List<MemorySegment> = emptyList(),
)

/** Raw `wl_display_get_protocol_error` result; `iface` may be NULL. */
internal data class ProtocolError(val code: Int, val iface: MemorySegment, val id: Int)

/**
 * The version each global is bound at.
 *
 * Bind the newest the protocol defines, except where kortex installs a listener: libwayland indexes
 * the listener struct positionally and calls straight through the empty slots a newer version adds.
 */
internal object WlVersion {
    /** wl_surface's `set_buffer_scale` arrives at v3 and `damage_buffer` at v4; both are sent here. */
    const val COMPOSITOR = 6
    const val SHM = 2
    const val LAYER_SHELL = 5
    const val VIRTUAL_POINTER = 2

    /** Pinned: wl_pointer and wl_keyboard inherit it, and all three listeners implement only v1's events. */
    const val SEAT = 1

    /** Pinned: the listener implements geometry/mode/done/scale; v4 adds `name`/`description`. */
    const val OUTPUT = 2
}

/**
 * The exported surface of libwayland-client.
 *
 * Its 169 request wrappers are `static inline`, so nothing exports them; every request here is the
 * `wl_proxy_marshal_flags` call they expand to.
 */
internal object LibWayland {
    private val linker: Linker = Linker.nativeLinker()

    // Global: stubs, interface tables and strings handed to the compositor must outlive every proxy
    // that references them, and a proxy can outlive any scope we could tie them to.
    val arena: Arena = Arena.global()

    private val lookup: SymbolLookup = SymbolLookup.libraryLookup("libwayland-client.so.0", arena)

    private fun symbol(name: String): MemorySegment =
        lookup.find(name).orElseThrow { UnsatisfiedLinkError("libwayland-client.so.0 exports no $name") }

    private fun downcall(name: String, descriptor: FunctionDescriptor, vararg options: Linker.Option) =
        linker.downcallHandle(symbol(name), descriptor, *options)

    private val displayConnect = downcall("wl_display_connect", FunctionDescriptor.of(ADDRESS, ADDRESS))
    private val displayDisconnect = downcall("wl_display_disconnect", FunctionDescriptor.ofVoid(ADDRESS))
    private val displayRoundtrip = downcall("wl_display_roundtrip", FunctionDescriptor.of(JAVA_INT, ADDRESS))
    private val displayDispatch = downcall("wl_display_dispatch", FunctionDescriptor.of(JAVA_INT, ADDRESS))
    private val displayDispatchTimeout =
        downcall("wl_display_dispatch_timeout", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
    private val displayFlush = downcall("wl_display_flush", FunctionDescriptor.of(JAVA_INT, ADDRESS))
    private val displayGetError = downcall("wl_display_get_error", FunctionDescriptor.of(JAVA_INT, ADDRESS))
    private val displayGetProtocolError =
        downcall("wl_display_get_protocol_error", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS))
    private val proxyGetVersion = downcall("wl_proxy_get_version", FunctionDescriptor.of(JAVA_INT, ADDRESS))
    private val proxyDestroy = downcall("wl_proxy_destroy", FunctionDescriptor.ofVoid(ADDRESS))
    private val proxyAddListener =
        downcall("wl_proxy_add_listener", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS))

    val registryInterface: MemorySegment = symbol("wl_registry_interface")
    val compositorInterface: MemorySegment = symbol("wl_compositor_interface")
    val surfaceInterface: MemorySegment = symbol("wl_surface_interface")
    val outputInterface: MemorySegment = symbol("wl_output_interface")
    val shmInterface: MemorySegment = symbol("wl_shm_interface")
    val shmPoolInterface: MemorySegment = symbol("wl_shm_pool_interface")
    val bufferInterface: MemorySegment = symbol("wl_buffer_interface")
    val seatInterface: MemorySegment = symbol("wl_seat_interface")
    val callbackInterface: MemorySegment = symbol("wl_callback_interface")
    val pointerInterface: MemorySegment = symbol("wl_pointer_interface")
    val keyboardInterface: MemorySegment = symbol("wl_keyboard_interface")

    fun displayConnect(name: MemorySegment): MemorySegment = displayConnect.invoke(name) as MemorySegment
    fun displayDisconnect(display: MemorySegment) { displayDisconnect.invoke(display) }
    fun displayRoundtrip(display: MemorySegment): Int = displayRoundtrip.invoke(display) as Int
    fun displayDispatch(display: MemorySegment): Int = displayDispatch.invoke(display) as Int

    /**
     * `wl_display_dispatch_timeout`: returns after [timeoutMillis] on an idle connection, where
     * [displayDispatch] blocks until an event arrives.
     */
    fun displayDispatchTimeout(display: MemorySegment, timeoutMillis: Long): Int {
        Arena.ofConfined().use { confined ->
            val timeout = confined.allocate(TIMESPEC)
            timeout.set(JAVA_LONG, TV_SEC_OFFSET, timeoutMillis / MILLIS_PER_SECOND)
            timeout.set(JAVA_LONG, TV_NSEC_OFFSET, (timeoutMillis % MILLIS_PER_SECOND) * NANOS_PER_MILLI)
            return displayDispatchTimeout.invoke(display, timeout) as Int
        }
    }

    fun displayFlush(display: MemorySegment): Int = displayFlush.invoke(display) as Int
    fun displayGetError(display: MemorySegment): Int = displayGetError.invoke(display) as Int

    /** `wl_display_get_protocol_error(display, &interface, &id)`. */
    fun displayGetProtocolError(display: MemorySegment): ProtocolError {
        // Native code fills both out-params before returning, so they outlive nothing.
        Arena.ofConfined().use { confined ->
            val ifaceOut = confined.allocate(ADDRESS)
            val idOut = confined.allocate(JAVA_INT)
            val code = displayGetProtocolError.invoke(display, ifaceOut, idOut) as Int
            return ProtocolError(code, ifaceOut.get(ADDRESS, 0L), idOut.get(JAVA_INT, 0L))
        }
    }

    fun proxyGetVersion(proxy: MemorySegment): Int = proxyGetVersion.invoke(proxy) as Int
    fun proxyDestroy(proxy: MemorySegment) { proxyDestroy.invoke(proxy) }

    fun proxyAddListener(proxy: MemorySegment, implementation: MemorySegment, data: MemorySegment): Int =
        proxyAddListener.invoke(proxy, implementation, data) as Int

    /**
     * Binds [method] on [target] as an upcall stub for a listener slot.
     *
     * The Java signature is derived from [descriptor], because a mismatch between the two crashes inside
     * native code rather than failing to compile. [method] must be public — Kotlin mangles an `internal`
     * name out of the lookup's reach.
     */
    fun upcall(target: Any, method: String, descriptor: FunctionDescriptor): MemorySegment {
        val handle = MethodHandles.publicLookup()
            .findVirtual(target.javaClass, method, descriptor.toMethodType())
            .bindTo(target)
        return linker.upcallStub(handle, descriptor, arena)
    }

    fun cString(value: String): MemorySegment = arena.allocateFrom(value)

    /** The `name` field of a `wl_interface`, which `wl_registry_bind` passes back to the compositor. */
    // A symbol from SymbolLookup is a zero-length segment, so its extent has to be restated before
    // any field can be read out of it.
    fun interfaceName(iface: MemorySegment): MemorySegment =
        iface.reinterpret(INTERFACE.byteSize()).get(ADDRESS, NAME_OFFSET)

    /**
     * `wl_proxy_marshal_flags(proxy, opcode, interface, version, flags, ...)`.
     *
     * Pass [iface] as NULL for a request that creates no object.
     */
    fun marshal(
        proxy: MemorySegment,
        opcode: Int,
        iface: MemorySegment = MemorySegment.NULL,
        version: Int = 0,
        args: List<WlArg> = emptyList(),
    ): MemorySegment {
        val shape = args.joinToString("") { if (it is WlArg.Num) "n" else "p" }
        // FFM wants the variadic layouts spelled out, so there is one handle per argument shape.
        val handle = marshalHandles.getOrPut(shape) {
            val variadic = shape.map { if (it == 'n') JAVA_INT else ADDRESS }
            downcall(
                "wl_proxy_marshal_flags",
                FunctionDescriptor.of(
                    ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, *variadic.toTypedArray(),
                ),
                Linker.Option.firstVariadicArg(FIRST_VARIADIC_ARG),
            )
        }
        val call = ArrayList<Any>(args.size + FIRST_VARIADIC_ARG)
        call += proxy; call += opcode; call += iface; call += version; call += NO_FLAGS
        args.forEach { call += if (it is WlArg.Num) it.value else (it as WlArg.Ptr).value }
        return handle.invokeWithArguments(call) as MemorySegment
    }

    /**
     * Builds a `wl_interface` and its message tables in native memory.
     *
     * libwayland exports tables for the 24 core interfaces only, so an extension supplies its own. Copy
     * the names, signatures and order from `wayland-scanner private-code`: order defines the opcodes, and
     * a wrong entry is a protocol error and a disconnect rather than a compile failure.
     */
    fun buildInterface(
        name: String,
        version: Int,
        requests: List<WlMessage>,
        events: List<WlMessage> = emptyList(),
    ): MemorySegment {
        val iface = arena.allocate(INTERFACE)
        iface.set(ADDRESS, NAME_OFFSET, cString(name))
        iface.set(JAVA_INT, VERSION_OFFSET, version)
        iface.set(JAVA_INT, METHOD_COUNT_OFFSET, requests.size)
        iface.set(ADDRESS, METHODS_OFFSET, messageTable(requests))
        iface.set(JAVA_INT, EVENT_COUNT_OFFSET, events.size)
        iface.set(ADDRESS, EVENTS_OFFSET, messageTable(events))
        return iface
    }

    private fun messageTable(messages: List<WlMessage>): MemorySegment {
        if (messages.isEmpty()) return MemorySegment.NULL
        val table = arena.allocate(MESSAGE, messages.size.toLong())
        messages.forEachIndexed { index, message ->
            val entry = table.asSlice(index * MESSAGE.byteSize(), MESSAGE.byteSize())
            entry.set(ADDRESS, MESSAGE_NAME_OFFSET, cString(message.name))
            entry.set(ADDRESS, MESSAGE_SIGNATURE_OFFSET, cString(message.signature))
            entry.set(ADDRESS, MESSAGE_TYPES_OFFSET, typeTable(message.types))
        }
        return table
    }

    private fun typeTable(types: List<MemorySegment>): MemorySegment {
        if (types.isEmpty()) return MemorySegment.NULL
        val table = arena.allocate(ADDRESS, types.size.toLong())
        types.forEachIndexed { index, type -> table.setAtIndex(ADDRESS, index.toLong(), type) }
        return table
    }

    private val marshalHandles = HashMap<String, MethodHandle>()

    private val TIMESPEC: StructLayout = MemoryLayout.structLayout(
        JAVA_LONG.withName("tv_sec"),
        JAVA_LONG.withName("tv_nsec"),
    )

    private val MESSAGE: StructLayout = MemoryLayout.structLayout(
        ADDRESS.withName("name"),
        ADDRESS.withName("signature"),
        ADDRESS.withName("types"),
    )

    private val INTERFACE: StructLayout = MemoryLayout.structLayout(
        ADDRESS.withName("name"),
        JAVA_INT.withName("version"),
        JAVA_INT.withName("method_count"),
        ADDRESS.withName("methods"),
        JAVA_INT.withName("event_count"),
        MemoryLayout.paddingLayout(4),
        ADDRESS.withName("events"),
    )

    private const val TV_SEC_OFFSET = 0L
    private const val TV_NSEC_OFFSET = 8L
    private const val MILLIS_PER_SECOND = 1_000L
    private const val NANOS_PER_MILLI = 1_000_000L

    private const val MESSAGE_NAME_OFFSET = 0L
    private const val MESSAGE_SIGNATURE_OFFSET = 8L
    private const val MESSAGE_TYPES_OFFSET = 16L

    private const val NAME_OFFSET = 0L
    private const val VERSION_OFFSET = 8L
    private const val METHOD_COUNT_OFFSET = 12L
    private const val METHODS_OFFSET = 16L
    private const val EVENT_COUNT_OFFSET = 24L
    private const val EVENTS_OFFSET = 32L

    private const val FIRST_VARIADIC_ARG = 5
    private const val NO_FLAGS = 0
}
