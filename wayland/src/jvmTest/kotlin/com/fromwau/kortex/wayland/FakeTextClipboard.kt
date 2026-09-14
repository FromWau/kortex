package com.fromwau.kortex.wayland

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** A [TextClipboard] that keeps what reaches it and answers every read with [read]; any thread may call it. */
internal class FakeTextClipboard(
    private val read: suspend () -> Result<String, ClipboardError> = { Err(ClipboardError.NoSelection) },
) : TextClipboard {
    val setTexts = CopyOnWriteArrayList<String>()
    val clears = AtomicInteger()
    val reads = AtomicInteger()

    @Volatile
    override var ownedText: String? = null

    override suspend fun setText(text: String): EmptyResult<ClipboardError> {
        setTexts += text
        return Ok(Unit)
    }

    override suspend fun readText(): Result<String, ClipboardError> {
        reads.incrementAndGet()
        return read()
    }

    override suspend fun clear(): EmptyResult<ClipboardError> {
        clears.incrementAndGet()
        return Ok(Unit)
    }
}
