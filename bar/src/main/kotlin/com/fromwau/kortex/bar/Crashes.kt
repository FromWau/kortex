package com.fromwau.kortex.bar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.errorOrNull
import com.fromwau.kern.result.getOrNull
import com.fromwau.kern.result.onError
import com.fromwau.kortex.theme.rememberFileTheme
import com.fromwau.kortex.wayland.Bar
import com.fromwau.kortex.wayland.ContextMenu
import com.fromwau.kortex.wayland.Dialog
import com.fromwau.kortex.wayland.KeyboardInteractivity
import com.fromwau.kortex.wayland.KortexError
import com.fromwau.kortex.wayland.Monitor
import com.fromwau.kortex.wayland.Osd
import com.fromwau.kortex.wayland.SurfaceEnd
import com.fromwau.kortex.wayland.SurfaceState
import com.fromwau.kortex.wayland.SurfaceStatus
import com.fromwau.kortex.wayland.WaylandInterface
import com.fromwau.kortex.wayland.Window
import com.fromwau.kortex.wayland.WindowStatus
import com.fromwau.kortex.wayland.kortexApplication
import com.fromwau.kortex.wayland.rememberMonitors
import com.fromwau.kortex.wayland.rememberSurfaceState
import com.fromwau.kortex.wayland.rememberWindowState
import java.nio.file.Path
import kotlin.math.roundToInt
import kotlin.system.exitProcess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path as KPath

/** What a surface that ended with a crash gets written to, and what the person is shown about it. */
/** Whether a monitor's bar has stopped, and what it stopped with. */
internal sealed interface Stopped {
    data object NotYet : Stopped

    data class With(val ending: Result<SurfaceEnd, KortexError>) : Stopped
}

private val Result<SurfaceEnd, KortexError>.crash: KortexError.SurfaceCrashed?
    get() = errorOrNull() as? KortexError.SurfaceCrashed

/** Appends the crash a surface ended with to the crash log at [path], if it ended with one. */
internal suspend fun logIfCrashed(
    path: Path,
    ending: Result<SurfaceEnd, KortexError>,
) {
    ending.crash?.let { crash -> logCrash(path, crash) }
}

private suspend fun logCrash(path: Path, crash: KortexError.SurfaceCrashed) {
    // NonCancellable: this runs inside the ended surface's own LaunchedEffect, and an outer surface (or the whole
    // application) can leave composition and cancel it before the write lands.
    withContext(NonCancellable + Dispatchers.IO) {
        appendCrash(path, crash)
    }.onError { writeFailure ->
        System.err.println("kortex: surface crashed: $crash")
        System.err.println(crash.failure.cause.stackTraceToString())
        System.err.println("kortex: could not write the crash log: $writeFailure")
    }
}

@Composable
internal fun CrashPopup(
    monitor: Monitor,
    stopped: Result<SurfaceEnd, KortexError>,
    crashLog: Path,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(stopped) { logIfCrashed(crashLog, stopped) }

    val popup = rememberSurfaceState()
    when (val status = popup.status) {
        is SurfaceStatus.Ended -> LaunchedEffect(status) {
            logIfCrashed(crashLog, status.result)
            onDismiss()
        }

        else -> Osd(
            monitor = monitor,
            width = 480.dp,
            height = 120.dp,
            namespace = "kortex-stopped",
            state = popup,
        ) {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .clickable { close() }
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "The bar stopped. Click to bring it back.",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )

                    Text(
                        text = stopped.crash?.failure?.cause?.toString() ?: "$stopped",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
