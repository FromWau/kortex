package com.fromwau.kortex.compose

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.text.input.BackspaceCommand
import androidx.compose.ui.text.input.CommitTextCommand
import androidx.compose.ui.text.input.DeleteSurroundingTextCommand

/** The cursor shapes Compose can ask for. */
public enum class KortexCursor {
    Default,
    Crosshair,
    Text,
    Hand,
}

/**
 * An open text-input session: a text field has focus and is waiting to be told what was typed.
 *
 * A windowless scene has no keyboard of its own, so nothing is inserted until you forward what you
 * receive.
 */
@OptIn(ExperimentalComposeUiApi::class)
public class KortexTextInput internal constructor(
    private val request: PlatformTextInputMethodRequest,
) {
    /** Inserts [text] at the cursor. */
    public fun commit(text: String) {
        request.onEditCommand(listOf(CommitTextCommand(text, CURSOR_AFTER_TEXT)))
    }

    /** Deletes the selection, or the character before the cursor when nothing is selected. */
    public fun backspace() {
        request.onEditCommand(listOf(BackspaceCommand()))
    }

    /** Deletes [count] characters after the cursor. */
    public fun delete(count: Int = 1) {
        request.onEditCommand(listOf(DeleteSurroundingTextCommand(0, count)))
    }

    private companion object {
        const val CURSOR_AFTER_TEXT = 1
    }
}

/**
 * The capabilities you supply that a windowless scene cannot answer for itself.
 *
 * Every member has a default, so override only what you can actually do; [None] answers nothing.
 */
public interface KortexPlatform {
    /** Called when the composition wants a different cursor, e.g. via `Modifier.pointerHoverIcon`. */
    public fun setCursor(cursor: KortexCursor): Unit = Unit

    /** Called when a text field takes focus. The session stays valid until [onTextInputStopped]. */
    public fun onTextInputStarted(session: KortexTextInput): Unit = Unit

    /** Called when the text field loses focus and the session is over. */
    public fun onTextInputStopped(): Unit = Unit

    public companion object {
        public val None: KortexPlatform = object : KortexPlatform {}
    }
}
