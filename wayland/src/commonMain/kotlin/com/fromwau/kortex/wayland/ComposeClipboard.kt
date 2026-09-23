@file:OptIn(ExperimentalComposeUiApi::class)

package com.fromwau.kortex.wayland

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.asAwtTransferable
import androidx.compose.ui.text.AnnotatedString
import com.fromwau.kern.result.getOrNull
import java.awt.datatransfer.Clipboard as AwtClipboard
import java.awt.datatransfer.ClipboardOwner
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The shell's clipboard as Compose's clipboards need it: a [KortexClipboard] that can also answer without waiting. */
internal interface TextClipboard : KortexClipboard {
    /** The text this client set, until another selection or a clear replaces it; answered at once, on any thread. */
    val ownedText: String?

    /**
     * Whether the selection is text, as this client last heard: its own text, or an offer listing a text type
     * while the client has keyboard focus. Answered at once, on any thread.
     */
    val hasText: Boolean
}

/** Provides [clipboard] to [content] as both of Compose's clipboards, so no copy or paste there reaches AWT's. */
@Composable
internal fun ProvideClipboard(clipboard: TextClipboard, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val composeClipboard = remember(clipboard, scope) { ComposeClipboard(clipboard, scope) }
    val manager = remember(clipboard, scope) { ComposeClipboardManager(clipboard, scope) }
    CompositionLocalProvider(
        LocalClipboard provides composeClipboard,
        @Suppress("DEPRECATION") LocalClipboardManager provides manager,
        content = content,
    )
}

/**
 * Compose's clipboard over [clipboard]: text in and out, and nothing at all where [clipboard] fails.
 *
 * Text alone. Compose carries an image in an entry as AWT's own image type, and reading one starts the toolkit
 * kortex keeps out of the process, so content that copies or pastes an image calls [KortexClipboard.setImage] and
 * [KortexClipboard.readImage] instead.
 */
internal class ComposeClipboard(
    private val clipboard: TextClipboard,
    // Runs a copy made through the AWT clipboard, whose setContents cannot suspend.
    private val scope: CoroutineScope,
) : Clipboard {
    override suspend fun getClipEntry(): ClipEntry? {
        val read = clipboard.readText()
        // readText cannot be cancelled, so a caller cancelled meanwhile learns it here rather than pasting late.
        currentCoroutineContext().ensureActive()
        return read.getOrNull()?.let { ClipEntry(StringSelection(it)) }
    }

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        if (clipEntry == null) {
            clipboard.clear()
            return
        }
        // An entry with no text cannot be offered here, so the selection stays as it was.
        clipEntry.text()?.let { clipboard.setText(it) }
    }

    // A text field's context menu enables Paste only when this, cast to an AWT clipboard, says it holds text.
    @Suppress("OVERRIDE_DEPRECATION")
    override val nativeClipboard: Any = PasteCheckClipboard(clipboard) { contents ->
        scope.launch { setClipEntry(ClipEntry(contents)) }
    }
}

/**
 * The AWT clipboard that foundation's paste checks ask, synchronously and on the loop thread, whether there is text
 * to paste. It answers from [TextClipboard.hasText] and never reads. Its contents are this client's own text alone:
 * another client's text it only reports as there, and that text is pasted through [Clipboard.getClipEntry]. A copy
 * handed to it goes to [copy].
 */
private class PasteCheckClipboard(
    private val clipboard: TextClipboard,
    private val copy: (Transferable) -> Unit,
) : AwtClipboard("kortex") {
    override fun isDataFlavorAvailable(flavor: DataFlavor): Boolean =
        flavor == DataFlavor.stringFlavor && clipboard.hasText

    override fun getAvailableDataFlavors(): Array<DataFlavor> =
        if (clipboard.hasText) arrayOf(DataFlavor.stringFlavor) else emptyArray()

    // The JDK's getData reads through this too, so neither ever waits on a read.
    override fun getContents(requestor: Any?): Transferable? = clipboard.ownedText?.let(::StringSelection)

    // No lostOwnership for owner: the JDK's own clipboard sends it through AWT's event thread, starting the toolkit.
    override fun setContents(contents: Transferable, owner: ClipboardOwner?) = copy(contents)
}

/**
 * Compose's synchronous clipboard over [clipboard]. [getText] answers only with [TextClipboard.ownedText]: content
 * calls it on the loop thread, which a read of another client's text needs free, so it never waits on a read.
 */
@Suppress("DEPRECATION") // Compose deprecates the interface, yet LocalClipboardManager still reaches content.
internal class ComposeClipboardManager(
    private val clipboard: TextClipboard,
    // Runs each set, which suspends where this interface cannot.
    private val scope: CoroutineScope,
) : ClipboardManager {
    override fun getText(): AnnotatedString? = clipboard.ownedText?.let { AnnotatedString(it) }

    override fun setText(annotatedString: AnnotatedString) {
        scope.launch { clipboard.setText(annotatedString.text) }
    }
}

private suspend fun ClipEntry.text(): String? {
    val transferable = asAwtTransferable ?: return null
    if (!transferable.isDataFlavorSupported(DataFlavor.stringFlavor)) return null
    // Off the caller's thread, which for content is the loop's: a Transferable may take its time producing its data.
    return withContext(Dispatchers.IO) {
        try {
            transferable.getTransferData(DataFlavor.stringFlavor) as? String
        } catch (_: IOException) {
            // The data can be gone by the time it is asked for, in a flavor the Transferable still lists.
            null
        }
    }
}
