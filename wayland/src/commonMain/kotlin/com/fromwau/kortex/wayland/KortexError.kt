package com.fromwau.kortex.wayland

import androidx.compose.ui.unit.Dp
import com.fromwau.kern.result.IError
import com.fromwau.kortex.compose.ContentFailure

/** A `wl_seat` capability a surface may need but the compositor did not announce. */
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

    /** [axis] was left 0 ("you choose") while [anchor] does not pin both of its edges; the protocol forbids it. */
    public data class UnspannableAxis(public val axis: Axis, public val anchor: Set<Edge>) : KortexError

    /** [anchor] does not pin [edge], and reserving space against an unanchored edge is a protocol error. */
    public data class InvalidExclusiveEdge(public val edge: Edge, public val anchor: Set<Edge>) : KortexError

    /**
     * [amount] reserves nothing once rounded to logical pixels, so it names a case of its own:
     * [ExclusiveZone.Yield] at 0, [ExclusiveZone.Overlap] below it.
     */
    public data class InvalidExclusiveZone(public val amount: Dp) : KortexError

    /** Allocating a shared-memory buffer failed at [step]. */
    public data class ShmAllocationFailed(public val step: ShmStep) : KortexError

    /** Content on the surface named [namespace] threw and runs no more; [failure] says what it was doing. */
    public data class SurfaceCrashed(public val namespace: String, public val failure: ContentFailure) : KortexError

    /**
     * Your application's own code threw: the content of `kortexApplication`, UI placed directly in it rather than in a
     * surface, or an `onClose` you passed. [cause] is what it threw. The application ends with this error, and no
     * other `onClose` is called.
     */
    public data class ApplicationCrashed(public val cause: Throwable) : KortexError
}
