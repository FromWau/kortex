package com.fromwau.kortex.wayland

import java.lang.foreign.MemorySegment

/** The `wl_surface` a test's input is on when the test hands that input straight to a listener. */
internal val LISTENER_SURFACE: MemorySegment = MemorySegment.ofAddress(0x1000)

/** A surface of this client's other than [LISTENER_SURFACE], which input on it must not reach. */
internal val OTHER_SURFACE: MemorySegment = MemorySegment.ofAddress(0x2000)

/** The enter on [surface] that a compositor sends a keyboard ahead of its first key. */
internal fun KeyboardInput.enter(surface: MemorySegment = LISTENER_SURFACE, serial: Int = 0) =
    onEnter(MemorySegment.NULL, MemorySegment.NULL, serial, surface, MemorySegment.NULL)
