package com.fromwau.kortex.polkitagent

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kortex.auth.AuthConversation
import com.fromwau.kortex.auth.AuthError
import com.fromwau.kortex.auth.AuthState
import com.fromwau.kortex.auth.ConversationError
import com.fromwau.kortex.auth.Note
import com.fromwau.kortex.auth.Prompt
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class PromptHolderTest {
    private val conversation = ScriptedConversation()
    private val user = MutableStateFlow("alice")
    private val switches = mutableListOf<String>()
    private val holder = PromptHolder(
        message = "Restart sshd.service?",
        users = listOf("alice", "root"),
        user = user,
        conversation = conversation,
        switchUser = { name -> switches += name },
    )

    @Test
    fun `a prompt is drawn with what PAM said before it`() = runTest(UnconfinedTestDispatcher()) {
        val running = launch { holder.run() }
        val info = Note.Info("Place your finger on the reader")

        conversation.state.value = AuthState.Asking(Prompt.Secret("Password: "), listOf(info))

        assertEquals(Step.Asking(Prompt.Secret("Password: ")), holder.state.value.step)
        assertEquals(listOf(info), holder.state.value.notes)
        running.cancel()
    }

    @Test
    fun `a rejected answer asks again, and keeps what was said about it`() = runTest(UnconfinedTestDispatcher()) {
        val running = launch { holder.run() }
        val failure = Note.Problem("Authentication failure")

        conversation.state.value = AuthState.Rejected(listOf(failure))

        assertEquals(1, conversation.retries)
        assertEquals(listOf(failure), holder.state.value.rejected)
        assertEquals(Step.Waiting, holder.state.value.step)

        conversation.state.value = AuthState.Asking(Prompt.Secret("Password: "), emptyList())
        assertEquals(listOf(failure), holder.state.value.rejected, "the next prompt lost why the last one failed")
        running.cancel()
    }

    @Test
    fun `switching user forgets why the last user's answer was rejected`() = runTest(UnconfinedTestDispatcher()) {
        val running = launch { holder.run() }
        conversation.state.value = AuthState.Rejected(listOf(Note.Problem("Authentication failure")))

        holder.onAction(PromptAction.SwitchUser("root"))

        assertEquals(listOf("root"), switches)
        assertEquals(null, holder.state.value.rejected)
        running.cancel()
    }

    @Test
    fun `the prompt names whoever is being asked now`() = runTest(UnconfinedTestDispatcher()) {
        val running = launch { holder.run() }

        user.value = "root"

        assertEquals("root", holder.state.value.user)
        assertEquals(listOf("alice", "root"), holder.state.value.users)
        running.cancel()
    }

    @Test
    fun `a submitted answer reaches the conversation, and cancel cancels it`() = runTest {
        holder.onAction(PromptAction.Submit("hunter2".toCharArray()))
        holder.onAction(PromptAction.Cancel)

        assertEquals(listOf("hunter2"), conversation.answers)
        assertEquals(AuthState.Ended(Err(AuthError.Cancelled)), conversation.state.value)
    }

    private class ScriptedConversation : AuthConversation {
        override val state = MutableStateFlow<AuthState>(AuthState.Waiting(emptyList()))
        val answers = mutableListOf<String>()
        var retries = 0

        override suspend fun answer(response: CharArray): EmptyResult<ConversationError> {
            answers += response.concatToString()
            return Ok(Unit)
        }

        override suspend fun retry(): EmptyResult<ConversationError> {
            retries++
            return Ok(Unit)
        }

        override suspend fun cancel() {
            state.value = AuthState.Ended(Err(AuthError.Cancelled))
        }
    }
}
