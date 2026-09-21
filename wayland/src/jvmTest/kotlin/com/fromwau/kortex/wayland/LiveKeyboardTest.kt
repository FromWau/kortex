package com.fromwau.kortex.wayland

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A changed `keyboard` takes and gives back the surface's own `wl_keyboard`, so a surface that is no longer
 * interactive holds none.
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

    private companion object {
        const val NAMESPACE = "kortex-live-keyboard"
        const val PUMP_MILLIS = 4_000L
    }
}
