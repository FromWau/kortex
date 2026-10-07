package com.fromwau.kortex.polkitagent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.fromwau.kortex.auth.Note
import com.fromwau.kortex.auth.Prompt

/**
 * The card that asks for the password: what is about to happen, as whom, and the field to answer in.
 *
 * Enter answers and Escape cancels, since the keyboard is all a held keyboard leaves to reach it with.
 */
@Composable
internal fun PromptCard(
    state: PromptState,
    onAction: (PromptAction) -> Unit,
) {
    val asking = state.step as? Step.Asking
    // Keyed on the step, so each new prompt starts empty.
    var text by remember(asking) { mutableStateOf("") }
    val submit = {
        if (asking != null) {
            onAction(PromptAction.Submit(text.toCharArray()))
            text = ""
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.width(CARD_WIDTH),
    ) {
        Column(
            modifier = Modifier
                .padding(24.dp)
                .onPreviewKeyEvent { event ->
                    val escape = event.type == KeyEventType.KeyDown && event.key == Key.Escape
                    if (escape) onAction(PromptAction.Cancel)
                    escape
                },
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Authentication required",
                style = MaterialTheme.typography.headlineSmall,
            )

            Text(
                text = state.message,
                style = MaterialTheme.typography.bodyMedium,
            )

            Text(
                text = "as ${state.user}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when (asking) {
                null -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

                else -> AnswerField(
                    prompt = asking.prompt,
                    text = text,
                    onTextChange = { text = it },
                    onSubmit = submit,
                )
            }

            Notes(state)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = { onAction(PromptAction.Cancel) }) {
                    Text("Cancel")
                }

                Button(
                    onClick = submit,
                    enabled = asking != null,
                ) {
                    Text("Authenticate")
                }
            }
        }
    }
}

@Composable
private fun AnswerField(
    prompt: Prompt,
    text: String,
    onTextChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val focus = remember(prompt) { FocusRequester() }

    OutlinedTextField(
        value = text,
        onValueChange = onTextChange,
        label = { Text(prompt.text.trim().removeSuffix(":")) },
        singleLine = true,
        visualTransformation = when (prompt) {
            is Prompt.Secret -> PasswordVisualTransformation()
            is Prompt.Visible -> VisualTransformation.None
        },
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focus)
            .onPreviewKeyEvent { event ->
                val enter = event.type == KeyEventType.KeyDown && event.key == Key.Enter
                if (enter) onSubmit()
                enter
            },
    )

    LaunchedEffect(focus) { focus.requestFocus() }
}

/** What PAM said: about the last rejected answer first, then during this attempt. */
@Composable
private fun Notes(state: PromptState) {
    val rejected = state.rejected?.let { notes -> notes.ifEmpty { listOf(Note.Problem(REJECTED)) } }.orEmpty()
    (rejected + state.notes).forEach { note ->
        Text(
            text = note.text,
            style = MaterialTheme.typography.bodySmall,
            color = when (note) {
                is Note.Problem -> MaterialTheme.colorScheme.error
                is Note.Info -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

private const val REJECTED = "That did not work. Try again."
private val CARD_WIDTH = 420.dp
