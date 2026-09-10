package com.fromwau.kortex.wayland

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.getOrElse
import com.fromwau.kern.result.map
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.StructLayout
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.foreign.ValueLayout.JAVA_SHORT

/**
 * The libc calls behind a shared-memory buffer and the event loop's wait.
 *
 * Passing the fd is the reason kortex binds libwayland rather than speaking the wire protocol
 * directly: libwayland does the SCM_RIGHTS dance, and the JDK's Unix socket channels cannot.
 */
internal object LibC {
    private val linker = Linker.nativeLinker()
    private val lookup = linker.defaultLookup()

    private fun downcall(name: String, descriptor: FunctionDescriptor, vararg options: Linker.Option) =
        linker.downcallHandle(
            lookup.find(name).orElseThrow { UnsatisfiedLinkError("libc exports no $name") },
            descriptor,
            *options,
        )

    private val memfdCreate = downcall("memfd_create", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT))
    private val ftruncate = downcall("ftruncate", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_LONG))
    private val mmap = downcall(
        "mmap",
        FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG),
    )
    private val munmap = downcall("munmap", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG))
    private val close = downcall("close", FunctionDescriptor.of(JAVA_INT, JAVA_INT))
    private val eventfd = downcall("eventfd", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT))
    private val read = downcall("read", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG))
    private val write = downcall("write", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG))
    private val poll = downcall(
        "poll",
        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT),
        Linker.Option.captureCallState(ERRNO),
    )

    private val callState: StructLayout = Linker.Option.captureStateLayout()
    private val errnoOffset: Long = callState.byteOffset(MemoryLayout.PathElement.groupElement(ERRNO))

    private val POLLFD: StructLayout = MemoryLayout.structLayout(
        JAVA_INT.withName("fd"),
        JAVA_SHORT.withName("events"),
        JAVA_SHORT.withName("revents"),
    )

    fun memfdCreate(name: String): Result<Int, KortexError> {
        // The kernel copies the name, so it has to outlive only the call.
        val fd = Arena.ofConfined().use { request -> memfdCreate.invoke(request.allocateFrom(name), 0) as Int }
        if (fd < 0) return Err(KortexError.ShmAllocationFailed(ShmStep.MemfdCreate))
        return Ok(fd)
    }

    fun ftruncate(fd: Int, length: Long): EmptyResult<KortexError> {
        if (ftruncate.invoke(fd, length) as Int != 0) return Err(KortexError.ShmAllocationFailed(ShmStep.Ftruncate))
        return Ok(Unit)
    }

    fun mmapShared(fd: Int, length: Long): Result<MemorySegment, KortexError> {
        val address = mmap.invoke(MemorySegment.NULL, length, PROT_READ or PROT_WRITE, MAP_SHARED, fd, 0L)
            as MemorySegment
        if (address.address() == MAP_FAILED) return Err(KortexError.ShmAllocationFailed(ShmStep.Mmap))
        return Ok(address.reinterpret(length))
    }

    /** Read-only private mapping, for data the compositor owns such as the keymap. */
    fun mmapPrivateRead(fd: Int, length: Long): MemorySegment {
        val address = mmap.invoke(MemorySegment.NULL, length, PROT_READ, MAP_PRIVATE, fd, 0L) as MemorySegment
        check(address.address() != MAP_FAILED) { "mmap of the keymap failed" }
        return address.reinterpret(length)
    }

    fun munmap(address: MemorySegment, length: Long) {
        munmap.invoke(address, length)
    }

    fun close(fd: Int) {
        close.invoke(fd)
    }

    /** A close-on-exec, non-blocking `eventfd` whose counter starts at 0. */
    fun eventfd(): Int {
        val fd = eventfd.invoke(0, EFD_CLOEXEC or EFD_NONBLOCK) as Int
        check(fd >= 0) { "eventfd failed" }
        return fd
    }

    fun read(fd: Int, buffer: MemorySegment) {
        read.invoke(fd, buffer, buffer.byteSize())
    }

    fun write(fd: Int, buffer: MemorySegment) {
        write.invoke(fd, buffer, buffer.byteSize())
    }

    /**
     * `poll(2)` until one of [fds] is ready or [deadlineNanos] passes; null waits indefinitely.
     *
     * @param events what to wait for on the fd at the same index of [fds], e.g. [POLLIN].
     * @return each fd's `revents`, all 0 once the deadline has passed.
     */
    fun poll(fds: IntArray, events: IntArray, deadlineNanos: Long?): IntArray {
        Arena.ofConfined().use { call ->
            val entries = call.allocate(POLLFD, fds.size.toLong())
            fds.forEachIndexed { index, fd ->
                entries.set(JAVA_INT, index * POLLFD.byteSize() + POLLFD_FD, fd)
                entries.set(JAVA_SHORT, index * POLLFD.byteSize() + POLLFD_EVENTS, events[index].toShort())
            }
            val state = call.allocate(callState)
            while (true) {
                val timeout = pollTimeoutMillis(deadlineNanos, System.nanoTime())
                val ready = poll.invoke(state, entries, fds.size.toLong(), timeout) as Int
                if (ready >= 0) break
                val errno = state.get(JAVA_INT, errnoOffset)
                // A signal cut the wait short, which says nothing about the fds; wait out what is left of it.
                check(errno == EINTR) { "poll failed with errno $errno" }
            }
            return IntArray(fds.size) { index ->
                entries.get(JAVA_SHORT, index * POLLFD.byteSize() + POLLFD_REVENTS).toInt()
            }
        }
    }

    /** [poll]'s timeout for [deadlineNanos], rounded up so the wait never ends before it; null never ends. */
    fun pollTimeoutMillis(deadlineNanos: Long?, nowNanos: Long): Int {
        if (deadlineNanos == null) return POLL_INDEFINITELY
        val remaining = (deadlineNanos - nowNanos).coerceAtLeast(0L)
        return Math.ceilDiv(remaining, NANOS_PER_MILLI).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    const val POLLIN = 0x001
    const val POLLOUT = 0x004

    /** `poll(2)`'s own timeout for a wait with no deadline. */
    const val POLL_INDEFINITELY = -1

    private const val PROT_READ = 1
    private const val PROT_WRITE = 2
    private const val MAP_SHARED = 1
    private const val MAP_PRIVATE = 2
    private const val MAP_FAILED = -1L

    private const val EFD_CLOEXEC = 0x80000
    private const val EFD_NONBLOCK = 0x800
    private const val ERRNO = "errno"
    private const val EINTR = 4
    private const val POLLFD_FD = 0L
    private const val POLLFD_EVENTS = 4L
    private const val POLLFD_REVENTS = 6L
    private const val NANOS_PER_MILLI = 1_000_000L
}

