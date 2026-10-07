package com.fromwau.kortex.socket

import com.fromwau.kern.result.IError

/** Why a socket could not be reached, or stopped being usable. */
public sealed interface SocketError : IError {
    /** There is no socket file at [path], so nothing is listening there. */
    public data class NotFound(public val path: String) : SocketError

    /** The other end hung up, or this end was closed, before the read or write was done. */
    public data object Closed : SocketError

    /**
     * The socket failed for any other reason, such as a refused connection; [detail] is the system's word.
     *
     * Only a message, because the JDK reports all of these as a bare `IOException`.
     */
    public data class Failed(public val detail: String) : SocketError
}
