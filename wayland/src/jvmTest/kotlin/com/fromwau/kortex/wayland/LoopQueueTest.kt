package com.fromwau.kortex.wayland

import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals

/** One surface's [LoopQueue.drain] among a sibling's work and unowned work, with plain runnables and no compositor. */
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
}
