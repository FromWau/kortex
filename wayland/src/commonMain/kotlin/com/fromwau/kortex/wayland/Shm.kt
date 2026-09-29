package com.fromwau.kortex.wayland

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.IError
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
 * The libc calls behind a shared-memory buffer, the event loop's wait and the clipboard's pipes.
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
    private val eventfd = downcall(
        "eventfd",
        FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT),
        Linker.Option.captureCallState(ERRNO),
    )
    private val read = downcall("read", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG))
    private val write = downcall("write", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG))
    private val vsnprintf =
        downcall("vsnprintf", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS))
    private val poll = downcall(
        "poll",
        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT),
        Linker.Option.captureCallState(ERRNO),
    )
    private val pipe2 = downcall(
        "pipe2",
        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT),
        Linker.Option.captureCallState(ERRNO),
    )

    private val callState: StructLayout = Linker.Option.captureStateLayout()
    private val errnoOffset: Long = callState.byteOffset(MemoryLayout.PathElement.groupElement(ERRNO))

    private val POLLFD: StructLayout = MemoryLayout.structLayout(
        JAVA_INT.withName("fd"),
        JAVA_SHORT.withName("events"),
        JAVA_SHORT.withName("revents"),
    )
    private val pollFdOffset: Long = POLLFD.byteOffset(MemoryLayout.PathElement.groupElement("fd"))
    private val pollEventsOffset: Long = POLLFD.byteOffset(MemoryLayout.PathElement.groupElement("events"))
    private val pollReventsOffset: Long = POLLFD.byteOffset(MemoryLayout.PathElement.groupElement("revents"))

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

    /** Read-only private mapping of the keymap the compositor owns, at the length it announced. */
    fun mmapPrivateRead(fd: Int, length: Long): Result<MemorySegment, KortexError> {
        val address = mmap.invoke(MemorySegment.NULL, length, PROT_READ, MAP_PRIVATE, fd, 0L) as MemorySegment
        if (address.address() == MAP_FAILED) return Err(KortexError.ShmAllocationFailed(ShmStep.Mmap))
        return Ok(address.reinterpret(length))
    }

    fun munmap(address: MemorySegment, length: Long) {
        munmap.invoke(address, length)
    }

    fun close(fd: Int) {
        close.invoke(fd)
    }

    /** A close-on-exec, non-blocking `eventfd` whose counter starts at 0. */
    fun eventfd(): Result<Int, KortexError> {
        Arena.ofConfined().use { call ->
            val state = call.allocate(callState)
            val fd = eventfd.invoke(state, 0, EFD_CLOEXEC or EFD_NONBLOCK) as Int
            if (fd < 0) return Err(KortexError.ConnectionError(state.get(JAVA_INT, errnoOffset)))
            return Ok(fd)
        }
    }

    /** A close-on-exec pipe. */
    fun pipe(): Result<Pipe, Errno> {
        Arena.ofConfined().use { call ->
            val state = call.allocate(callState)
            val ends = call.allocate(JAVA_INT, PIPE_ENDS)
            if (pipe2.invoke(state, ends, O_CLOEXEC) as Int != 0) return Err(Errno(state.get(JAVA_INT, errnoOffset)))
            return Ok(
                Pipe(readFd = ends.getAtIndex(JAVA_INT, READ_END), writeFd = ends.getAtIndex(JAVA_INT, WRITE_END)),
            )
        }
    }

    /** @return how many bytes were read into [buffer]: 0 at end of file, negative on failure. */
    fun read(fd: Int, buffer: MemorySegment): Long = read.invoke(fd, buffer, buffer.byteSize()) as Long

    /** @return how many bytes of [buffer] were written, negative on failure. */
    fun write(fd: Int, buffer: MemorySegment): Long = write.invoke(fd, buffer, buffer.byteSize()) as Long

    /**
     * Formats [format] and [args] into [buffer], NUL terminated and truncated to fit.
     *
     * @return the length the whole text would have had, which is past [buffer] when it was truncated.
     */
    fun vsnprintf(buffer: MemorySegment, format: MemorySegment, args: MemorySegment): Int =
        vsnprintf.invoke(buffer, buffer.byteSize(), format, args) as Int

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
                entries.set(JAVA_INT, index * POLLFD.byteSize() + pollFdOffset, fd)
                entries.set(JAVA_SHORT, index * POLLFD.byteSize() + pollEventsOffset, events[index].toShort())
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
                entries.get(JAVA_SHORT, index * POLLFD.byteSize() + pollReventsOffset).toInt()
            }
        }
    }

    /** [poll]'s timeout for [deadlineNanos], rounded up so the wait never ends before it; null never ends. */
    fun pollTimeoutMillis(deadlineNanos: Long?, nowNanos: Long): Int {
        if (deadlineNanos == null) return POLL_INDEFINITELY
        val remaining = (deadlineNanos - nowNanos).coerceAtLeast(0L)
        return Math.ceilDiv(remaining, NANOS_PER_MILLI)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
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
    private const val O_CLOEXEC = 0x80000
    private const val PIPE_ENDS = 2L
    private const val READ_END = 0L
    private const val WRITE_END = 1L
    private const val ERRNO = "errno"
    private const val EINTR = 4
    private const val NANOS_PER_MILLI = 1_000_000L
}

/** Both ends of a pipe: what is written into [writeFd] comes out of [readFd]. */
internal data class Pipe(val readFd: Int, val writeFd: Int)

/** A libc call's failure: the C error number it left in `errno`. */
internal data class Errno(val number: Int) : IError

/**
 * A wl_shm buffer backed by memory you own and can draw into directly.
 *
 * The compositor may still be reading a committed buffer, and says so through [busy]. Drawing into a
 * busy buffer tears the frame being scanned out rather than reporting an error.
 */
internal class ShmBuffer(
    internal val buffer: MemorySegment,
    /** The mapped pixels, ARGB8888, [stride] bytes per row. */
    val pixels: MemorySegment,
    val width: Int,
    val height: Int,
    val stride: Int,
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
    val busy: Boolean get() = held

    internal fun markAttached() {
        held = true
    }

    internal fun released() {
        held = false
        releases++
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
internal class Shm(private val shm: MemorySegment) : AutoCloseable {

    private var closed = false

    fun createBuffer(width: Int, height: Int): Result<ShmBuffer, KortexError> {
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

    companion object {
        fun bind(display: WaylandDisplay): Result<Shm, KortexError> =
            display.require(WaylandInterface.Shm, LibWayland.shmInterface, WlVersion.SHM).map { Shm(it) }

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
