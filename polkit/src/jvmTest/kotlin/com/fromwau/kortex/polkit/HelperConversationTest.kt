package com.fromwau.kortex.polkit

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import com.fromwau.kortex.auth.AuthError
import com.fromwau.kortex.auth.AuthState
import com.fromwau.kortex.auth.ConversationError
import com.fromwau.kortex.auth.Note
import com.fromwau.kortex.auth.Prompt
import com.fromwau.kortex.socket.SocketError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/** One authentication against a helper the test speaks for. */
class HelperConversationTest {
    private val helper = FakeHelper()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        helper.close()
    }

    @Test
    fun `the user and the cookie go first, and a right password ends it authenticated`() = runBlocking<Unit> {
        val conversation = started()
        helper.accept().use { attempt ->
            assertEquals("alice", attempt.readLine())
            assertEquals(COOKIE, attempt.readLine())
            attempt.send("PAM_PROMPT_ECHO_OFF Password: ")
            assertEquals(ASKED, conversation.reach<AuthState.Asking>())

            conversation.answer("hunter2".toCharArray()).assertSuccess()
            assertEquals("hunter2", attempt.readLine())
            attempt.send("SUCCESS")

            assertEquals(AuthState.Ended(Ok(Unit)), conversation.reach<AuthState.Ended>())
        }
    }

    @Test
    fun `what PAM says before a prompt is drawn with it`() = runBlocking<Unit> {
        val conversation = started()
        helper.accept().use { attempt ->
            attempt.skipGreeting()
            attempt.send("PAM_TEXT_INFO Place your finger on the reader")
            attempt.send("PAM_ERROR_MSG Failed to match fingerprint")
            attempt.send("PAM_PROMPT_ECHO_ON Login: ")

            assertEquals(
                AuthState.Asking(
                    Prompt.Visible("Login: "),
                    listOf(Note.Info("Place your finger on the reader"), Note.Problem("Failed to match fingerprint")),
                ),
                conversation.reach<AuthState.Asking>(),
            )
        }
    }

    @Test
    fun `a wrong password is rejected, and a retry asks again on a new attempt`() = runBlocking<Unit> {
        val conversation = started()
        helper.accept().use { attempt ->
            attempt.skipGreeting()
            attempt.send("PAM_PROMPT_ECHO_OFF Password: ")
            conversation.reach<AuthState.Asking>()
            conversation.answer("wrong".toCharArray()).assertSuccess()
            attempt.readLine()
            attempt.send("PAM_ERROR_MSG Authentication failure")
            attempt.send("FAILURE")

            assertEquals(
                AuthState.Rejected(listOf(Note.Problem("Authentication failure"))),
                conversation.reach<AuthState.Rejected>(),
            )
        }

        conversation.retry().assertSuccess()
        helper.accept().use { attempt ->
            assertEquals("alice", attempt.readLine())
            assertEquals(COOKIE, attempt.readLine())
            attempt.send("PAM_PROMPT_ECHO_OFF Password: ")
            assertEquals(ASKED, conversation.reach<AuthState.Asking>())
        }
    }

    @Test
    fun `an answer is wiped once it is given`() = runBlocking<Unit> {
        val conversation = started()
        helper.accept().use { attempt ->
            attempt.skipGreeting()
            attempt.send("PAM_PROMPT_ECHO_OFF Password: ")
            conversation.reach<AuthState.Asking>()
            val password = "hunter2".toCharArray()

            conversation.answer(password).assertSuccess()

            assertEquals(CharArray(7).toList(), password.toList())
        }
    }

    @Test
    fun `an answer while nothing is asked is refused, and still wiped`() = runBlocking<Unit> {
        val conversation = started()
        val password = "hunter2".toCharArray()

        assertEquals(ConversationError.NotAsking, conversation.answer(password).assertError())
        assertEquals(CharArray(7).toList(), password.toList())
    }

    @Test
    fun `an answer with a line break is never sent, since the helper would read it as two`() = runBlocking<Unit> {
        val conversation = started()
        helper.accept().use { attempt ->
            attempt.skipGreeting()
            attempt.send("PAM_PROMPT_ECHO_OFF Password: ")
            conversation.reach<AuthState.Asking>()

            assertEquals(ConversationError.Unsendable, conversation.answer("one\ntwo".toCharArray()).assertError())
            assertEquals(ASKED, conversation.state.value)
        }
    }

    @Test
    fun `cancelling ends it and hangs up on the helper`() = runBlocking<Unit> {
        val conversation = started()
        helper.accept().use { attempt ->
            attempt.skipGreeting()
            attempt.send("PAM_PROMPT_ECHO_OFF Password: ")
            conversation.reach<AuthState.Asking>()

            conversation.cancel()

            assertEquals(AuthState.Ended(Err(AuthError.Cancelled)), conversation.state.value)
            assertNull(attempt.readLine(), "the helper was not hung up on")
            assertEquals(
                ConversationError.AlreadyEnded(Err(AuthError.Cancelled)),
                conversation.answer("late".toCharArray()).assertError(),
            )
        }
    }

    @Test
    fun `a helper that hangs up without an answer ends it unreachable`() = runBlocking<Unit> {
        val conversation = started()
        helper.accept().use { attempt -> attempt.skipGreeting() }

        assertEquals(
            AuthState.Ended(Err(AuthError.Unreachable(SocketError.Closed))),
            conversation.reach<AuthState.Ended>(),
        )
    }

    @Test
    fun `a line the protocol does not have ends it broken`() = runBlocking<Unit> {
        val conversation = started()
        helper.accept().use { attempt ->
            attempt.skipGreeting()
            attempt.send("PAM_SOMETHING_NEW hello")

            assertEquals(
                AuthState.Ended(Err(AuthError.Broken("PAM_SOMETHING_NEW hello"))),
                conversation.reach<AuthState.Ended>(),
            )
        }
    }

    @Test
    fun `no helper listening ends it unreachable`() = runBlocking<Unit> {
        val missing = "${helper.path}.missing"
        val conversation = HelperConversation(missing, "alice", COOKIE, scope).also { it.start() }

        assertEquals(
            AuthState.Ended(Err(AuthError.Unreachable(SocketError.NotFound(missing)))),
            conversation.reach<AuthState.Ended>(),
        )
    }

    @Test
    fun `the helper's escapes are undone, bytes first and UTF-8 after`() {
        assertEquals("Passwort für alice: ", "Passwort f\\303\\274r alice: ".unescaped())
        assertEquals("a \"quoted\" back\\slash\ttab", "a \\\"quoted\\\" back\\\\slash\\ttab".unescaped())
        assertEquals("two\nlines", "two\\nlines".unescaped())
    }

    private fun started() = HelperConversation(helper.path, "alice", COOKIE, scope).also { it.start() }

    private suspend fun FakeHelper.Attempt.skipGreeting() {
        readLine()
        readLine()
    }

    private suspend inline fun <reified T : AuthState> HelperConversation.reach(): T =
        withTimeout(SETTLE) { state.first { it is T } as T }

    private companion object {
        const val COOKIE = "3-a1b2c3-4-d5e6f7"
        val ASKED = AuthState.Asking(Prompt.Secret("Password: "), emptyList())
        val SETTLE = 5.seconds
    }
}
