package com.fromwau.kortex.polkitagent

import com.fromwau.kortex.auth.AuthConversation
import com.fromwau.kortex.auth.AuthState
import com.fromwau.kortex.auth.Note
import com.fromwau.kortex.auth.Prompt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What the prompt for one request draws. */
internal data class PromptState(
    val message: String,
    val user: String,
    val step: Step,
    val notes: List<Note>,
    /** What was said when the last answer was rejected, kept so the next prompt is drawn with it. */
    val rejected: List<Note>?,
)

internal sealed interface Step {
    data class Asking(val prompt: Prompt) : Step

    data object Waiting : Step
}

internal sealed interface PromptAction {
    /** [answer] is wiped once it is sent. */
    class Submit(val answer: CharArray) : PromptAction

    data object Cancel : PromptAction
}

/**
 * The prompt asking [user] about [message]: [conversation] as something to draw, and the user's actions sent
 * back to it.
 *
 * A rejected answer asks again at once, the way a password dialog does, with what PAM said about it kept.
 */
internal class PromptHolder(
    message: String,
    user: String,
    private val conversation: AuthConversation,
) {
    private val current = MutableStateFlow(
        PromptState(
            message = message,
            user = user,
            step = Step.Waiting,
            notes = emptyList(),
            rejected = null,
        ),
    )

    val state: StateFlow<PromptState> = current.asStateFlow()

    /** Follows the conversation for as long as the prompt is shown; it never returns on its own. */
    suspend fun run() {
        conversation.state.collect { auth ->
            when (auth) {
                is AuthState.Asking -> current.update { it.copy(step = Step.Asking(auth.prompt), notes = auth.notes) }
                is AuthState.Waiting -> current.update { it.copy(step = Step.Waiting, notes = auth.notes) }

                is AuthState.Rejected -> {
                    current.update { it.copy(step = Step.Waiting, rejected = auth.notes) }
                    conversation.retry()
                }

                is AuthState.Ended -> Unit
            }
        }
    }

    suspend fun onAction(action: PromptAction) {
        // Either refusal means the conversation has moved on since this was drawn, and state already shows it.
        when (action) {
            is PromptAction.Submit -> conversation.answer(action.answer)
            PromptAction.Cancel -> conversation.cancel()
        }
    }
}
