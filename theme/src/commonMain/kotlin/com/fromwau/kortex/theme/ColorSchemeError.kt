package com.fromwau.kortex.theme

import com.fromwau.kern.result.IError
import com.fromwau.kortex.watch.WatchError

/** Why a theme file has no colour scheme to give you. */
public sealed interface ColorSchemeError : IError {
    /**
     * The file could not be read, or could not be watched; [cause] is what `:watch` reported.
     *
     * Not the end of anything on its own: a theme file that is missing now may be written later, which is
     * what a tool that regenerates it does.
     */
    public data class Unreadable(public val cause: WatchError) : ColorSchemeError

    /** The file was read and does not hold a theme; [detail] is the decoder's own wording. */
    public data class Unparseable(public val detail: String) : ColorSchemeError
}
