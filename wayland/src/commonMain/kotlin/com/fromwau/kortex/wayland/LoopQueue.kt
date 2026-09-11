package com.fromwau.kortex.wayland

import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable

/** Rides in one surface's frame context, so [LoopQueue] can tell that surface's work from its siblings' by instance. */
internal class SurfaceWork : AbstractCoroutineContextElement(SurfaceWork) {
    companion object Key : CoroutineContext.Key<SurfaceWork>
}

/**
 * Compose's coroutine work for every surface one loop drives: any thread may dispatch it, and only the thread that
 * owns the connection runs it. Each piece keeps the [SurfaceWork] it was dispatched under, so a closing surface can
 * run its own and leave the rest where it is.
 *
 * Work arriving after the loop's owner has closed lands in a queue nobody drains; its `wake()` is a guarded no-op.
 */
internal class LoopQueue(private val wake: () -> Unit) : CoroutineDispatcher() {
    private val work = ConcurrentLinkedQueue<Queued>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        work += Queued(context[SurfaceWork], block)
        wake()
    }

    fun runPass() {
        // Taken before any runs: content that keeps yielding dispatches itself again, and would hold the pass forever.
        val queued = generateSequence(work::poll).toList()
        queued.forEach(Runnable::run)
    }

    /** Runs every surface's work, and whatever it queues in turn, for the shell's close, which no pass follows. */
    fun drain() {
        generateSequence(work::poll).forEach(Runnable::run)
    }

    /**
     * Runs [owner]'s work in queue order, and whatever that work queues in turn, until none of it is left.
     * Every other surface's work stays queued, in its order, for the next pass.
     */
    fun drain(owner: SurfaceWork) {
        generateSequence { pollFirst(owner) }.forEach(Runnable::run)
    }

    private fun pollFirst(owner: SurfaceWork): Queued? =
        work.firstOrNull { it.owner === owner }?.also { work.remove(it) }

    private class Queued(val owner: SurfaceWork?, block: Runnable) : Runnable by block
}
