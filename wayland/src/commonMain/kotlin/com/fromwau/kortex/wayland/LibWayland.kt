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
import java.util.concurrent.ConcurrentHashMap
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
 * The newest version each global is bound at; `wl_registry_bind` clamps each to what the compositor offers.
 *
 * Every listener kortex installs implements its interface's full event set at these versions, because
 * libwayland indexes the listener struct positionally and calls straight through an empty slot.
 */
internal object WlVersion {
    /** wl_surface's `set_buffer_scale` arrives at v3 and `damage_buffer` at v4; both are sent here. */
    const val COMPOSITOR = 7
    const val SHM = 3
    const val LAYER_SHELL = 5
    const val VIRTUAL_POINTER = 2

    /**
     * xdg_wm_base and the xdg_surface and xdg_toplevel it hands out, which share its version.
     *
     * xdg_toplevel's `configure_bounds` arrives at v4 and `wm_capabilities` at v5; both are listened for.
     */
    const val XDG_SHELL = 7

    /**
     * zxdg_decoration_manager_v1 and the zxdg_toplevel_decoration_v1 it hands out, which share its version.
     *
     * The one event, `configure`, exists from v1; v2 only drops v1's rule that no buffer may be attached before
     * that event arrives.
     */
    const val XDG_DECORATION = 2

    /** wl_pointer and wl_keyboard inherit it, so their listeners grow with it. */
    const val SEAT = 11

    const val OUTPUT = 4

    /**
     * zxdg_output_manager_v1 and the zxdg_output_v1 it hands out, which share its version.
     *
     * At v3 the output's own `done`, `name` and `description` are deprecated in favour of `wl_output`'s, and
     * nothing is kept from them; all three still have listener slots.
     */
    const val XDG_OUTPUT = 3

    /** wl_data_device, its offers and every wl_data_source inherit it, so their listeners grow with it. */
    const val DATA_DEVICE_MANAGER = 4
}

/**
 * The one handler libwayland calls for every client-side log line and for whatever `wl_abort` prints.
 *
 * It drops the protocol error [WaylandDisplay.requireAlive] already hands the host typed, and writes every
 * other line where libwayland would have written it: the process's own stderr.
 */
internal object WaylandLog {
    /** `wl_log_func_t`: a printf format, and a `va_list` that on this ABI arrives as a pointer `vsnprintf` reads. */
    fun onLog(format: MemorySegment, args: MemorySegment) {
        if (format.reinterpret(Long.MAX_VALUE).getString(0) in DROPPED) return
        Arena.ofConfined().use { call ->
            val line = call.allocate(LINE_BYTES)
            val wanted = LibC.vsnprintf(line, format, args)
            if (wanted <= 0) return
            // The write blocks if nothing is draining fd 2, and is meant to: libwayland calls this from
            // wl_abort too, where the line is the last thing the process says and must land before abort().
            LibC.write(STDERR_FD, line.asSlice(0L, minOf(wanted.toLong(), LINE_BYTES - 1L)))
        }
    }

    // display_handle_error's two formats, verbatim from libwayland 1.26.0's wayland-client.c.
    private val DROPPED = setOf("%s#%u: error %d: %s\n", "[destroyed object]: error %d: %s\n")

    private const val STDERR_FD = 2
    private const val LINE_BYTES = 1024L
}

/**
 * The exported surface of libwayland-client.
 *
 * Its 169 request wrappers are `static inline`, so nothing exports them; every request here is the
 * `wl_proxy_marshal_flags` call they expand to.
 */
internal object LibWayland {
    private val linker: Linker = Linker.nativeLinker()

