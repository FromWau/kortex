package com.fromwau.kortex.wayland

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.awtClipboard
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.getOrElse
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertIsNot
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.future.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Compose's two clipboards over the shell's, driven against a fake of it: what a copy, a clear and a paste hand
 * over, what the synchronous clipboard answers without reading, and which clipboards a shell's content is given,
 * its typed one among them.
 */
@OptIn(ExperimentalComposeUiApi::class)
class ComposeClipboardTest {
    @Test
    fun `a null entry clears the selection`() {
        val clipboard = FakeTextClipboard()
        runBlocking { composeClipboard(clipboard).setClipEntry(null) }
        assertEquals(1, clipboard.clears.get(), "a null entry did not clear the selection")
    }

    @Test
    fun `an entry that carries no text leaves the selection as it was`() {
        val clipboard = FakeTextClipboard()
        runBlocking { composeClipboard(clipboard).setClipEntry(ClipEntry(ImageOnly)) }
        assertEquals(0, clipboard.clears.get(), "an entry with no text cleared the selection")
        assertEquals(emptyList(), clipboard.setTexts.toList(), "an entry with no text set the selection")
    }

    @Test
    fun `a copy reads its entry's text off the thread that copies`() {
        val clipboard = FakeTextClipboard()
        val reader = AtomicReference<Thread?>()
        runBlocking { composeClipboard(clipboard).setClipEntry(ClipEntry(ThreadRecordingText(reader))) }
        assertNotEquals(Thread.currentThread(), reader.get(), "the entry's text was read on the thread that copied")
        assertEquals(listOf(COPIED), clipboard.setTexts.toList(), "the copy did not set the entry's text")
    }

