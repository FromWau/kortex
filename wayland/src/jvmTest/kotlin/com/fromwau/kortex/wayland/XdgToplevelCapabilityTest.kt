package com.fromwau.kortex.wayland

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a window reads out of `xdg_toplevel.wm_capabilities`, which says which of its asks the compositor will
 * honour at all: the decode at one end, and the three asks a [WindowState] offers at the other.
 *
 * Driven here rather than against a compositor, because none can be made to advertise less than it does.
 * Hyprland advertises all four, and a window that has been told nothing offers all three, so a live assertion
 * would read the same whether the value reached the window or not.
 *
 * Needs no compositor: a `wl_array` built here stands in for the one the event carries.
 */

class XdgToplevelCapabilityTest {
    @Test
    fun `a window honours every ask until the compositor has said which it takes`() {
        val listener = XdgToplevelListener(WIDTH, HEIGHT)

        assertEquals(
            XdgToplevelCapability.ALL, listener.capabilities,
            "a window with no wm_capabilities behind it must assume the compositor takes every ask, since one " +
                "too old to send the event ignores what it cannot do rather than failing it",
        )
    }

    @Test
    fun `a window takes the capabilities the event lists and drops the ones it leaves out`() {
        withCapabilities(XdgToplevelCapability.Maximize, XdgToplevelCapability.Fullscreen) { listener ->
            assertEquals(
                setOf(XdgToplevelCapability.Maximize, XdgToplevelCapability.Fullscreen), listener.capabilities,
                "the capabilities the event listed were not the ones the window kept",
            )
        }
    }

    @Test
    fun `a compositor that lists none leaves the window with none`() {
        withCapabilities { listener ->
            assertEquals(
                emptySet(), listener.capabilities,
                "an empty wm_capabilities must leave a window offering nothing, not everything",
            )
        }
    }

    @Test
    fun `a capability this protocol version does not declare is dropped rather than kept as a number`() {
        withCapabilities(wireValues = listOf(XdgToplevelCapability.Minimize.wireValue, UNDECLARED)) { listener ->
            assertEquals(
                setOf(XdgToplevelCapability.Minimize), listener.capabilities,
                "a capability from a newer protocol version was not dropped",
            )
        }
    }

    /**
     * The other end of the same value: three near-identical accessors over one set, which is where the wrong
     * capability gets named. A combination with a different answer for each, so no two can be swapped without
     * this failing.
     */
    @Test
    fun `each ask a window offers reads its own capability and no other`() {
        val state = WindowState()
        state.published.windowStates = WindowStates(
            capabilities = setOf(XdgToplevelCapability.Fullscreen, XdgToplevelCapability.WindowMenu),
        )

        assertFalse(state.canMaximize, "a window offered a maximize the compositor never said it would honour")
        assertTrue(state.canFullscreen, "a window hid a fullscreen the compositor said it would honour")
        assertFalse(state.canMinimize, "a window offered a minimize the compositor never said it would honour")
    }

    @Test
    fun `a window whose compositor has said nothing yet offers all three asks`() {
        val state = WindowState()

        assertTrue(state.canMaximize, "a window with no wm_capabilities behind it hid its maximize")
        assertTrue(state.canFullscreen, "a window with no wm_capabilities behind it hid its fullscreen")
        assertTrue(state.canMinimize, "a window with no wm_capabilities behind it hid its minimize")
    }

    private fun withCapabilities(
        vararg capabilities: XdgToplevelCapability,
        wireValues: List<Int> = capabilities.map(XdgToplevelCapability::wireValue),
        block: (XdgToplevelListener) -> Unit,
    ) {
        val listener = XdgToplevelListener(WIDTH, HEIGHT)
        Arena.ofConfined().use { arena ->
            listener.onWmCapabilities(MemorySegment.NULL, MemorySegment.NULL, arena.wlArrayOf(wireValues))
        }
        block(listener)
    }

    /**
     * [values] as the `struct wl_array { size_t size; size_t alloc; void *data; }` an upcall is handed, whose
     * own extent C says nothing about.
     */
    private fun Arena.wlArrayOf(values: List<Int>): MemorySegment {
        val data = allocate(JAVA_INT, values.size.toLong())
        values.forEachIndexed { index, value -> data.setAtIndex(JAVA_INT, index.toLong(), value) }
        val header = allocate(WL_ARRAY_BYTES)
        val bytes = values.size.toLong() * Int.SIZE_BYTES
        header.set(JAVA_LONG, SIZE_OFFSET, bytes)
        header.set(JAVA_LONG, ALLOC_OFFSET, bytes)
        header.set(ADDRESS, DATA_OFFSET, data)
        return header
    }

    private companion object {
        const val WIDTH = 640
        const val HEIGHT = 480

        /** Past every entry xdg_toplevel v7 declares, as a later version's own would be. */
        const val UNDECLARED = 99

        const val SIZE_OFFSET = 0L
        const val ALLOC_OFFSET = 8L
        const val DATA_OFFSET = 16L
        const val WL_ARRAY_BYTES = 24L
    }
}