    // Global for what is static by nature: the library lookups, and the interface tables and their
    // strings, which libwayland dereferences through any proxy created against them. Listener stubs are
    // not static, and belong to an arena the object that installed them closes; see upcall below.
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
    private val displayGetFd = downcall("wl_display_get_fd", FunctionDescriptor.of(JAVA_INT, ADDRESS))
    private val displayPrepareRead = downcall("wl_display_prepare_read", FunctionDescriptor.of(JAVA_INT, ADDRESS))
    private val displayReadEvents = downcall("wl_display_read_events", FunctionDescriptor.of(JAVA_INT, ADDRESS))
    private val displayCancelRead = downcall("wl_display_cancel_read", FunctionDescriptor.ofVoid(ADDRESS))
    private val displayDispatchPending =
        downcall("wl_display_dispatch_pending", FunctionDescriptor.of(JAVA_INT, ADDRESS))
    private val displayGetError = downcall("wl_display_get_error", FunctionDescriptor.of(JAVA_INT, ADDRESS))
    private val displayGetProtocolError =
        downcall("wl_display_get_protocol_error", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS))
    private val proxyGetVersion = downcall("wl_proxy_get_version", FunctionDescriptor.of(JAVA_INT, ADDRESS))
    private val proxyGetInterface = downcall("wl_proxy_get_interface", FunctionDescriptor.of(ADDRESS, ADDRESS))
    private val proxyGetClass = downcall("wl_proxy_get_class", FunctionDescriptor.of(ADDRESS, ADDRESS))
    private val proxyDestroy = downcall("wl_proxy_destroy", FunctionDescriptor.ofVoid(ADDRESS))
    private val proxyAddListener =
        downcall("wl_proxy_add_listener", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS))
    private val logSetHandlerClient = downcall("wl_log_set_handler_client", FunctionDescriptor.ofVoid(ADDRESS))

    init {
        logSetHandlerClient.invoke(upcall(arena, WaylandLog, "onLog", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS)))
    }

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
    val dataDeviceManagerInterface: MemorySegment = symbol("wl_data_device_manager_interface")
    val dataDeviceInterface: MemorySegment = symbol("wl_data_device_interface")
    val dataSourceInterface: MemorySegment = symbol("wl_data_source_interface")

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
    fun displayGetFd(display: MemorySegment): Int = displayGetFd.invoke(display) as Int
    fun displayPrepareRead(display: MemorySegment): Int = displayPrepareRead.invoke(display) as Int
    fun displayReadEvents(display: MemorySegment): Int = displayReadEvents.invoke(display) as Int
    fun displayCancelRead(display: MemorySegment) { displayCancelRead.invoke(display) }
    fun displayDispatchPending(display: MemorySegment): Int = displayDispatchPending.invoke(display) as Int
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

    /** The interface name a proxy carries, for a failure that has to say which one it was. */
    private fun proxyClass(proxy: MemorySegment): String {
        val name = proxyGetClass.invoke(proxy) as MemorySegment
        return if (name == MemorySegment.NULL) "an unnamed proxy" else name.reinterpret(Long.MAX_VALUE).getString(0)
    }

    /** The `wl_interface` a proxy was made with, which carries its request table. */
    private fun proxyGetInterface(proxy: MemorySegment): MemorySegment =
        proxyGetInterface.invoke(proxy) as MemorySegment
    fun proxyDestroy(proxy: MemorySegment) { proxyDestroy.invoke(proxy) }

    fun proxyAddListener(proxy: MemorySegment, implementation: MemorySegment, data: MemorySegment): Int =
        proxyAddListener.invoke(proxy, implementation, data) as Int

    /**
     * Binds [method] on [target] as an upcall stub for a listener slot, living as long as [arena].
     *
     * Named rather than defaulted, because closing [arena] frees the stub's code: whoever installs a
     * listener has to have decided which teardown destroys the proxy that can still call it. Pass a
     * shared arena, not a confined one, which would refuse every thread but the one that installed.
     *
     * The Java signature is derived from [descriptor], because a mismatch between the two crashes inside
     * native code rather than failing to compile. [method] must be public: Kotlin mangles an `internal`
     * name out of the lookup's reach.
     */
    fun upcall(arena: Arena, target: Any, method: String, descriptor: FunctionDescriptor): MemorySegment {
        val handle = MethodHandles.publicLookup()
            .findVirtual(target.javaClass, method, descriptor.toMethodType())
            .bindTo(target)
        return linker.upcallStub(handle, descriptor, arena)
    }

    /** The `name` field of a `wl_interface`, which `wl_registry_bind` passes back to the compositor. */
    // A symbol from SymbolLookup is a zero-length segment, so its extent has to be restated before
    // any field can be read out of it.
    fun interfaceName(iface: MemorySegment): MemorySegment =
        iface.reinterpret(INTERFACE.byteSize()).get(ADDRESS, NAME_OFFSET)

    /**
     * The version [opcode] first appeared in, read off its own entry in the interface's request table.
     *
     * libwayland writes it as the leading digits of the signature and reads it back with `atoi`, taking a
     * missing one as 1 (`wl_message_get_since`, src/connection.c). Every table kortex builds follows the
     * same encoding, because `wayland-scanner` does.
     */
    private fun requestSince(proxy: MemorySegment, opcode: Int): Int {
        val iface = proxyGetInterface(proxy)
        if (iface == MemorySegment.NULL) return FIRST_VERSION
        // Cached per interface: this sits on the path every commit and every damage takes.
        val sinceByOpcode = requestSince.computeIfAbsent(iface.address()) { readRequestSince(iface) }
        return sinceByOpcode.getOrElse(opcode) { FIRST_VERSION }
    }

    private fun readRequestSince(iface: MemorySegment): IntArray {
        val header = iface.reinterpret(INTERFACE.byteSize())
        val count = header.get(JAVA_INT, METHOD_COUNT_OFFSET)
        if (count <= 0) return IntArray(0)
        val methods = header.get(ADDRESS, METHODS_OFFSET).reinterpret(MESSAGE.byteSize() * count)
        return IntArray(count) { opcode ->
            val entry = methods.asSlice(opcode * MESSAGE.byteSize(), MESSAGE.byteSize())
            val signature = entry.get(ADDRESS, MESSAGE_SIGNATURE_OFFSET).reinterpret(Long.MAX_VALUE).getString(0)
            signature.takeWhile(Char::isDigit).toIntOrNull() ?: FIRST_VERSION
        }
    }

    /** The `version` field of a `wl_interface`; nothing on the client side checks a bind or a marshal against it. */
    fun interfaceVersion(iface: MemorySegment): Int =
        iface.reinterpret(INTERFACE.byteSize()).get(JAVA_INT, VERSION_OFFSET)

    /**
     * `wl_proxy_marshal_flags(proxy, opcode, interface, version, flags, ...)`.
     *
     * Pass [iface] as NULL for a request that creates no object.
     *
     * The flags are always empty, so even a request the protocol marks a destructor only sends it: the
     * proxy stays alive until [proxyDestroy] takes it.
     */
    fun marshal(
        proxy: MemorySegment,
        opcode: Int,
        iface: MemorySegment = MemorySegment.NULL,
        version: Int = 0,
        args: List<WlArg> = emptyList(),
    ): MemorySegment {
        // A request the negotiated version does not carry is answered by the compositor with
        // wl_display.error(invalid_method), which destroys the client, so it is not sent. Only the server
        // checks this (wayland-server.c, where it reads wl_message_get_since); libwayland's client side
        // does not, which is why every versioned request used to name its own since by hand and two did not.
        // Above zero, as the server's own check reads it: a proxy that carries no version at all, the
        // display itself among them, answers 0, and 0 means unversioned rather than older than everything.
        val senderVersion = proxyGetVersion(proxy)
        if (senderVersion > UNVERSIONED && senderVersion < requestSince(proxy, opcode)) {
            // One that makes an object cannot simply be skipped: the caller is owed a proxy, and a null one
            // would be dereferenced somewhere else entirely. Asking a compositor to make something it never
            // offered is the caller's own mistake, so it is raised where it was made.
            check(iface == MemorySegment.NULL) {
                "opcode $opcode of ${proxyClass(proxy)} makes an object and needs a newer version than the " +
                    "$senderVersion this compositor gave"
            }
            return MemorySegment.NULL
        }
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
    ): MemorySegment = buildInterface(name, version, { requests }, events)

    /**
     * Builds a `wl_interface` one of whose own requests takes another object of that same interface.
     *
     * Such a table has no value to point at until the interface exists, so [requests] is handed the interface
     * being built and the tables are filled once it returns. Otherwise as [buildInterface].
     */
    fun buildInterface(
        name: String,
        version: Int,
        requests: (self: MemorySegment) -> List<WlMessage>,
        events: List<WlMessage> = emptyList(),
    ): MemorySegment {
        val iface = arena.allocate(INTERFACE)
        val messages = requests(iface)
        iface.set(ADDRESS, NAME_OFFSET, arena.allocateFrom(name))
        iface.set(JAVA_INT, VERSION_OFFSET, version)
        iface.set(JAVA_INT, METHOD_COUNT_OFFSET, messages.size)
        iface.set(ADDRESS, METHODS_OFFSET, messageTable(messages))
        iface.set(JAVA_INT, EVENT_COUNT_OFFSET, events.size)
        iface.set(ADDRESS, EVENTS_OFFSET, messageTable(events))
        return iface
    }

    private fun messageTable(messages: List<WlMessage>): MemorySegment {
        if (messages.isEmpty()) return MemorySegment.NULL
        val table = arena.allocate(MESSAGE, messages.size.toLong())
        messages.forEachIndexed { index, message ->
            val entry = table.asSlice(index * MESSAGE.byteSize(), MESSAGE.byteSize())
            entry.set(ADDRESS, MESSAGE_NAME_OFFSET, arena.allocateFrom(message.name))
            entry.set(ADDRESS, MESSAGE_SIGNATURE_OFFSET, arena.allocateFrom(message.signature))
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

    // Each interface's request versions, by the address of the wl_interface they were read from. Both the
    // tables kortex builds and libwayland's own live for the process, so an address identifies one for good.
    private val requestSince = ConcurrentHashMap<Long, IntArray>()

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
    // What a signature with no leading digits means, as libwayland's own wl_message_get_since takes it.
    // What wl_proxy_get_version answers for a proxy with no version of its own, such as the display.
    private const val UNVERSIONED = 0

    private const val FIRST_VERSION = 1

    private const val METHOD_COUNT_OFFSET = 12L
    private const val METHODS_OFFSET = 16L
    private const val EVENT_COUNT_OFFSET = 24L
    private const val EVENTS_OFFSET = 32L

    private const val FIRST_VARIADIC_ARG = 5
    private const val NO_FLAGS = 0
}
