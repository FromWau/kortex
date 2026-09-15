package com.fromwau.kortex.wayland

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.IntSize
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The monitors an application lists, and surfaces put on one of them. */
class MonitorTest {
    @Test
    fun `rememberMonitors() lists every connected monitor, named and sized as hyprctl reports it`() {
        val listed = AtomicReference<List<Monitor>>(emptyList())
        val content: @Composable KortexApplicationScope.() -> Unit = {
            val monitors by rememberMonitors()
            SideEffect { listed.set(monitors) }
        }

        onApplication(content) { shell ->
            assertTrue(shell.pumpOrFail(PUMP_MILLIS) { listed.get().isNotEmpty() }, "rememberMonitors() listed nothing")
            assertEquals(
                Hyprctl.monitors().associate { it.name to IntSize(it.width, it.height) },
                listed.get().associate { it.name to IntSize(it.geometry.width, it.geometry.height) },
                "rememberMonitors() did not list the monitors hyprctl reports, by name and mode size",
            )
        }
    }

    private companion object {
        const val PUMP_MILLIS = 4_000L
    }
}
