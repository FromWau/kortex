package com.fromwau.kortex.bar

import com.fromwau.kortex.wayland.KortexError
import com.fromwau.kortex.wayland.SeatDevice
import com.fromwau.kortex.wayland.WaylandInterface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the bar prints on the way out.
 *
 * The one failure here a person can do something about is a compositor with no `zwlr_layer_shell_v1`, where
 * kortex opens nothing at all and the way out is another compositor. Saying which protocol is missing is the
 * whole of that message's job.
 */
class ExitMessageTest {
    @Test
    fun `a compositor with no layer shell is told which protocol it lacks, in words rather than fields`() {
        val said = KortexError.MissingGlobal(WaylandInterface.LayerShell).saidPlainly()

        assertTrue(
            WaylandInterface.LayerShell.wireName in said,
            "the message does not name the protocol that is missing: $said",
        )
        assertFalse(
            said.startsWith("MissingGlobal("),
            "the message is the data class's own toString, which is written for a debugger: $said",
        )
    }

    @Test
    fun `a compositor with no layer shell is told where kortex does run, not only that it cannot run here`() {
        val said = KortexError.MissingGlobal(WaylandInterface.LayerShell).saidPlainly()

        assertTrue(
            RUNS_ON.any { it in said },
            "this is the one failure a person can act on, and the message names no compositor they could " +
                "act by moving to: $said",
        )
    }

    @Test
    fun `another missing global is named as itself, not reported as the layer shell`() {
        val said = KortexError.MissingGlobal(WaylandInterface.Shm).saidPlainly()

        assertTrue(
            WaylandInterface.Shm.wireName in said,
            "the message does not name the protocol that is missing: $said",
        )
        assertFalse(
            WaylandInterface.LayerShell.wireName in said,
            "a missing ${WaylandInterface.Shm.wireName} was reported as a missing layer shell: $said",
        )
    }

    @Test
    fun `a failure nobody can act on keeps the fields that say what happened`() {
        val error = KortexError.MissingSeatDevice(SeatDevice.Keyboard)

        assertEquals(error.toString(), error.saidPlainly(), "an unworded failure lost what it carried")
    }

    private companion object {
        /** Compositors that do implement the layer shell, of which the message must name at least one. */
        val RUNS_ON = listOf("Hyprland", "sway", "KWin")
    }
}
