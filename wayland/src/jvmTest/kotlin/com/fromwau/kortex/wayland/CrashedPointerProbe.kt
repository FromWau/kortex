package com.fromwau.kortex.wayland

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.fromwau.kortex.compose.LocalKortexSurface
import kotlinx.coroutines.delay

// Read by VirtualPointerCrashTest, which runs this in a child JVM: a throw escaping a real wl_pointer
// callback would otherwise end the JVM running the tests.
internal const val POINTER_PROBE_NAMESPACE = "kortex-crash-pointer-probe"
internal const val POINTER_PROBE_FAILURE = "a click handler threw"

// What Screen.pixelReaching waits for before a click: proof the probe has its first buffer on screen.
internal const val POINTER_PROBE_PIXEL = 0xFFFF00FF.toInt()

/** Shows one clickable surface whose content throws when clicked, and prints how it ended. */
object CrashedPointerProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        kortexApplication {
            val probe = rememberSurfaceState()
            when (val status = probe.status) {
                is SurfaceStatus.Ended -> LaunchedEffect(status) {
                    printEnding(status.result)
                    exitApplication()
                }

                else -> TestSurface(
                    POINTER_PROBE_NAMESPACE,
                    layer = Layer.Overlay,
                    anchor = setOf(Edge.Top, Edge.Left),
                    width = Length.Of(32.dp),
                    height = Length.Of(32.dp),
                    state = probe,
                ) { ThrowOnClick() }
            }
        }
    }
}

@Composable
private fun ThrowOnClick() {
    val surface = LocalKortexSurface.current
    // Ends the run on its own when no click ever lands, rather than waiting out runProbe's kill timeout.
    LaunchedEffect(Unit) {
        delay(NO_CLICK_TIMEOUT_MILLIS)
        surface.close()
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(POINTER_PROBE_PIXEL))
            .clickable { error(POINTER_PROBE_FAILURE) },
    )
}

// Past both of VirtualPointerCrashTest's sequential Screen waits, with room for the click to arrive.
private const val NO_CLICK_TIMEOUT_MILLIS = 2 * Screen.SETTLE_TIMEOUT_MILLIS + 2_000L
