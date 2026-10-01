package com.fromwau.kortex.wayland

import androidx.compose.ui.unit.Dp
import com.fromwau.kern.result.IError
import com.fromwau.kortex.compose.ContentFailure

/** A `wl_seat` capability a surface may need but the compositor did not announce. */
public enum class SeatDevice { Pointer, Keyboard }

/** Which step of allocating a shared-memory buffer failed. */
public enum class ShmStep { MemfdCreate, Ftruncate, Mmap }

/**
 * A Wayland global kortex binds, under the name a compositor advertises it as.
 *
 * Only these: a compositor advertises many more, and kortex asks for none of them. [LayerShell] is the one a
 * compositor is most likely to be without, and kortex opens nothing at all without it.
 */
public enum class WaylandInterface(public val wireName: String) {
    Compositor("wl_compositor"),
    Shm("wl_shm"),
    Seat("wl_seat"),
    Output("wl_output"),
    DataDeviceManager("wl_data_device_manager"),
    LayerShell("zwlr_layer_shell_v1"),
    XdgWmBase("xdg_wm_base"),
    VirtualPointerManager("zwlr_virtual_pointer_manager_v1"),
}

/** Failures a caller of `:wayland`'s public entry points can distinguish and act on. */
public sealed interface KortexError : IError {
    /** Connecting found no compositor, or the initial roundtrip died with no protocol error to explain it. */
    public data object NoCompositorResponse : KortexError

    /** The compositor never advertised [global], so nothing kortex builds on it can be bound. */
    public data class MissingGlobal(public val global: WaylandInterface) : KortexError

    /** The seat exists but never announced [device] among its capabilities. */
    public data class MissingSeatDevice(public val device: SeatDevice) : KortexError

    /** The compositor never sent `configure` for a surface, a window or a popup within the wait budget. */
    public data object SurfaceNotConfigured : KortexError

    /** The compositor draws no decoration for this window, and kortex draws none of its own. */
    public data object ClientSideDecorationRequired : KortexError

    /** The connection died for a reason outside the protocol; [errno] is the C error number. */
    public data class ConnectionError(public val errno: Int) : KortexError

    /** The compositor rejected a request. [interfaceName] and [objectId] identify the offending object. */
    public data class ProtocolViolation(
        public val code: Int,
        public val interfaceName: String?,
        public val objectId: Int,
    ) : KortexError

    /**
     * [axis] was asked to span ([Length.WholeAxis]) while [anchor] does not pin both of its edges; the
     * protocol forbids it.
     */
    public data class UnspannableAxis(public val axis: Axis, public val anchor: Set<Edge>) : KortexError

    /** [axis] was given a size that rounds below 0, to [size] logical pixels. */
    public data class NegativeSize(public val axis: Axis, public val size: Int) : KortexError

    /**
     * [axis] was given a [Length.Of] that rounds to no pixels at all, so the surface would draw nothing.
     *
     * Distinct from [UnspannableAxis] on purpose: both arrive as a 0 on the wire, and they are opposite
     * mistakes. One asked for the whole axis without anchoring it, the other asked for nothing.
     */
    public data class EmptyLength(public val axis: Axis) : KortexError

    /** [anchor] does not pin [edge], and reserving space against an unanchored edge is a protocol error. */
    public data class InvalidExclusiveEdge(public val edge: Edge, public val anchor: Set<Edge>) : KortexError

    /**
     * [amount] reserves nothing once rounded to logical pixels, so it names a case of its own:
     * [ExclusiveZone.Yield] at 0, [ExclusiveZone.Overlap] below it.
     */
    public data class InvalidExclusiveZone(public val amount: Dp) : KortexError

    /** Allocating a shared-memory buffer failed at [step]. */
    public data class ShmAllocationFailed(public val step: ShmStep) : KortexError

    /**
     * Content on one of your surfaces threw and runs no more; [failure] says what it was doing.
     *
     * @property surface what names that surface: a [LayerSurface]'s `namespace`, a [Window]'s or a [Dialog]'s
     *   `title`, and for a [Popup] or a [ContextMenu] the name of the surface it opened over followed by
     *   `/popup`. Two popups open over the same surface are named alike, so read it as where a crash happened
     *   rather than as an identity.
     */
    public data class SurfaceCrashed(public val surface: String, public val failure: ContentFailure) : KortexError

    /**
     * Your application's own code threw: the content of `kortexApplication`, or UI placed directly in it rather than
     * in a surface. [cause] is what it threw, and the application ends with this error.
     */
    public data class ApplicationCrashed(public val cause: Throwable) : KortexError
}
