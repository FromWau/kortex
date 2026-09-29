package com.fromwau.kortex.compose

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draganddrop.DragAndDropTransferAction
import androidx.compose.ui.draganddrop.DragAndDropTransferable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.text.input.BackspaceCommand
import androidx.compose.ui.text.input.CommitTextCommand
import androidx.compose.ui.text.input.DeleteSurroundingTextCommand

/**
 * The cursor shapes kortex shows: `java.awt.Cursor`'s 14 predefined types. Any other pointer icon, a custom
 * AWT cursor included, shows as [Default].
 */
public enum class KortexCursor {
    Default,
    Crosshair,
    Text,
    Hand,
    Move,
    Wait,
    ResizeNorth,
    ResizeNorthEast,
    ResizeEast,
    ResizeSouthEast,
    ResizeSouth,
    ResizeSouthWest,
    ResizeWest,
    ResizeNorthWest,
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
 * What content drags out of a scene: hand one to Compose's `DragAndDropTransferData` as its transferable, and
 * whatever the drag is dropped on, in your application or another, is offered what it holds.
 *
 * ```kotlin
 * @OptIn(ExperimentalComposeUiApi::class)
 * fun Modifier.draggingLink(link: String): Modifier = dragAndDropSource {
 *     DragAndDropTransferData(KortexDragSource.Text(link), listOf(DragAndDropTransferAction.Copy))
 * }
 * ```
 *
 * A drag is a copy whichever actions you list, so a drop never takes away what you dragged, and the drag
 * decoration you draw is not used: the desktop shows a drag cursor of its own.
 *
 * A drag the desktop will not take does not start, and `DragAndDropTransferData`'s own `onTransferCompleted` is
 * called with null to say so: a scene with nothing behind it to drag from, or a desktop that has sent your
 * application no click to quote yet. An [Image] larger than one transfer carries, 64 MiB encoded, starts like
 * any other and delivers nothing when it is dropped, since its size is known only once a receiver asks for it.
 */
@OptIn(ExperimentalComposeUiApi::class)
public sealed interface KortexDragSource : DragAndDropTransferable {
    /** A text, offered under every text type a paste can ask for. */
    public data class Text(public val text: String) : KortexDragSource

    /** An image, offered as both a PNG and a JPEG. */
    public data class Image(public val image: ImageBitmap) : KortexDragSource

    /**
     * Files, offered as the `text/uri-list` a file manager reads.
     *
     * Name each by its URI and not its path, percent-encoded, as `file:///home/you/my%20notes.txt`. Nothing
     * here checks that: what a destination makes of a URI is its own business, and a bare path sent where one
     * belongs simply fails to resolve wherever it lands.
     */
    public data class Files(public val uris: List<String>) : KortexDragSource
}

/**
 * One drag out of a scene: what it carries, what may be done with it, and where its outcome is reported.
 *
 * @param dragged what the drag carries, which content named as its transferable.
 * @param actions what content will let the drop do, out of [DragAndDropTransferAction.Copy] and
 *   [DragAndDropTransferAction.Move]; never empty. The desktop settles on one of them, usually from the
 *   modifier keys the user holds.
 * @param onEnded called once the drag is over, with the action it settled on, or null where it did not
 *   complete. A [DragAndDropTransferAction.Move] is where content removes what left it, and it is told only
 *   here: nothing else reports that a drop happened.
 */
@OptIn(ExperimentalComposeUiApi::class)
public class KortexDrag(
    public val dragged: KortexDragSource,
    public val actions: Set<DragAndDropTransferAction>,
    public val onEnded: (DragAndDropTransferAction?) -> Unit,
)

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

    /**
     * Called when content asks to drag something out, e.g. through `Modifier.dragAndDropSource`.
     *
     * Answer from the call itself, and leave whatever the payload costs to encode to the transfer that asks for
     * it: a desktop hands a drag the input the user is still holding, so one asked for a moment later is one it
     * may refuse.
     *
     * @return whether the drag started. Answer false, as the default does, when nothing you host can carry a
     *   drag to the desktop; content is then told its gesture did not complete, and Compose offers the same drag
     *   to the next `dragAndDropSource` above the one that asked.
     */
    public fun startDrag(drag: KortexDrag): Boolean = false

    public companion object {
        public val None: KortexPlatform = object : KortexPlatform {}
    }
}
