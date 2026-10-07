package com.fromwau.kortex.auth

import com.fromwau.kern.result.EmptyResult

/**
 * Where an [AuthConversation] stands.
 *
 * [notes] are what the backend has said during this attempt, oldest first, kept until [AuthConversation.retry]
 * starts another, so a prompt that comes after "wrong password" is drawn with it.
 */
public sealed interface AuthState {
    /** It is the backend's turn: connecting, checking an answer, or about to ask. */
    public data class Waiting(public val notes: List<Note>) : AuthState

    /** [prompt] waits for [AuthConversation.answer]. */
    public data class Asking(
        public val prompt: Prompt,
        public val notes: List<Note>,
    ) : AuthState

    /** The answers did not authenticate. [AuthConversation.retry] asks again; nothing happens until then. */
    public data class Rejected(public val notes: List<Note>) : AuthState

    /** Over for good: [outcome] is Ok where the user was authenticated, and why not otherwise. */
    public data class Ended(public val outcome: EmptyResult<AuthError>) : AuthState
}

/** A question the backend wants answered. */
public sealed interface Prompt {
    /** The question as the backend words it, such as "Password: ". */
    public val text: String

    /** An answer drawn hidden, such as a password. */
    public data class Secret(override val text: String) : Prompt

    /** An answer shown as it is typed, such as a user name. */
    public data class Visible(override val text: String) : Prompt
}

/** Something the backend says that needs no answer. */
public sealed interface Note {
    public val text: String

    public data class Info(override val text: String) : Note

    /** Said about something that went wrong, such as a password that did not match. */
    public data class Problem(override val text: String) : Note
}
