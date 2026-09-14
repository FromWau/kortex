package com.fromwau.kortex.wayland

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.awtClipboard
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.getOrElse
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.fail
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Compose's two clipboards over the shell's, driven against a fake of it: what a copy, a clear and a paste hand
 * over, what the synchronous clipboard answers without reading, and which clipboards a shell's content is given.
 */
@OptIn(ExperimentalComposeUiApi::class)
class ComposeClipboardTest {
    @Test
    fun `a null entry clears the selection`() {
        val clipboard = FakeTextClipboard()
        runBlocking { ComposeClipboard(clipboard).setClipEntry(null) }
        assertEquals(1, clipboard.clears.get(), "a null entry did not clear the selection")
    }

    @Test
    fun `an entry that carries no text leaves the selection as it was`() {
        val clipboard = FakeTextClipboard()
        runBlocking { ComposeClipboard(clipboard).setClipEntry(ClipEntry(ImageOnly)) }
        assertEquals(0, clipboard.clears.get(), "an entry with no text cleared the selection")
        assertEquals(emptyList(), clipboard.setTexts.toList(), "an entry with no text set the selection")
    }

    @Test
    fun `a read that returns after its caller was cancelled hands that caller no entry`() {
        val release = CompletableDeferred<Unit>()
        // Held through its caller's cancellation, as the shell's own read is.
        val clipboard = FakeTextClipboard(
            read = {
                withContext(NonCancellable) { release.await() }
                Ok(COPIED)
            },
        )
        val handedBack = AtomicReference<ClipEntry?>()
        runBlocking {
            // Unconfined runs the paste up to its held read, and on from there as the read is released.
            val paste = launch(Dispatchers.Unconfined) { handedBack.set(ComposeClipboard(clipboard).getClipEntry()) }
            paste.cancel()
            release.complete(Unit)
            paste.join()
        }
        assertNull(handedBack.get(), "a paste cancelled during its read was handed the entry anyway")
    }

    @Test
    fun `Compose's awtClipboard finds none instead of throwing, as a text field's paste check needs`() {
        assertNull(ComposeClipboard(FakeTextClipboard()).awtClipboard, "the clipboard claimed to be AWT's")
    }

    @Test
    fun `the synchronous getter answers only with this client's own text, and never reads`() {
        val clipboard = FakeTextClipboard(read = { Ok(OTHER_CLIENTS) })
        val manager = ComposeClipboardManager(clipboard, CoroutineScope(Dispatchers.Unconfined))

        clipboard.ownedText = COPIED
        assertEquals(COPIED, manager.getText()?.text, "the getter did not answer with this client's own text")
        clipboard.ownedText = null
        assertNull(manager.getText(), "the getter answered for another client's selection")
        assertEquals(0, clipboard.reads.get(), "the getter read the selection, which waits on the loop it runs on")
    }

    @Test
    fun `the synchronous setter sets the selection`() {
        val clipboard = FakeTextClipboard()
        // Unconfined runs the set the setter launches before the setter returns.
        ComposeClipboardManager(clipboard, CoroutineScope(Dispatchers.Unconfined)).setText(AnnotatedString(COPIED))
        assertEquals(listOf(COPIED), clipboard.setTexts.toList(), "the setter did not set the selection")
    }

    @Test
    fun `a shell gives its surface's content kortex's clipboards, not AWT's`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }
        val clipboard = AtomicReference<Any?>()
        val manager = AtomicReference<Any?>()
        display.use { wayland ->
            val spec = SurfaceSpec(SPECK_CONFIG, OutputTarget.CompositorChoice) {
                clipboard.set(LocalClipboard.current)
                // Deprecated by Compose, yet still what content that has not moved to LocalClipboard reads.
                @Suppress("DEPRECATION")
                val deprecatedManager = LocalClipboardManager.current
                manager.set(deprecatedManager)
                Box(Modifier.fillMaxSize())
            }
            val shell = KortexShell.create(wayland, spec).getOrElse { error -> fail("shell creation failed: $error") }
            shell.useOrFail {
                assertIs<ComposeClipboard>(clipboard.get(), "content's LocalClipboard is not the shell's")
                assertIs<ComposeClipboardManager>(manager.get(), "content's LocalClipboardManager is not the shell's")
            }
        }
    }

    /** A copy offered only as an image, as a copied picture would be; nothing here asks for the image itself. */
    private object ImageOnly : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.imageFlavor)

        override fun isDataFlavorSupported(flavor: DataFlavor?): Boolean = flavor == DataFlavor.imageFlavor

        override fun getTransferData(flavor: DataFlavor?): Any = throw UnsupportedFlavorException(flavor)
    }

    private companion object {
        const val COPIED = "Grüße aus kortex"
        const val OTHER_CLIENTS = "from another client"

        // A speck in a corner that takes neither the keyboard nor any screen space from the desktop.
        val SPECK_CONFIG = SurfaceConfig(
            namespace = "kortex-compose-clipboard",
            layer = Layer.Overlay,
            anchor = setOf(Edge.Bottom, Edge.Right),
            width = 8.dp,
            height = 8.dp,
            exclusiveZone = ExclusiveZone.Yield,
        )
    }
}
