package com.fromwau.kortex.wayland

import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable

/**
 * Compose's coroutine work for every surface one loop drives: any thread may dispatch it, and only the thread that
 * owns the connection runs it.
 *
 * Work arriving after the loop's owner has closed lands in a queue nobody drains; its `wake()` is a guarded no-op.
 */
internal class LoopQueue(private val wake: () -> Unit) : CoroutineDispatcher() {
    private val work = ConcurrentLinkedQueue<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        work += block
        wake()
    }

    fun runPass() {
        // Taken before any runs: content that keeps yielding dispatches itself again, and would hold the pass forever.
        val queued = generateSequence(work::poll).toList()
        queued.forEach(Runnable::run)
    }

    /** Runs everything queued, and whatever that work queues in turn, for a close that no further pass follows. */
    fun drain() {
        generateSequence(work::poll).forEach(Runnable::run)
    }
}
