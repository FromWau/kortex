package com.fromwau.kortex.auth

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.IError
import com.fromwau.kortex.socket.SocketError

/** Why an authentication ended without authenticating anyone. */
public sealed interface AuthError : IError {
    /** By [AuthConversation.cancel], or by whoever asked for the authentication withdrawing it. */
    public data object Cancelled : AuthError

    /** The backend could not be reached, or hung up before saying how it went. */
    public data class Unreachable(public val cause: SocketError) : AuthError

    /** The backend sent [received], which its protocol does not have. */
    public data class Broken(public val received: String) : AuthError
}

/** Why an [AuthConversation] could not take a step it was asked to. */
public sealed interface ConversationError : IError {
    /** An answer came while no prompt was open. */
    public data object NotAsking : ConversationError

    /** A retry came while the last attempt was not [AuthState.Rejected]. */
    public data object NotRejected : ConversationError

    /** The backend has no way to send this answer, such as a line break to one that reads answers by line. */
    public data object Unsendable : ConversationError

    /** It is over, with [outcome]. */
    public data class AlreadyEnded(public val outcome: EmptyResult<AuthError>) : ConversationError
}
