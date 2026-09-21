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
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.errorOrNull
import kotlinx.coroutines.delay

// Read by ContentFailureTest, which runs this in a child JVM: should a throw ever escape a libwayland
// callback again, the JDK ends this JVM rather than the one running the tests.
internal const val PROBE_MARKER = "KORTEX-PROBE"
internal const val PROBE_NAMESPACE = "kortex-crash-probe"
internal const val PROBE_FAILURE = "content threw while drawing a later frame"

/** Shows one surface whose content draws a frame and throws while drawing the next, and prints how it ended. */
object CrashedSurfaceProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        kortexApplication {
            val probe = rememberSurfaceState()
            when (val status = probe.status) {
                is SurfaceStatus.Ended -> LaunchedEffect(status) {
                    printEnding(status.result)
                    exitApplication()
                }

                else -> TestSurface(PROBE_NAMESPACE, state = probe) { ThrowOnLaterFrame() }
            }
        }
    }
}

/** Prints the crash [ending] carries, in the two lines the test reads. */
internal fun printEnding(ending: Result<SurfaceEnd, KortexError>) {
    val crash = ending.errorOrNull() as? KortexError.SurfaceCrashed ?: return
    System.err.println("$PROBE_MARKER hook crashed=${crash.namespace} cause=${crash.failure.cause.message}")
    System.err.println(
        "$PROBE_MARKER crashed=${crash.namespace} failure=${crash.failure::class.simpleName} " +
            "cause=${crash.failure.cause.message}",
    )
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
