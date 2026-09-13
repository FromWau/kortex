package com.fromwau.kortex.wayland

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.errorOrNull
import kotlinx.coroutines.delay

// Read by ContentFailureTest, which runs this in a child JVM: should a throw ever escape a libwayland
// callback again, the JDK ends this JVM rather than the one running the tests.
internal const val PROBE_MARKER = "KORTEX-PROBE"
internal const val PROBE_NAMESPACE = "kortex-crash-probe"
internal const val PROBE_FAILURE = "content threw while drawing a later frame"

/** Runs one surface whose content draws a frame and throws while drawing the next, and reports how the run ended. */
object CrashedSurfaceProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val result = runSurfaces(
            SurfaceSpec(PROBE_CONFIG, OutputTarget.CompositorChoice) { ThrowOnLaterFrame() },
            onCrashSurface = { crash ->
                System.err.println("$PROBE_MARKER hook crashed=${crash.namespace} cause=${crash.failure.cause.message}")
            },
        )
        val crash = result.errorOrNull() as? KortexError.SurfaceCrashed
        val kind = crash?.failure?.let { it::class.simpleName }
        val cause = crash?.failure?.cause?.message
        System.err.println("$PROBE_MARKER crashed=${crash?.namespace} failure=$kind cause=$cause")
    }
}

@Composable
private fun ThrowOnLaterFrame() {
    var broken by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(BREAK_AFTER_MILLIS)
        broken = true
    }
    // Read in composition, so the change recomposes and asks for a frame.
    val throwNow = broken
    Canvas(Modifier.fillMaxSize()) {
        if (throwNow) error(PROBE_FAILURE)
    }
}

private const val BREAK_AFTER_MILLIS = 200L

// A speck in the corner, where the pointer is least likely to be.
private val PROBE_CONFIG = SurfaceConfig(
    layer = Layer.Overlay,
    anchor = setOf(Edge.Bottom, Edge.Right),
    width = 8.dp,
    height = 8.dp,
    exclusiveZone = ExclusiveZone.Yield,
).copy(namespace = PROBE_NAMESPACE)
