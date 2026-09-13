package com.fromwau.kortex.wayland

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.errorOrNull
import com.fromwau.kortex.compose.LocalKortexSurface
import kotlinx.coroutines.delay

// Read by VirtualPointerCrashTest, which runs this in a child JVM: a throw escaping a real wl_pointer
// callback would otherwise end the JVM running the tests.
internal const val POINTER_PROBE_NAMESPACE = "kortex-crash-pointer-probe"
internal const val POINTER_PROBE_FAILURE = "a click handler threw"

/** Runs one clickable surface whose content throws when clicked, and reports how the run ended. */
object CrashedPointerProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val result = runSurfaces(
            SurfaceSpec(POINTER_PROBE_CONFIG, OutputTarget.CompositorChoice) { ThrowOnClick() },
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
private fun ThrowOnClick() {
    val handle = LocalKortexSurface.current
    // Ends the run on its own when no click ever lands, rather than waiting out runProbe's kill timeout.
    LaunchedEffect(Unit) {
        delay(NO_CLICK_TIMEOUT_MILLIS)
        handle.close()
    }
    Box(Modifier.fillMaxSize().clickable { error(POINTER_PROBE_FAILURE) })
}

// Comfortably past Screen.awaitGeometry's own 5s budget, so a real click still lands well within it.
private const val NO_CLICK_TIMEOUT_MILLIS = 8_000L

// Anchored so a virtual pointer can find and click it; unlike CrashedSurfaceProbe's speck, this one must
// be reachable, not avoided.
private val POINTER_PROBE_CONFIG = SurfaceConfig(
    layer = Layer.Overlay,
    anchor = setOf(Edge.Top, Edge.Left),
    width = 32.dp,
    height = 32.dp,
    exclusiveZone = ExclusiveZone.Yield,
).copy(namespace = POINTER_PROBE_NAMESPACE)