    @Test
    fun `a copy the clipboard refuses does nothing and throws nothing`() {
        val clipboard = FakeTextClipboard(set = { Err(ClipboardError.NoInputSerial) })
        runBlocking { composeClipboard(clipboard).setClipEntry(ClipEntry(StringSelection(COPIED))) }
        assertEquals(listOf(COPIED), clipboard.setTexts.toList(), "the copy was not tried exactly once")
        assertEquals(0, clipboard.clears.get(), "a refused copy cleared the selection")
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
            val paste = launch(Dispatchers.Unconfined) { handedBack.set(composeClipboard(clipboard).getClipEntry()) }
            paste.cancel()
            release.complete(Unit)
            paste.join()
        }
        assertNull(handedBack.get(), "a paste cancelled during its read was handed the entry anyway")
    }

    @Test
    fun `a text field's paste check finds text exactly while the clipboard has some, and reads none`() {
        // Another client's text: the clipboard has text, none of it this client's own.
        val clipboard = FakeTextClipboard(read = { Ok(OTHER_CLIENTS) })
        val awt = assertNotNull(composeClipboard(clipboard).awtClipboard, "the paste check found no clipboard to ask")

        // The two questions foundation's paste checks ask: nativeClipboardHasText, and ClipboardPasteState.update.
        assertFalse(
            awt.isDataFlavorAvailable(DataFlavor.stringFlavor),
            "the check found text on a clipboard with none",
        )
        assertEquals(emptyList(), awt.availableDataFlavors.toList(), "a clipboard with no text listed a flavor")
        clipboard.hasText = true
        assertTrue(
            awt.isDataFlavorAvailable(DataFlavor.stringFlavor),
            "the check found no text on a clipboard with some",
        )
        assertEquals(listOf(DataFlavor.stringFlavor), awt.availableDataFlavors.toList(), "the flavors named no text")
        assertEquals(0, clipboard.reads.get(), "the check read the selection, which waits on the loop it runs on")
    }

    @Test
    fun `the AWT clipboard's contents are this client's own text, and none for another client's`() {
        val clipboard = FakeTextClipboard(read = { Ok(OTHER_CLIENTS) })
        val awt = assertNotNull(composeClipboard(clipboard).awtClipboard, "there was no AWT clipboard to ask")

        clipboard.ownedText = COPIED
        clipboard.hasText = true
        assertEquals(
            COPIED, awt.getContents(null)?.getTransferData(DataFlavor.stringFlavor),
            "the AWT clipboard's contents were not this client's own text",
        )
        assertEquals(
            COPIED, awt.getData(DataFlavor.stringFlavor),
            "the AWT clipboard's data was not this client's text",
        )

        clipboard.ownedText = null
        assertNull(awt.getContents(null), "the AWT clipboard had contents for another client's text")
        assertEquals(
            0, clipboard.reads.get(),
            "the AWT clipboard read the selection, which waits on the loop it runs on",
        )
    }

    @Test
    fun `an AWT copy reaches the clipboard without waiting for it`() {
        val release = CompletableDeferred<Unit>()
        val clipboard = FakeTextClipboard(
            set = {
                release.await()
                Ok(Unit)
            },
        )
        val awt = assertNotNull(composeClipboard(clipboard).awtClipboard, "there was no AWT clipboard to copy into")
        val copy = CompletableFuture.runAsync { awt.setContents(StringSelection(COPIED), null) }
        try {
            copy.get(CALL_BOUND_MILLIS, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            fail("setContents was still waiting on the clipboard ${CALL_BOUND_MILLIS}ms in")
        } finally {
            release.complete(Unit)
        }
        assertTrue(awaitUntil { clipboard.setTexts.isNotEmpty() }, "the copy never reached the clipboard")
        assertEquals(listOf(COPIED), clipboard.setTexts.toList(), "the copy set something other than its text")
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
        val clipboard = AtomicReference<Any?>()
        val manager = AtomicReference<Any?>()
        val typed = AtomicReference<KortexClipboard?>()
        withSpeckShell(
            content = {
                clipboard.set(LocalClipboard.current)
                // Deprecated by Compose, yet still what content that has not moved to LocalClipboard reads.
                @Suppress("DEPRECATION")
                val deprecatedManager = LocalClipboardManager.current
                manager.set(deprecatedManager)
                typed.set(LocalKortexClipboard.current)
                Box(Modifier.fillMaxSize())
            },
        ) { _ ->
            assertIs<ComposeClipboard>(clipboard.get(), "content's LocalClipboard is not the shell's")
            assertIs<ComposeClipboardManager>(manager.get(), "content's LocalClipboardManager is not the shell's")
            assertIsNot<AutoCloseable>(
                assertNotNull(typed.get(), "content was handed no typed clipboard"),
                "content was handed a clipboard it could close",
            )
        }
    }

    @Test
    fun `content reads and writes the shell's clipboard through LocalKortexClipboard, failures included`() {
        val clipboard = FakeTextClipboard(read = { Ok(OTHER_CLIENTS) }, set = { Err(ClipboardError.NoInputSerial) })
        val results = CopyOnWriteArrayList<Result<Any, ClipboardError>>()
        withSpeckShell(
            content = {
                val typed = LocalKortexClipboard.current
                LaunchedEffect(typed) {
                    results += typed.readText()
                    results += typed.setText(COPIED)
                    results += typed.clear()
                }
            },
            contentClipboard = { clipboard },
        ) { shell ->
            assertTrue(shell.pumpOrFail(PUMP_MILLIS) { results.size == CALLS }, "content's calls never returned")
        }
        assertEquals(
            listOf(Ok(OTHER_CLIENTS), Err(ClipboardError.NoInputSerial), Ok(Unit)),
            results.toList(),
            "content did not get the clipboard's own results",
        )
    }

    @Test
    fun `content inside a Popup reaches the shell's clipboard through LocalKortexClipboard`() {
        val clipboard = FakeTextClipboard(read = { Ok(OTHER_CLIENTS) })
        val typed = AtomicReference<KortexClipboard?>()
        withSpeckShell(
            content = {
                Popup {
                    typed.set(LocalKortexClipboard.current)
                    Box(Modifier.size(1.dp))
                }
            },
            contentClipboard = { clipboard },
        ) { shell ->
            assertTrue(shell.pumpOrFail(PUMP_MILLIS) { typed.get() != null }, "the Popup's content never composed")
            val popupClipboard = assertNotNull(typed.get(), "the Popup's content was handed no typed clipboard")
            // Pumped rather than awaited: a clipboard whose read hops onto the loop would wait on this very thread.
            val read = CoroutineScope(Dispatchers.Unconfined).future { popupClipboard.readText() }
            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { read.isDone },
                "the read never returned while the shell was pumped",
            )
            assertEquals(Ok(OTHER_CLIENTS), read.get(), "the Popup's typed clipboard did not reach the shell's")
        }
    }

    @Test
    fun `content's clipboard fails every call at once after its shell has closed`() {
        val typed = AtomicReference<KortexClipboard?>()
        withSpeckShell(content = { typed.set(LocalKortexClipboard.current) }) { }
        val clipboard = assertNotNull(typed.get(), "content was handed no typed clipboard")
        listOf<suspend () -> Any>(
            { clipboard.setText(COPIED) },
            { clipboard.clear() },
            { clipboard.readText() },
        ).forEach { call ->
            val result = CoroutineScope(Dispatchers.Unconfined).future { call() }
            val failure = try {
                result.get(CALL_BOUND_MILLIS, TimeUnit.MILLISECONDS)
                fail("a call on a closed shell's clipboard returned")
            } catch (_: TimeoutException) {
                fail("a call on a closed shell's clipboard was still waiting ${CALL_BOUND_MILLIS}ms in")
            } catch (thrown: ExecutionException) {
                thrown.cause
            }
            assertIs<IllegalStateException>(failure, "a call on a closed shell's clipboard failed some other way")
        }
    }

    /** [clipboard] as Compose's, running what it launches right where it is launched. */
    private fun composeClipboard(clipboard: TextClipboard) =
        ComposeClipboard(clipboard, CoroutineScope(Dispatchers.Unconfined))

    /** Polls [condition] until it holds or [CALL_BOUND_MILLIS] pass. */
    private fun awaitUntil(condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + CALL_BOUND_MILLIS * NANOS_PER_MILLI
        while (!condition()) {
            if (System.nanoTime() >= deadline) return false
            Thread.sleep(POLL_MILLIS)
        }
        return true
    }

    /**
     * Runs [block] against a shell showing one speck of [content], once it is placed: by default, with the shell's
     * own clipboard, or [contentClipboard]'s stand-in for it.
     */
    private fun withSpeckShell(
        content: @Composable TestSurface<Nothing>.() -> Unit,
        contentClipboard: (WaylandClipboard) -> TextClipboard = { it },
        block: (KortexShell) -> Unit,
    ) {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }
        display.use { wayland ->
            val application: @Composable KortexApplicationScope.() -> Unit = {
                Show(TestSurface(SPECK_NAMESPACE, content = content))
            }
            val shell = KortexShell
                .createApplication(wayland, contentClipboard = contentClipboard, content = application)
                .getOrElse { error -> fail("shell creation failed: $error") }
            shell.useOrFail { awaitPlaced(it); block(it) }
        }
    }

    /** A text entry that notes which thread asks it for its text. */
    private class ThreadRecordingText(private val reader: AtomicReference<Thread?>) : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.stringFlavor)

        override fun isDataFlavorSupported(flavor: DataFlavor?): Boolean = flavor == DataFlavor.stringFlavor

        override fun getTransferData(flavor: DataFlavor?): Any {
            reader.set(Thread.currentThread())
            return COPIED
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
        const val CALLS = 3
        const val PUMP_MILLIS = 2000L
        const val CALL_BOUND_MILLIS = 2000L
        const val POLL_MILLIS = 10L
        const val NANOS_PER_MILLI = 1_000_000L
        const val SPECK_NAMESPACE = "kortex-compose-clipboard"
    }
}
