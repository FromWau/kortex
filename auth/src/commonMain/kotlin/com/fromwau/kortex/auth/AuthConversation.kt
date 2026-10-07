package com.fromwau.kortex.auth

import com.fromwau.kern.result.EmptyResult
import kotlinx.coroutines.flow.StateFlow

/**
 * One authentication in progress: the questions a backend asks and the answers given to it.
 *
 * A polkit request, a greeter's login and a lock screen all hold one of these, and each draws it its own
 * way. Whoever draws it reads [state] and answers what it asks:
 *
 * ```kotlin
 * conversation.state.collect { state ->
 *     when (state) {
 *         is AuthState.Asking -> show(state.prompt, state.notes)
 *         is AuthState.Waiting -> showBusy(state.notes)
 *         is AuthState.Rejected -> showTryAgain(state.notes)
 *         is AuthState.Ended -> close(state.outcome)
 *     }
 * }
 * conversation.answer(field.takePassword())
 * ```
 */
public interface AuthConversation {
    /** Where the authentication stands, which is everything a prompt needs to draw. */
    public val state: StateFlow<AuthState>

    /**
     * Answers the prompt [state] is asking.
     *
     * [response] is zeroed before this returns, whatever the outcome, so the caller holds no copy of a
     * password past this call.
     *
     * @return [ConversationError.NotAsking] where no prompt is open, [ConversationError.Unsendable] where
     *   the backend cannot carry [response], and [ConversationError.AlreadyEnded] once it is over.
     */
    public suspend fun answer(response: CharArray): EmptyResult<ConversationError>

    /**
     * Starts again after [AuthState.Rejected], with no notes.
     *
     * @return [ConversationError.NotRejected] in any other state, and [ConversationError.AlreadyEnded]
     *   once it is over.
     */
    public suspend fun retry(): EmptyResult<ConversationError>

    /** Ends it as [AuthError.Cancelled], unless it has already ended. */
    public suspend fun cancel()
}
