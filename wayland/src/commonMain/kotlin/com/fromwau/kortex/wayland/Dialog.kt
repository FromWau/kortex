package com.fromwau.kortex.wayland

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Keeps a dialog of your own on screen while this call is in composition, drawing [content] in it: a window of
 * its own, which the compositor is told belongs to the window this call's content is in. A question to answer,
 * a file to pick, a warning to confirm.
 *
 * A compositor uses that to keep the dialog with its window, commonly by floating it over that window and
 * raising the two together; how far it takes that is its own decision.
 *
 * ```kotlin
 * Window(title = "Notes") {
 *     var asking by remember { mutableStateOf(false) }
 *
 *     Button(onClick = { asking = true }) { Text("Quit") }
 *
 *     if (asking) {
 *         Dialog(title = "Discard changes?") {
 *             Button(onClick = { asking = false }) { Text("Keep editing") }
 *         }
 *     }
 * }
 * ```
 *
 * Call it anywhere. A compositor takes a dialog as belonging to another window and to nothing else, so one
 * shown from a bar's content, from a popup's, or in [kortexApplication]'s own content belongs to nothing, and
 * is listed and placed like any other window. Everything else about it is the same either way.
 *
 * A dialog does not lock the window it belongs to: the user can still reach that window, type in it and close
 * it. Stop composing this call to take the dialog away, and stop composing the window's own call to take both.
 *
 * A dialog is matched by the app id `kortex`, the one a window whose caller names none carries, so a window
 * rule written for that app id finds it.
 *
 * The dialog appears shortly after the call enters composition, and goes when the call leaves it. It keeps
 * running and draws the newest [content] throughout, and a changed [title] reaches the dialog on screen shortly
 * after, ending nothing. State [content] keeps behind `remember` stands through those changes, and lasts until
 * the dialog ends.
 *
 * A changed [width] or [height] draws content at the new size, and the dialog keeps it until the compositor next
 * sizes the dialog. [state] reports the size it is drawn at, whichever it is.
 *
 * The compositor can ask for the dialog to close, which sets [WindowState.closeRequested] and does nothing else.
 * Take the call out of composition to close it, leave it in to refuse, and say a refusal with
 * [WindowState.declineClose] so the next ask is seen as one.
 *
 * Once the dialog has ended, in any of the ways [WindowStatus.Ended] lists, the call shows nothing until you take
 * it out of composition and put it back, which places a dialog again and takes [state] with it.
 *
 * @param title what the compositor shows for the dialog wherever it names it, such as a task bar or a window
 *   switcher, exactly as written.
 * @param width how wide content is drawn, at first and again each time this changes, until the compositor next
 *   gives the dialog a size of its own.
 * @param height how tall content is drawn, on the same terms as [width].
 * @param state where to read what the dialog is doing: [WindowStatus.Placing] until it reaches the screen,
 *   [WindowStatus.OnScreen] with the size it is drawn at, and [WindowStatus.Ended] with how it ended, once and
 *   for good. Pass a state of your own to keep reading it after the call has left composition; one call at a
 *   time publishes to a state.
 * @param content what is drawn in the dialog. It reaches the dialog itself as `this`, the clipboard as
 *   [LocalKortexClipboard], and the same dialog through `LocalKortexSurface.current` in a composable further
 *   down.
 * @throws IllegalStateException when called outside [kortexApplication].
 * @throws IllegalArgumentException when [width] or [height] rounds to less than one pixel, which ends more than
 *   this dialog: the application when this call is in [kortexApplication]'s own content, and otherwise the
 *   surface whose content it is in.
 */
@Composable
public fun Dialog(
    title: String,
    width: Dp = 400.dp,
    height: Dp = 200.dp,
    state: WindowState = rememberWindowState(),
    content: @Composable SurfaceScope.() -> Unit,
) {
    require(width.toLogicalPx() > 0 && height.toLogicalPx() > 0) {
        "a dialog is drawn at a size of at least one pixel on each axis, not $width by $height"
    }
    SurfaceCall(
        settings = DialogSettings(title = title, width = width, height = height),
        published = state.published,
        content = content,
    )
}

/** What a [Dialog] call asks for; its parent is the surface its call composes in, which no setting names. */
internal data class DialogSettings(
    override val title: String,
    override val width: Dp,
    override val height: Dp,
) : ToplevelSettings() {
    override val appId: String get() = DEFAULT_APP_ID
}
