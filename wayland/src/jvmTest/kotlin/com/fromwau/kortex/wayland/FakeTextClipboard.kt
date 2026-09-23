package com.fromwau.kortex.wayland

import androidx.compose.ui.graphics.ImageBitmap
import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * A [TextClipboard] that keeps what reaches it, answers every read with [read] and every set with [set]; any thread
 * may call it.
 */
internal class FakeTextClipboard(
    private val read: suspend () -> Result<String, ClipboardError> = { Err(ClipboardError.NoSelection) },
    private val set: suspend () -> EmptyResult<ClipboardError> = { Ok(Unit) },
) : TextClipboard {
    val setTexts = CopyOnWriteArrayList<String>()
    val clears = AtomicInteger()
    val reads = AtomicInteger()

    @Volatile
    override var ownedText: String? = null

    @Volatile
    override var hasText: Boolean = false

    override suspend fun setText(text: String): EmptyResult<ClipboardError> {
        setTexts += text
        return set()
    }

    override suspend fun readText(): Result<String, ClipboardError> {
        reads.incrementAndGet()
        return read()
    }

    override suspend fun clear(): EmptyResult<ClipboardError> {
        clears.incrementAndGet()
        return Ok(Unit)
    }

    override suspend fun setImage(image: ImageBitmap): EmptyResult<ClipboardError> =
        error("nothing this fake stands in for copies an image")

    override suspend fun readImage(): Result<ImageBitmap, ClipboardError> =
        error("nothing this fake stands in for pastes an image")
}
