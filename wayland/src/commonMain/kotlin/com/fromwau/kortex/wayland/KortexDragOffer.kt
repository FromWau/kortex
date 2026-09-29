package com.fromwau.kortex.wayland

import androidx.compose.ui.graphics.ImageBitmap
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Result

/**
 * What a drag from another application is carrying, handed to your content as a drag and drop event's native
 * event.
 *
 * ```kotlin
 * @OptIn(ExperimentalComposeUiApi::class)
 * fun Modifier.takingDrags(): Modifier = dragAndDropTarget(
 *     shouldStartDragAndDrop = { start -> (start.nativeEvent as? KortexDragOffer)?.types?.isNotEmpty() == true },
 *     target = object : DragAndDropTarget {
 *         override fun onDrop(event: DragAndDropEvent): Boolean =
 *             (event.nativeEvent as KortexDragOffer).readText() is Ok
 *     },
 * )
 * ```
 *
 * [readText], [readImage], [readUris] and [readPortalKey] answer from memory and never block: everything the
 * drag carries has arrived before your content is told of the drop. Until the user lets go there is nothing to
 * read, because the application dragging sends what it holds only then; [types] says what is coming.
 *
 * Whether a drop copies or moves what was dragged is the compositor's to settle, so a drop reaching you is no
 * promise that the application dragging still holds it.
 */
public class KortexDragOffer internal constructor(
    private val carried: List<Mime>,
    private val text: Result<String, ClipboardError> = Err(ClipboardError.NoText),
    private val image: Result<ImageBitmap, ClipboardError> = Err(ClipboardError.NoImage),
    private val uris: Result<List<String>, ClipboardError> = Err(ClipboardError.NoUris),
    private val portalKey: Result<String, ClipboardError> = Err(ClipboardError.NoPortalKey),
) {
    /**
     * The types this drag is offered under that kortex can hand you, most preferred first, such as
     * `text/uri-list`, `text/plain;charset=utf-8` or `image/png`. A type kortex carries nothing of, a PDF say,
     * is not here, and a drag offering only those never reaches your content at all.
     */
    public val types: List<String> get() = carried.map(Mime::wireName)

    /**
     * The text this drag carries.
     *
     * @return the text, or why there is none: [ClipboardError.NoText] where the drag offers no text or has not
     *   been dropped yet, [ClipboardError.PipeFailed], [ClipboardError.ReadTimedOut] or [ClipboardError.TooLarge].
     */
    public fun readText(): Result<String, ClipboardError> = text

    /**
     * The image this drag carries.
     *
     * @return the image, or why there is none: [ClipboardError.NoImage] where the drag offers no image in a
     *   format kortex decodes or has not been dropped yet, [ClipboardError.PipeFailed],
     *   [ClipboardError.ReadTimedOut] or [ClipboardError.TooLarge].
     */
    public fun readImage(): Result<ImageBitmap, ClipboardError> = image

    /**
     * The files this drag carries, as the URIs the application dragging named them by, such as
     * `file:///home/you/notes.txt`. They arrive percent-encoded and under any scheme, so turn one into a path
     * yourself, with `Path.of(URI(uri))` for a `file` and your own handling for the rest.
     *
     * @return the URIs, or why there are none: [ClipboardError.NoUris] where the drag offers no list of files or
     *   has not been dropped yet, [ClipboardError.PipeFailed], [ClipboardError.ReadTimedOut] or
     *   [ClipboardError.TooLarge].
     */
    public fun readUris(): Result<List<String>, ClipboardError> = uris

    /**
     * The key an application in a sandbox names its files by, where it offers one instead of paths.
     *
     * A sandboxed application's own paths mean nothing outside its filesystem, so it hands over a key and
     * lets the desktop's document portal turn that into paths your side can open. kortex does not make that
     * call: it speaks Wayland and no D-Bus. Make it yourself, as
     * `org.freedesktop.portal.Documents` at `/org/freedesktop/portal/documents`, calling
     * `org.freedesktop.portal.FileTransfer.RetrieveFiles(key, {})`, which answers an array of paths.
     *
     * Prefer [readUris] and come here only when it has none: an application that offers both means the same
     * files either way, and the URIs need nothing of you.
     *
     * @return the key, or why there is none: [ClipboardError.NoPortalKey] where the drag offers no key or has
     *   not been dropped yet, [ClipboardError.PipeFailed], [ClipboardError.ReadTimedOut] or
     *   [ClipboardError.TooLarge].
     */
    public fun readPortalKey(): Result<String, ClipboardError> = portalKey
}
