package com.fromwau.kortex.wayland

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.flatMap
import java.lang.foreign.MemorySegment

private const val CONFIGURE_TIMEOUT_MILLIS = 4_000L
private const val DISPATCH_SLICE_MILLIS = 50L
private const val NANOS_PER_MILLI = 1_000_000L

/**
 * Dispatches until [configured] answers true, which is what every role's [SurfaceRole.waitForConfigure] waits
 * on, and gives up when [closed] answers first or when neither does within the budget.
 *
 * @return `Ok` once configured; else the connection's error when it died before a configure came, else
 *   [KortexError.SurfaceNotConfigured], which is also what a role the compositor gave up on ends with.
 */
internal fun awaitConfigure(
    display: WaylandDisplay,
    configured: () -> Boolean,
    // The role's own closed flag, which the compositor sets by closing the window or dismissing the surface.
    closed: () -> Boolean,
): EmptyResult<KortexError> {
    display.roundtrip()
    val deadline = System.nanoTime() + CONFIGURE_TIMEOUT_MILLIS * NANOS_PER_MILLI
    // Dispatched in slices rather than blocking: this runs on the one thread that pumps the connection, and
    // wl_display_dispatch returns only once an event arrives, so a compositor that answers nothing at all
    // would hold the whole application here, past any exitApplication.
    while (!configured() && !closed() && System.nanoTime() < deadline) {
        // A negative return means the connection is already gone: dispatching again would return immediately
        // without sleeping, so this loop would burn the rest of the budget spinning.
        if (display.dispatch(DISPATCH_SLICE_MILLIS) < 0) break
    }
    if (configured()) return Ok(Unit)
    // A dead connection surfaces first as an unconfigured surface; prefer the real cause.
    return display.requireAlive().flatMap { Err(KortexError.SurfaceNotConfigured) }
}

/** How a surface parents a popup opened from its content: the two ways the protocol offers. */
internal sealed interface PopupParent {
    /** An `xdg_surface`, which `xdg_surface.get_popup` takes as the popup is created. */
    data class Xdg(val xdgSurface: MemorySegment) : PopupParent

    /** A layer surface, whose [surface] adopts a popup created without a parent through its own `adoptPopup`. */
    data class Layer(val surface: LayerShellSurface) : PopupParent
}

/** What the surface engine needs of whatever protocol object a surface is built on. */
internal interface SurfaceRole : AutoCloseable {
    /** The `wl_surface` the engine draws into and paces its frames off. */
    val surface: MemorySegment

    /** What a popup shown from this surface's content is parented to. */
    val popupParent: PopupParent

    /** The logical (surface-local) size the compositor assigned, available once [waitForConfigure] returns `Ok`. */
    val logicalWidth: Int
    val logicalHeight: Int

    /**
     * The buffer scale the compositor wants for this surface, from `wl_surface.preferred_buffer_scale`.
     *
     * It reflects the output this surface is actually on, so two surfaces on a mixed-DPI setup report
     * different scales. Reads 1 until the compositor says otherwise, as the protocol prescribes.
     */
    val preferredBufferScale: Int

    /** True once this surface has to be torn down, whether the compositor closed it or [markClosed] did. */
    val closed: Boolean

    /** Whether this role takes the keyboard when a scene is attached to it. */
    val wantsKeyboard: Boolean

    /**
     * Blocks until the compositor has configured this surface, acknowledging the serial it sent.
     *
     * @return `Ok` once configured, else why it never was.
     */
    fun waitForConfigure(): EmptyResult<KortexError>

    /**
     * True once since the last configure, and only once; the caller compares the sizes before it resizes.
     *
     * A role whose size only a configure can change may report a configure that changed nothing as no resize. A
     * role whose size a call can also change must report every configure, or the two sizes never reconcile.
     */
    fun consumeResize(): Boolean

    /** Double-buffered like every pending surface state: takes effect only at the next [commit]. */
    fun setBufferScale(scale: Int)

    /** Attaches [buffer] and marks the whole surface damaged. Must follow an acknowledged configure. */
    fun attach(buffer: ShmBuffer)

    /** Makes everything pending on the surface take effect, and flushes the connection. */
    fun commit()

    /** Sets the flag the compositor closing this surface sets, as though it had. */
    fun markClosed()
}
