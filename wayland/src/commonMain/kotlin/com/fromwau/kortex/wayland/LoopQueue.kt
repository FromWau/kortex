package com.fromwau.kortex.wayland

import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable

/**
 * Compose's coroutine work for every surface one loop drives. Any thread may [post]; only the thread that owns
 * the connection may run it.
 */
internal class LoopQueue(private val wake: () -> Unit) {
    private val work = ConcurrentLinkedQueue<Runnable>()

    fun post(task: Runnable) {
        work += task
        wake()
    }

    /** Runs what is queued now; what that work queues waits for the next pass, which its post has already woken. */
    fun runPass() {
        // Taken before any runs: content that keeps yielding re-posts itself and would hold the pass forever.
        val queued = generateSequence(work::poll).toList()
        queued.forEach(Runnable::run)
    }

    /** Runs everything queued, and whatever that work queues in turn, for a close that no further pass follows. */
    fun drain() {
        generateSequence(work::poll).forEach(Runnable::run)
    }
}

/** One surface's Compose dispatcher: every task goes to its loop's [LoopQueue] until [close]. */
internal class SceneDispatcher(private val loop: LoopQueue) : CoroutineDispatcher() {
    @Volatile
    private var closed = false

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        if (!closed) loop.post(block)
    }

    /** Call once the scene has closed: runs the loop's queue, so the scene finishes cancelling, then drops the rest. */
    fun close() {
        // Compose's recomposer leaves its process-wide snapshot observers only as its cancelled run loop resumes here.
        loop.drain()
        closed = true
    }
}