/**
 * A wl_shm buffer backed by memory you own and can draw into directly.
 *
 * The compositor may still be reading a committed buffer, and says so through [busy]. Drawing into a
 * busy buffer tears the frame being scanned out rather than reporting an error.
 */
public class ShmBuffer internal constructor(
    internal val buffer: MemorySegment,
    /** The mapped pixels, ARGB8888, [stride] bytes per row. */
    public val pixels: MemorySegment,
    public val width: Int,
    public val height: Int,
    public val stride: Int,
    private val fd: Int,
    private val size: Long,
) : AutoCloseable {
    private val arena: Arena = Arena.ofShared()

    @Volatile
    private var held = false

    @Volatile
    internal var releases: Int = 0
        private set

    /** True while the compositor still owns the last committed contents. */
    public val busy: Boolean get() = held

    internal fun markAttached() {
        held = true
    }

    internal fun released() {
        held = false
        releases++
    }

    /** Fills every pixel with one ARGB value. */
    public fun fill(argb: Int) {
        for (index in 0 until (size / Int.SIZE_BYTES)) {
            pixels.setAtIndex(JAVA_INT, index, argb)
        }
    }

    /** Installs the `wl_buffer.release` listener, whose stub lives exactly as long as this buffer. */
    internal fun installRelease() {
        val listener = arena.allocate(ADDRESS.byteSize())
        val release = BufferRelease(this)
        listener.setAtIndex(ADDRESS, 0L, LibWayland.upcall(arena, release, "onRelease", RELEASE_DESCRIPTOR))
        check(LibWayland.proxyAddListener(buffer, listener, MemorySegment.NULL) == 0) {
            "wl_proxy_add_listener rejected the buffer listener"
        }
    }

    override fun close() {
        LibWayland.marshal(buffer, WL_BUFFER_DESTROY)
        LibWayland.proxyDestroy(buffer)
        // After the destroy: a release still queued for this buffer would otherwise reach a freed stub.
        arena.close()
        LibC.munmap(pixels, size)
        LibC.close(fd)
    }

    internal companion object {
        const val WL_BUFFER_DESTROY = 0

        private val RELEASE_DESCRIPTOR = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS)
    }
}

