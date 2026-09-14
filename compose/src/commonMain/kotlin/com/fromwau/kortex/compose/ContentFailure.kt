package com.fromwau.kortex.compose

import com.fromwau.kern.result.IError

/**
 * Content threw, and the scene runs none of it from then on. The case says what the scene was doing at the
 * time; [cause] is what content threw.
 */
public sealed interface ContentFailure : IError {
    public val cause: Throwable

    /**
     * While the scene ran the composition itself: composing, recomposing, laying out or drawing, or in one of
     * content's coroutines, such as an effect.
     */
    public data class Composition(override val cause: Throwable) : ContentFailure

    /** While the scene delivered a key. */
    public data class KeyInput(override val cause: Throwable) : ContentFailure

    /** While the scene delivered a pointer event. */
    public data class PointerInput(override val cause: Throwable) : ContentFailure
}
