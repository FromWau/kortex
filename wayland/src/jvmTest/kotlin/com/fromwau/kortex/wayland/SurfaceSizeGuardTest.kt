package com.fromwau.kortex.wayland

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.errorOrNull
import com.fromwau.kern.result.getOrElse
import com.fromwau.kern.result.onSuccess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A size that rounds below one pixel on either axis is API misuse rather than an expected failure, so each call
 * that takes one refuses it where it is called, naming the call and the size it was given.
 *
 * A window and a dialog are refused before anything of theirs reaches the compositor. A popup is refused inside
 * its parent's content, so its parent, a speck in a corner, is placed first and taken down with the crash.
 */
class SurfaceSizeGuardTest {
    @Test
    fun `a window, a dialog and a popup each refuse a size below one pixel and say what they were given`() {
        assertApplicationRefuses("a window is drawn at a size of at least one pixel on each axis, not $NONE by $TALL") {
            Window(title = TITLE, width = NONE, height = TALL) { Grey() }
        }

        assertApplicationRefuses("a dialog is drawn at a size of at least one pixel on each axis, not $WIDE by $NONE") {
            Dialog(title = TITLE, width = WIDE, height = NONE) { Grey() }
        }

        assertPopupRefuses("a popup is drawn at a size of at least one pixel on each axis, not $NONE by $TALL")
    }

    /**
     * Fails unless an application of [content] never starts, ending as a crash whose message is [message].
     */
    private fun assertApplicationRefuses(
        message: String,
        content: @Composable KortexApplicationScope.() -> Unit,
    ) {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use {
            var error: KortexError? = null
            capturingStderr {
                error = KortexShell
                    .createApplication(display, content = content)
                    .onSuccess { it.close() }
                    .errorOrNull()
            }

            val crash = assertIs<KortexError.ApplicationCrashed>(error, "a call with no size on one axis was placed")
            assertEquals(message, crash.cause.message, "the crash did not say what size the call needs")
        }
    }

    /** Fails unless a popup of no width ends the surface it opens over, as a crash whose message is [message]. */
    private fun assertPopupRefuses(message: String) {
        val speck = SurfaceState()
        val content: @Composable KortexApplicationScope.() -> Unit = {
            TestSurface(NAMESPACE, state = speck) {
                Popup(at = AT, width = NONE, height = TALL) { Grey() }
            }
        }

        onApplication(content) { shell ->
            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { speck.hasEnded },
                "a popup with no width left the surface it opened over running",
            )
            val crash = speck.crashOrFail("a popup with no width did not end its surface as a crash")
            assertEquals(message, crash.failure.cause.message, "the crash did not say what size a popup needs")
        }
    }

    private companion object {
        const val TITLE = "kortex size guard"
        const val NAMESPACE = "kortex-size-guard"

        val NONE = 0.dp
        val WIDE = 400.dp
        val TALL = 120.dp

        val AT = IntOffset(4, 4)

        const val PUMP_MILLIS = 4_000L
    }
}
