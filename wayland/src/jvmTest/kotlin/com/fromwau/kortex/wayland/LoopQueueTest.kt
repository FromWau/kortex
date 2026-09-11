package com.fromwau.kortex.wayland

import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals

/** What [LoopQueue]'s drains run and where they stop, with plain runnables and no compositor. */
class LoopQueueTest {
    private val ran = mutableListOf<String>()
    private val queue = LoopQueue(wake = {})
    private val closing = SurfaceWork()
    private val sibling = SurfaceWork()

    @Test
    fun `draining one surface's work runs it in queue order, with what it queues in turn, and nothing else`() {
        queueMixedWork()

        queue.drain(closing)

        assertEquals(
            listOf("closing 1", "closing 2", "closing 3"),
            ran,
            "draining the closing surface's work did not run exactly that work, in queue order",
        )
    }

    @Test
    fun `draining one surface's work leaves every other entry queued in its order for the next pass`() {
        queueMixedWork()
        queue.drain(closing)
        ran.clear()

        queue.runPass()

        assertEquals(
            listOf("sibling 1", "unowned", "sibling 2"),
            ran,
            "the work left behind by draining one surface did not reach the next pass in its order",
        )
    }

    @Test
    fun `draining one surface's work runs a chain shorter than the bound to its end`() {
        val chain = chain(closing, links = SHORT_CHAIN)

        queue.drain(closing)

        assertEquals(SHORT_CHAIN, chain.runs, "draining one surface's work stopped before its chain ended")
    }

    @Test
    fun `draining one surface's work stops after the bound's rounds and leaves the rest to the next pass`() {
        val chains = runawayChains(closing, closing)

        queue.drain(closing)
        assertEquals(
            listOf(LoopQueue.DRAIN_BOUND_ROUNDS, LoopQueue.DRAIN_BOUND_ROUNDS),
            chains.map { it.runs },
            "draining one surface's work did not run each of its chains for exactly the bound's rounds",
        )

        queue.runPass()
        assertEquals(
            listOf(LoopQueue.DRAIN_BOUND_ROUNDS + 1, LoopQueue.DRAIN_BOUND_ROUNDS + 1),
            chains.map { it.runs },
            "what a bounded drain of one surface's work left queued did not run in the next pass",
        )
    }

    @Test
    fun `draining the whole queue runs a chain shorter than the bound to its end`() {
        val chain = chain(closing, links = SHORT_CHAIN)

        queue.drain()

        assertEquals(SHORT_CHAIN, chain.runs, "draining the whole queue stopped before its chain ended")
    }

    @Test
    fun `draining the whole queue stops after the bound's rounds and leaves the rest to the next pass`() {
        val chains = runawayChains(closing, sibling)

        queue.drain()
        assertEquals(
            listOf(LoopQueue.DRAIN_BOUND_ROUNDS, LoopQueue.DRAIN_BOUND_ROUNDS),
            chains.map { it.runs },
            "draining the whole queue did not run each chain for exactly the bound's rounds",
        )

        queue.runPass()
        assertEquals(
            listOf(LoopQueue.DRAIN_BOUND_ROUNDS + 1, LoopQueue.DRAIN_BOUND_ROUNDS + 1),
            chains.map { it.runs },
            "what a bounded drain of the whole queue left queued did not run in the next pass",
        )
    }

    private fun queueMixedWork() {
        queue.dispatch(closing, record("closing 1"))
        queue.dispatch(sibling, record("sibling 1"))
        queue.dispatch(EmptyCoroutineContext, record("unowned"))
        queue.dispatch(closing, record("closing 2") { queue.dispatch(closing, record("closing 3")) })
        queue.dispatch(sibling, record("sibling 2"))
    }

    private fun record(name: String, then: () -> Unit = {}): Runnable = Runnable {
        ran += name
        then()
    }

    private fun chain(owner: CoroutineContext, links: Int): Chain =
        Chain(owner, links).also { queue.dispatch(owner, it) }

    /** A chain far past the bound per owner: with two, a drain that counted runs, not rounds, stops each at half. */
    private fun runawayChains(vararg owners: CoroutineContext): List<Chain> =
        owners.map { chain(it, links = RUNAWAY_CHAIN) }

    /** Queues itself again under [owner] each time it runs, until it has run [links] times. */
    private inner class Chain(private val owner: CoroutineContext, private val links: Int) : Runnable {
        var runs = 0
            private set

        override fun run() {
            runs++
            if (runs < links) queue.dispatch(owner, this)
        }
    }

    private companion object {
        const val SHORT_CHAIN = LoopQueue.DRAIN_BOUND_ROUNDS - 1

        // Far past the bound yet finite, so a drain that ignores the bound fails the count instead of hanging.
        const val RUNAWAY_CHAIN = 100 * LoopQueue.DRAIN_BOUND_ROUNDS
    }
}
