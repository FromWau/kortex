package com.fromwau.kortex.wayland

import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable

/** Rides in one surface's frame context, so [LoopQueue] can tell that surface's work from its siblings' by instance. */
internal class SurfaceWork : AbstractCoroutineContextElement(SurfaceWork) {
    // Set on the loop thread, and read from whichever thread that surface's content dispatches on.
    @Volatile
    var closed: Boolean = false
        private set

    /** After this, work dispatched under this owner is dropped rather than queued or run. */
    fun close() {
        closed = true
    }

    companion object Key : CoroutineContext.Key<SurfaceWork>
}

/**
 * Compose's coroutine work for every surface one loop drives: any thread may dispatch it, and only the thread that
 * owns the connection runs it. Each piece keeps the [SurfaceWork] it was dispatched under, so a closing surface can
 * run its own and leave the rest where it is.
 *
 * Work runs in rounds, each one what was queued as it began, so work that keeps queuing itself waits for the next
 * round. A pass runs one round; a drain runs several, up to [DRAIN_BOUND_ROUNDS]. Work of an owner that has closed
 * is neither taken nor run, whether it was dispatched before the close or after it.
 *
 * Once the loop's owner has closed, no pass follows: what its last drain left, and work arriving after, stays in a
 * queue nobody drains, and that work's `wake()` is a guarded no-op.
 */
internal class LoopQueue(private val wake: () -> Unit) : CoroutineDispatcher() {
    private val work = ConcurrentLinkedQueue<Queued>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        val owner = context[SurfaceWork]
        if (owner?.closed == true) return
        work += Queued(owner, block)
        wake()
    }

    fun runPass() {
        // Taken before any runs: content that keeps yielding dispatches itself again, and would hold the pass forever.
        pollAll().forEach(Runnable::run)
    }

    /**
     * Runs every surface's work for the shell's close, which no pass follows: rounds until one finds nothing, or
     * until [DRAIN_BOUND_ROUNDS] have run.
     */
    fun drain() {
        runRounds { pollAll() }
    }

    /**
     * Runs [owner]'s work in queue order: rounds until one finds none of it, or until [DRAIN_BOUND_ROUNDS] have run.
     * Every other surface's work, and whatever of [owner]'s is left, stays queued in its order for the next pass.
     */
    fun drain(owner: SurfaceWork) {
        runRounds { pollAll(owner) }
    }

    private fun runRounds(nextRound: () -> List<Runnable>) {
        repeat(DRAIN_BOUND_ROUNDS) {
            val round = nextRound()
            if (round.isEmpty()) return
            round.forEach(Runnable::run)
        }
    }

    private fun pollAll(): List<Queued> = generateSequence(work::poll).toList()

    private fun pollAll(owner: SurfaceWork): List<Queued> = work
        .filter { it.owner === owner }
        .onEach { work.remove(it) }

    private class Queued(val owner: SurfaceWork?, private val block: Runnable) : Runnable {
        // Its owner can close between the dispatch and this, and an ended surface's content never runs again.
        override fun run() {
            if (owner?.closed == true) return
            block.run()
        }
    }

    companion object {
        // Thirty-two times the two rounds the longest measured close took, leaving a scene's cancellation wide room.
        const val DRAIN_BOUND_ROUNDS = 64
    }
}