/** The compositor's shared-memory buffer factory. */
public class Shm internal constructor(private val shm: MemorySegment) : AutoCloseable {

    private var closed = false

    public fun createBuffer(width: Int, height: Int): Result<ShmBuffer, KortexError> {
        check(!closed) { "createBuffer on a Shm whose wl_shm is already given back" }
        val stride = width * BYTES_PER_PIXEL
        val size = stride.toLong() * height
        val fd = LibC.memfdCreate("kortex-shm").getOrElse { return Err(it) }
        LibC.ftruncate(fd, size).getOrElse { failure ->
            LibC.close(fd)
            return Err(failure)
        }
        val pixels = LibC.mmapShared(fd, size).getOrElse { failure ->
            LibC.close(fd)
            return Err(failure)
        }

        val pool = LibWayland.marshal(
            shm, WL_SHM_CREATE_POOL, LibWayland.shmPoolInterface, LibWayland.proxyGetVersion(shm),
            listOf(WlArg.Ptr(MemorySegment.NULL), WlArg.Num(fd), WlArg.Num(size.toInt())),
        )
        val buffer = LibWayland.marshal(
            pool, WL_SHM_POOL_CREATE_BUFFER, LibWayland.bufferInterface, LibWayland.proxyGetVersion(pool),
            listOf(
                WlArg.Ptr(MemorySegment.NULL), WlArg.Num(0), WlArg.Num(width), WlArg.Num(height),
                WlArg.Num(stride), WlArg.Num(WL_SHM_FORMAT_ARGB8888),
            ),
        )
        // The pool is only a handle onto the fd; the buffer keeps the mapping alive on its own.
        LibWayland.marshal(pool, WL_SHM_POOL_DESTROY)
        LibWayland.proxyDestroy(pool)

        return Ok(ShmBuffer(buffer, pixels, width, height, stride, fd, size).also { it.installRelease() })
    }

    /** Gives the `wl_shm` back; buffers already taken from it stay valid, but [createBuffer] must not run again. */
    override fun close() {
        if (closed) return
        closed = true
        releaseShm(shm)
    }

    public companion object {
        public fun bind(display: WaylandDisplay): Result<Shm, KortexError> =
            display.require("wl_shm", LibWayland.shmInterface, WlVersion.SHM).map { Shm(it) }

        private const val WL_SHM_CREATE_POOL = 0
        private const val WL_SHM_POOL_CREATE_BUFFER = 0
        private const val WL_SHM_POOL_DESTROY = 1
        private const val BYTES_PER_PIXEL = 4

        /** Little-endian ARGB8888, which is what Skia's N32 premul writes. */
        private const val WL_SHM_FORMAT_ARGB8888 = 0
    }
}

/** Holds the `wl_buffer.release` upcall, which [LibWayland.upcall] cannot bind to a method on [ShmBuffer]. */
internal class BufferRelease(private val buffer: ShmBuffer) {
    fun onRelease(data: MemorySegment, proxy: MemorySegment) = buffer.released()
}
