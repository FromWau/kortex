package com.fromwau.kortex.wayland

import com.fromwau.kern.result.IError

/** A `wl_seat` capability [KortexBar] may need but the compositor did not announce. */
public enum class SeatDevice { Pointer, Keyboard }

/** Which step of allocating a shared-memory buffer failed. */
public enum class ShmStep { MemfdCreate, Ftruncate, Mmap }

/** Failures a caller of `:wayland`'s public entry points can distinguish and act on. */
public sealed interface KortexError : IError {
    /** Connecting found no compositor, or the initial roundtrip died with no protocol error to explain it. */
    public data object NoCompositorResponse : KortexError

    /** The compositor never advertised [interfaceName] as a global. */
    public data class MissingGlobal(public val interfaceName: String) : KortexError

    /** The seat exists but never announced [device] among its capabilities. */
    public data class MissingSeatDevice(public val device: SeatDevice) : KortexError

    /** The compositor never sent `configure` for a layer surface within the wait budget. */
    public data object SurfaceNotConfigured : KortexError

    /** The connection died for a reason outside the protocol; [errno] is the C error number. */
    public data class ConnectionError(public val errno: Int) : KortexError

    /** The compositor rejected a request. [interfaceName] and [objectId] identify the offending object. */
    public data class ProtocolViolation(
        public val code: Int,
        public val interfaceName: String?,
        public val objectId: Int,
    ) : KortexError

    /** Allocating a shared-memory buffer failed at [step]. */
    public data class ShmAllocationFailed(public val step: ShmStep) : KortexError
}
