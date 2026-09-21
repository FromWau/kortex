package com.fromwau.kortex.wayland

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A changed `keyboard` takes and gives back the surface's own `wl_keyboard`, so a surface that is no longer
 * interactive holds none, and the content on it is no longer focused either.
 *
 * Desktop-free: Hyprland hands a surface whose keyboard is anything but [KeyboardInteractivity.None] the focus as it
 * maps, taking it from whatever the user is typing in.
 */
class LiveKeyboardTest {
    @Test
    fun `a surface that drops to None gives its keyboard back`() {
        val keyboard = mutableStateOf(KeyboardInteractivity.OnDemand)
        val content: @Composable KortexApplicationScope.() -> Unit = {
            TestSurface(NAMESPACE, keyboard = keyboard.value)
        }

        onApplication(content) { shell ->
            awaitPlaced(shell)
            val surface = shell.shownSurfaces.single()
            assertNotNull(surface.keyboardInput, "a surface asking for OnDemand was placed holding no keyboard")

            keyboard.value = KeyboardInteractivity.None

            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { surface.keyboardInput == null },
                "a surface that dropped to None still holds a keyboard",
            )
        }
    }

    @Test
    fun `a surface that takes interactivity takes a keyboard with it`() {
        val keyboard = mutableStateOf(KeyboardInteractivity.None)
        val content: @Composable KortexApplicationScope.() -> Unit = {
            TestSurface(NAMESPACE, keyboard = keyboard.value)
        }

        onApplication(content) { shell ->
            awaitPlaced(shell)
            val surface = shell.shownSurfaces.single()
            assertNull(surface.keyboardInput, "a surface asking for None was placed holding a keyboard")

            keyboard.value = KeyboardInteractivity.OnDemand

            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { surface.keyboardInput != null },
                "a surface that became interactive took no keyboard",
            )
        }
    }

    @Test
    fun `a surface that drops to None stops drawing`() {
        val keyboard = mutableStateOf(KeyboardInteractivity.OnDemand)
        val focused = AtomicBoolean(false)
        val content: @Composable KortexApplicationScope.() -> Unit = {
            TestSurface(NAMESPACE, width = FIELD_SIDE.dp, height = FIELD_SIDE.dp, keyboard = keyboard.value) {
                FocusedTextField(focused)
            }
        }

        onApplication(content) { shell ->
            awaitPlaced(shell)
            val surface = shell.shownSurfaces.single()
            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { focused.get() },
                "the text field on the interactive surface never took focus",
            )
            val beforeBlinking = surface.renders
            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { surface.renders > beforeBlinking },
                "the caret of the focused text field never drew a frame",
            )

            keyboard.value = KeyboardInteractivity.None

            assertTrue(
                shell.pumpOrFail(PUMP_MILLIS) { surface.keyboardInput == null },
                "a surface that dropped to None still holds a keyboard",
            )
            // The caret going is a frame of its own, so the surface is idle only once that frame is drawn.
            shell.pumpOrFail(SETTLE_MILLIS)

            val settled = surface.renders
            shell.pumpOrFail(IDLE_MILLIS)
            assertEquals(settled, surface.renders, "a surface that can take no key kept drawing")
        }
    }

    /** A text field that takes focus in its composition, whose caret blinks while its surface has the keyboard. */
    @Composable
    private fun FocusedTextField(focused: AtomicBoolean) {
        var text by remember { mutableStateOf("") }
        val requester = remember { FocusRequester() }
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier
                .focusRequester(requester)
                .onFocusChanged { focused.set(it.isFocused) },
        )
        LaunchedEffect(Unit) { requester.requestFocus() }
    }

    private companion object {
        const val NAMESPACE = "kortex-live-keyboard"
        const val PUMP_MILLIS = 4_000L
        const val SETTLE_MILLIS = 500L

        // The caret blinks about twice a second, so a window this long holds several of its frames.
        const val IDLE_MILLIS = 2_000L
        const val FIELD_SIDE = 200
    }
}
