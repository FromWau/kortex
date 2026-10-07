package com.fromwau.kortex.polkit

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.getOrElse
import com.fromwau.kortex.auth.AuthConversation
import com.fromwau.kortex.auth.AuthError
import com.fromwau.kortex.auth.AuthState
import com.fromwau.kortex.auth.ConversationError
import com.fromwau.kortex.auth.Note
import com.fromwau.kortex.auth.Prompt
import com.fromwau.kortex.socket.UnixSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import kotlin.concurrent.Volatile

/**
 * An authentication run by polkit's agent helper, which runs PAM as root and tells polkitd how it went.
 *
 * Each attempt is a connection of its own. The user name and the cookie go first, a line each, and the
 * helper then sends PAM's side of the conversation a line at a time until it says `SUCCESS` or `FAILURE`.
 */
internal class HelperConversation(
    private val helper: String,
    private val user: String,
    private val cookie: String,
    private val scope: CoroutineScope,
) : AuthConversation {
    private val current = MutableStateFlow<AuthState>(AuthState.Waiting(emptyList()))

    override val state: StateFlow<AuthState> = current.asStateFlow()

    @Volatile
    private var socket: UnixSocket? = null

    @Volatile
    private var attempt: Job? = null

    fun start() {
        attempt = scope.launch { converse() }
    }

    override suspend fun answer(response: CharArray): EmptyResult<ConversationError> {
        try {
            val asked = current.value as? AuthState.Asking
                ?: return Err(refusal(ConversationError.NotAsking))
            // The helper reads an answer up to its line break, so one inside it would answer the next prompt too.
            if ('\n' in response) return Err(ConversationError.Unsendable)
            val line = response.encodedLine() ?: return Err(ConversationError.Unsendable)

            try {
                // Waiting before the write, so the helper's next line cannot land first and be overwritten.
                if (!current.compareAndSet(asked, AuthState.Waiting(asked.notes))) {
                    return Err(refusal(ConversationError.NotAsking))
                }
                val socket = checkNotNull(socket) { "a prompt arrived before the helper was connected" }
                socket.write(line).getOrElse { failure ->
                    end(Err(AuthError.Unreachable(failure)))
                    return Err(refusal(ConversationError.NotAsking))
                }
            } finally {
                line.fill(0)
            }
            return Ok(Unit)
        } finally {
            response.fill(Char(0))
        }
    }

    override suspend fun retry(): EmptyResult<ConversationError> {
        val rejected = current.value as? AuthState.Rejected
            ?: return Err(refusal(ConversationError.NotRejected))
        if (!current.compareAndSet(rejected, AuthState.Waiting(emptyList()))) {
            return Err(refusal(ConversationError.NotRejected))
        }
        start()
        return Ok(Unit)
    }

    override suspend fun cancel() {
        end(Err(AuthError.Cancelled))
        // A read cut short closes the socket, which is how the helper hears the attempt is over.
        attempt?.cancelAndJoin()
    }

    private suspend fun converse() {
        val socket = UnixSocket.connect(helper).getOrElse { failure ->
            return end(Err(AuthError.Unreachable(failure)))
        }
        this.socket = socket
        socket.use {
            socket.write("$user\n$cookie\n".encodeToByteArray()).getOrElse { failure ->
                return end(Err(AuthError.Unreachable(failure)))
            }
            do {
                val line = socket.readLine().getOrElse { failure ->
                    return end(Err(AuthError.Unreachable(failure)))
                }
            } while (hear(line.unescaped()))
        }
    }

    /** Takes one line from the helper, and says whether more are coming. */
    private fun hear(line: String): Boolean {
        when {
            line.startsWith(SECRET) -> move { notes -> AuthState.Asking(Prompt.Secret(line.after(SECRET)), notes) }
            line.startsWith(VISIBLE) -> move { notes -> AuthState.Asking(Prompt.Visible(line.after(VISIBLE)), notes) }
            // The helper waits for an answer after every prompt, so no note arrives while one is open.
            line.startsWith(PROBLEM) -> move { notes -> AuthState.Waiting(notes + Note.Problem(line.after(PROBLEM))) }
            line.startsWith(INFO) -> move { notes -> AuthState.Waiting(notes + Note.Info(line.after(INFO))) }
            line == SUCCESS -> end(Ok(Unit))
            line == FAILURE -> move { notes -> AuthState.Rejected(notes) }
            else -> end(Err(AuthError.Broken(line)))
        }
        return current.value is AuthState.Waiting || current.value is AuthState.Asking
    }

    private fun move(next: (List<Note>) -> AuthState) = current.update { now ->
        when (now) {
            is AuthState.Ended -> now
            is AuthState.Waiting -> next(now.notes)
            is AuthState.Asking -> next(now.notes)
            is AuthState.Rejected -> next(now.notes)
        }
    }

    private fun end(outcome: EmptyResult<AuthError>) = current.update { now ->
        if (now is AuthState.Ended) now else AuthState.Ended(outcome)
    }

    private fun refusal(otherwise: ConversationError): ConversationError =
        (current.value as? AuthState.Ended)?.let { ended -> ConversationError.AlreadyEnded(ended.outcome) }
            ?: otherwise

    private companion object {
        const val SECRET = "PAM_PROMPT_ECHO_OFF "
        const val VISIBLE = "PAM_PROMPT_ECHO_ON "
        const val PROBLEM = "PAM_ERROR_MSG "
        const val INFO = "PAM_TEXT_INFO "
        const val SUCCESS = "SUCCESS"
        const val FAILURE = "FAILURE"
    }
}

private fun String.after(prefix: String): String = substring(prefix.length)

/** The answer as UTF-8 with its line break, or null where it holds a lone surrogate UTF-8 cannot carry. */
private fun CharArray.encodedLine(): ByteArray? {
    val encoded = try {
        Charsets.UTF_8.newEncoder().encode(CharBuffer.wrap(this))
    } catch (_: CharacterCodingException) {
        return null
    }
    val line = ByteArray(encoded.remaining() + 1)
    encoded.get(line, 0, line.size - 1)
    line[line.size - 1] = '\n'.code.toByte()
    encoded.array().fill(0)
    return line
}

/**
 * Undoes glib's `g_strescape`, which the helper writes every line through.
 *
 * It escapes every byte outside printable ASCII as octal, so the bytes are put back first and only then
 * read as UTF-8.
 */
internal fun String.unescaped(): String {
    val raw = encodeToByteArray()
    val bytes = ByteArrayOutputStream()
    var index = 0
    while (index < raw.size) {
        val byte = raw[index++]
        if (byte != BACKSLASH || index == raw.size) {
            bytes.write(byte.toInt())
            continue
        }
        when (val escaped = raw[index++].toInt().toChar()) {
            in '0'..'7' -> {
                var value = escaped - '0'
                repeat(2) {
                    val next = raw.getOrNull(index)?.toInt()?.toChar()
                    if (next != null && next in '0'..'7') {
                        value = value * 8 + (next - '0')
                        index++
                    }
                }
                bytes.write(value)
            }

            'b' -> bytes.write('\b'.code)
            'f' -> bytes.write(0x0c)
            'n' -> bytes.write('\n'.code)
            'r' -> bytes.write('\r'.code)
            't' -> bytes.write('\t'.code)
            'v' -> bytes.write(0x0b)
            else -> bytes.write(escaped.code)
        }
    }
    return bytes.toString(Charsets.UTF_8)
}

private const val BACKSLASH = '\\'.code.toByte()
