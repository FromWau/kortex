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
import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.getOrNull
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** A clipboard that holds text: what Compose's clipboard needs of the shell's. */
internal interface TextClipboard {
    /** The text this client set, until another selection or a clear replaces it; answered at once, on any thread. */
    val ownedText: String?

    suspend fun setText(text: String): EmptyResult<ClipboardError>

    suspend fun readText(): Result<String, ClipboardError>

    suspend fun clear(): EmptyResult<ClipboardError>
}

/** Provides [clipboard] to [content] as both of Compose's clipboards, so no copy or paste there reaches AWT's. */
@Composable
internal fun ProvideClipboard(clipboard: TextClipboard, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val composeClipboard = remember(clipboard) { ComposeClipboard(clipboard) }
    val manager = remember(clipboard, scope) { ComposeClipboardManager(clipboard, scope) }
    CompositionLocalProvider(
        LocalClipboard provides composeClipboard,
        @Suppress("DEPRECATION") LocalClipboardManager provides manager,
        content = content,
    )
}

/** Compose's clipboard over [clipboard]: text in and out, and nothing at all where [clipboard] fails. */
internal class ComposeClipboard(private val clipboard: TextClipboard) : Clipboard {
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
        // An entry with no text, an image say, cannot be offered, so the selection stays as it was.
        clipEntry.text()?.let { clipboard.setText(it) }
    }

    // A text field's paste check casts this to an AWT clipboard; the interface's default throws there instead.
    @Suppress("OVERRIDE_DEPRECATION")
    override val nativeClipboard: Any get() = clipboard
}

/**
 * Compose's synchronous clipboard over [clipboard]. [getText] answers only with [TextClipboard.ownedText]: content
 * calls it on the loop thread, which a read of this client's own selection needs free, so it never waits on a read.
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

private fun ClipEntry.text(): String? {
    val transferable = asAwtTransferable ?: return null
    if (!transferable.isDataFlavorSupported(DataFlavor.stringFlavor)) return null
    return try {
        transferable.getTransferData(DataFlavor.stringFlavor) as? String
    } catch (_: IOException) {
        // The data can be gone by the time it is asked for, in a flavor the Transferable still lists.
        null
    }
}
