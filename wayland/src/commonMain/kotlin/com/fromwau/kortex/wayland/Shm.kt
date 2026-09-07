package com.fromwau.kortex.wayland

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.getOrElse
import com.fromwau.kern.result.map
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG

/**
 * The libc calls behind a shared-memory buffer.
 *
 * Passing the fd is the reason kortex binds libwayland rather than speaking the wire protocol
 * directly: libwayland does the SCM_RIGHTS dance, and the JDK's Unix socket channels cannot.
 */
internal object LibC {
    private val linker = Linker.nativeLinker()
    private val lookup = linker.defaultLookup()

    private fun downcall(name: String, descriptor: FunctionDescriptor) =
        linker.downcallHandle(
            lookup.find(name).orElseThrow { UnsatisfiedLinkError("libc exports no $name") },
            descriptor,
        )

    private val memfdCreate = downcall("memfd_create", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT))
    private val ftruncate = downcall("ftruncate", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_LONG))
    private val mmap = downcall(
        "mmap",
        FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG),
    )
    private val munmap = downcall("munmap", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG))
    private val close = downcall("close", FunctionDescriptor.of(JAVA_INT, JAVA_INT))

    fun memfdCreate(name: String): Result<Int, KortexError> {
        val fd = memfdCreate.invoke(LibWayland.cString(name), 0) as Int
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

    private const val PROT_READ = 1
    private const val PROT_WRITE = 2
    private const val MAP_SHARED = 1
    private const val MAP_PRIVATE = 2
    private const val MAP_FAILED = -1L
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

    override fun close() {
        LibWayland.marshal(buffer, WL_BUFFER_DESTROY)
        LibWayland.proxyDestroy(buffer)
        LibC.munmap(pixels, size)
        LibC.close(fd)
    }

    internal companion object {
        const val WL_BUFFER_DESTROY = 0
    }
}

/** The compositor's shared-memory buffer factory. */
public class Shm internal constructor(private val shm: MemorySegment) {

    public fun createBuffer(width: Int, height: Int): Result<ShmBuffer, KortexError> {
        val stride = width * BYTES_PER_PIXEL
        val size = stride.toLong() * height
        val fd = LibC.memfdCreate("kortex-shm").getOrElse { return Err(it) }
        LibC.ftruncate(fd, size).getOrElse { return Err(it) }
        val pixels = LibC.mmapShared(fd, size).getOrElse { return Err(it) }

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

        val shmBuffer = ShmBuffer(buffer, pixels, width, height, stride, fd, size)
        val listener = LibWayland.arena.allocate(ADDRESS.byteSize())
        val release = BufferRelease(shmBuffer)
        listener.setAtIndex(ADDRESS, 0L, LibWayland.upcall(release, "onRelease", RELEASE_DESCRIPTOR))
        check(LibWayland.proxyAddListener(buffer, listener, MemorySegment.NULL) == 0) {
            "wl_proxy_add_listener rejected the buffer listener"
        }
        return Ok(shmBuffer)
    }

    public companion object {
        public fun bind(display: WaylandDisplay): Result<Shm, KortexError> =
            display.require("wl_shm", LibWayland.shmInterface, WlVersion.SHM).map { Shm(it) }

        private val RELEASE_DESCRIPTOR = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS)

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
