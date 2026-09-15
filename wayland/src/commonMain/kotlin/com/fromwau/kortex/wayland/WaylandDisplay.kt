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
import java.util.concurrent.CopyOnWriteArrayList

/** A global the compositor advertises on the registry. */
internal data class WaylandGlobal(
    val name: Int,
    val interfaceName: String,
    val version: Int,
)

/** A connection to a Wayland compositor. */
internal class WaylandDisplay private constructor(
    internal val display: MemorySegment,
    private val registry: MemorySegment,
    // The eventfd wake() counts up from any thread; only awaitWork, on the loop thread, reads it back down.
    private val wakeFd: Int,
) : AutoCloseable {
    private val mutableGlobals = CopyOnWriteArrayList<WaylandGlobal>()

    // Holds the registry listener's struct and stubs and the wake buffers, which close() frees with the connection.
    private val arena: Arena = Arena.ofShared()

    private val waylandFd: Int = LibWayland.displayGetFd(display)
    private val wakeIncrement: MemorySegment = arena.allocate(JAVA_LONG).also { it.set(JAVA_LONG, 0L, 1L) }
    private val wakeDrain: MemorySegment = arena.allocate(JAVA_LONG)
    private val wakeLock = Any()

    // Guarded by wakeLock.
    private var wakeClosed = false

    // Events a roundtrip or dispatch runs mid-pass can change what that pass already checked, so the next wait
    // must not sleep.
    private var dispatchedOutsideWait = false

    /** The registry listener's struct; not private because a test asserts that [close] frees it. */
    internal lateinit var registryListener: MemorySegment
        private set

    /** How many times [awaitWork] has been entered; exposed so a test can assert an idle loop sleeps. */
    @Volatile
    internal var waits: Long = 0L
        private set

    /** Live registry snapshot; safe to read from another thread while upcalls append or remove during dispatch. */
    val globals: List<WaylandGlobal> get() = mutableGlobals

    /** Fires on whichever thread is dispatching when the compositor advertises a new global. */
    var onGlobalAdded: ((WaylandGlobal) -> Unit)? = null

    /** Fires on whichever thread is dispatching when a previously advertised global goes away. */
    var onGlobalRemoved: ((WaylandGlobal) -> Unit)? = null

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

    fun roundtrip(): Int = LibWayland.displayRoundtrip(display).also { dispatchedOutsideWait = true }

    fun dispatch(): Int = LibWayland.displayDispatch(display).also { dispatchedOutsideWait = true }

    /** Dispatches for at most [timeoutMillis]; exposed so a test can watch events arrive as they are sent. */
    fun dispatch(timeoutMillis: Long): Int =
        LibWayland.displayDispatchTimeout(display, timeoutMillis).also { dispatchedOutsideWait = true }

    fun flush(): Int = LibWayland.displayFlush(display)

    /**
     * Makes the loop's current or next [awaitWork] return. Any thread may call it, even after [close].
     *
     * Enqueue the work first and wake second: the other way round, the loop can drain the wake, find no
     * work yet, and sleep through it.
     */
    internal fun wake() {
        // Under the lock: a wake racing close() would otherwise write into whatever file next reuses the fd.
        synchronized(wakeLock) {
            if (!wakeClosed) LibC.write(wakeFd, wakeIncrement)
        }
    }

    /**
     * Sleeps until a Wayland event arrives, [wake] is called, or [deadlineNanos] passes, then dispatches
     * whatever arrived. Only the loop thread may call it.
     *
     * @param deadlineNanos in [System.nanoTime] units; null sleeps until one of the other two happens.
     * @return false once the connection has failed.
     */
    internal fun awaitWork(deadlineNanos: Long?): Boolean {
        waits++
        val sleepUntil = if (dispatchedOutsideWait) System.nanoTime() else deadlineNanos
        dispatchedOutsideWait = false
        // libwayland reads only into an empty queue, and what is queued already may move the caller's deadline.
        if (LibWayland.displayPrepareRead(display) != 0) return LibWayland.displayDispatchPending(display) >= 0
        // A full socket holds requests back, and the answer this sleep waits for may need them sent first.
        val unsent = LibWayland.displayFlush(display) < 0
        val revents = try {
            LibC.poll(
                fds = intArrayOf(waylandFd, wakeFd),
                events = intArrayOf(if (unsent) LibC.POLLIN or LibC.POLLOUT else LibC.POLLIN, LibC.POLLIN),
                deadlineNanos = sleepUntil,
            )
        } catch (failure: Throwable) {
            // A prepared read left neither read nor cancelled blocks every later dispatch on this connection.
            LibWayland.displayCancelRead(display)
            throw failure
        }
        if (revents[WAYLAND_FD] == 0) {
            LibWayland.displayCancelRead(display)
        } else if (LibWayland.displayReadEvents(display) < 0) {
            return false
        }
        if (revents[WAKE_FD] != 0) LibC.read(wakeFd, wakeDrain)
        return LibWayland.displayDispatchPending(display) >= 0
    }

    /** Checks that the connection still works: `Ok` while it does, else why it stopped. */
    fun requireAlive(): EmptyResult<KortexError> {
        val errno = LibWayland.displayGetError(display)
        if (errno == 0) return Ok(Unit)
        // Only EPROTO means the compositor rejected a request; any other errno is the socket dying,
        // and asking for protocol details would return meaningless zeros.
        if (errno != EPROTO) return Err(KortexError.ConnectionError(errno))

        val raw = LibWayland.displayGetProtocolError(display)
        val name = if (raw.iface.equals(MemorySegment.NULL)) {
            null
        } else {
            // The name pointer comes back zero-length; it must be reinterpreted before it can be read as a string.
            LibWayland.interfaceName(raw.iface).reinterpret(Long.MAX_VALUE).getString(0)
        }
        return Err(KortexError.ProtocolViolation(raw.code, name, raw.id))
    }

    fun global(interfaceName: String): WaylandGlobal? = globals.firstOrNull { it.interfaceName == interfaceName }

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
            ?: return requireAlive().flatMap { Err(KortexError.MissingGlobal(interfaceName)) }
        return Ok(bind(global, iface, maxVersion))
    }

    override fun close() {
        synchronized(wakeLock) {
            wakeClosed = true
            LibC.close(wakeFd)
        }
        // wl_registry has no destructor request, so its proxy is only ever freed on this side.
        LibWayland.proxyDestroy(registry)
        LibWayland.displayDisconnect(display)
        // After the destroy, never before: an event still queued for the registry would reach a freed stub.
        arena.close()
    }

    private fun installRegistryListener() {
        val sink = RegistryListener(this)
        val listener = arena.allocate(ADDRESS.byteSize() * 2)
        listener.setAtIndex(ADDRESS, 0L, LibWayland.upcall(arena, sink, "onGlobal", GLOBAL_DESCRIPTOR))
        listener.setAtIndex(
            ADDRESS, 1L,
            LibWayland.upcall(arena, sink, "onGlobalRemove", GLOBAL_REMOVE_DESCRIPTOR),
        )
        check(LibWayland.proxyAddListener(registry, listener, MemorySegment.NULL) == 0) {
            "wl_proxy_add_listener rejected the registry listener"
        }
        registryListener = listener
    }

    companion object {
        /** Connects to [name], or to `$WAYLAND_DISPLAY` when null. */
        fun connect(name: String? = null): Result<WaylandDisplay, KortexError> {
            // Before connecting, so failing here leaves nothing built to give back.
            val wakeFd = LibC.eventfd().getOrElse { return Err(it) }
            // libwayland copies the name into the socket address, so it has to outlive only the call.
            val display = Arena.ofConfined().use { request ->
                LibWayland.displayConnect(name?.let { request.allocateFrom(it) } ?: MemorySegment.NULL)
            }
            if (display.equals(MemorySegment.NULL)) {
                LibC.close(wakeFd)
                return Err(KortexError.NoCompositorResponse)
            }

            val registry = LibWayland.marshal(
                proxy = display,
                opcode = WL_DISPLAY_GET_REGISTRY,
                iface = LibWayland.registryInterface,
                version = LibWayland.proxyGetVersion(display),
                args = listOf(WlArg.Ptr(MemorySegment.NULL)),
            )

            val waylandDisplay = WaylandDisplay(display, registry, wakeFd)
            waylandDisplay.installRegistryListener()
            if (LibWayland.displayRoundtrip(display) < 0) {
                val failure = waylandDisplay.requireAlive().flatMap { Err(KortexError.NoCompositorResponse) }
                waylandDisplay.close()
                return failure
            }

            return Ok(waylandDisplay)
        }

        private const val WL_DISPLAY_GET_REGISTRY = 1
        private const val WL_REGISTRY_BIND = 0

        // Where awaitWork puts each of its two fds in what it polls.
        private const val WAYLAND_FD = 0
        private const val WAKE_FD = 1

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
