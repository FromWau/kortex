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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.errorOrNull
import com.fromwau.kern.result.onError
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

fun main() {
    val crashLog = crashLogPath(System.getenv())
    kortexApplication {
        val monitors by rememberMonitors()
        for (monitor in monitors) key(monitor) {
            val bar = rememberSurfaceState()
            var stopped by remember { mutableStateOf<Stopped>(Stopped.NotYet) }
            val status = bar.status
            LaunchedEffect(status) { if (status is SurfaceStatus.Ended) stopped = Stopped.With(status.result) }

            when (val ended = stopped) {
                // Dismissing puts the bar back under the same state, which reads Placing again as it is replaced.
                Stopped.NotYet -> DemoBar(screen = monitor, crashLog = crashLog, state = bar)

                is Stopped.With -> CrashPopup(
                    monitor = monitor,
                    stopped = ended.ending,
                    crashLog = crashLog,
                    onDismiss = { stopped = Stopped.NotYet },
                )
            }
        }
    }.onError { error ->
        System.err.println("kortex: $error")
        exitProcess(1)
    }
}

/** Whether a monitor's bar has stopped, and what it stopped with. */
private sealed interface Stopped {
    data object NotYet : Stopped

    data class With(val ending: Result<SurfaceEnd, KortexError>) : Stopped
}

private val Result<SurfaceEnd, KortexError>.crash: KortexError.SurfaceCrashed?
    get() = errorOrNull() as? KortexError.SurfaceCrashed

/** Appends the crash a surface ended with to the crash log at [path], if it ended with one. */
private suspend fun logIfCrashed(
    path: Path,
    ending: Result<SurfaceEnd, KortexError>,
) {
    ending.crash?.let { crash -> logCrash(path, crash) }
}

private suspend fun logCrash(path: Path, crash: KortexError.SurfaceCrashed) {
    // NonCancellable: this runs inside the ended surface's own LaunchedEffect, and an outer surface (or the whole
    // application) can leave composition and cancel it before the write lands.
    withContext(NonCancellable + Dispatchers.IO) { appendCrash(path, crash) }.onError { writeFailure ->
        System.err.println("kortex: surface crashed: $crash")
        System.err.println(crash.failure.cause.stackTraceToString())
        System.err.println("kortex: could not write the crash log: $writeFailure")
    }
}

/**
 * The bar on [screen]: a click counter, a text field, a button that makes the bar taller and shorter, a button that
 * opens a window, and a context menu for a right click on its background. A crash goes to [crashLog].
 *
 * The count and the typed text are the bar's own content state, so both survive the resize, and the readout beside
 * them is the size the compositor gave the bar.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun DemoBar(
    screen: Monitor,
    crashLog: Path,
    state: SurfaceState,
) {
    var tall by remember { mutableStateOf(false) }

    Bar(
        monitor = screen,
        thickness = if (tall) TALL_THICKNESS else THICKNESS,
        keyboard = KeyboardInteractivity.OnDemand,
        namespace = "kortex-${screen.name}",
        state = state,
    ) {
        val bar = this
        var clicks by remember { mutableStateOf(0) }
        var text by remember { mutableStateOf("") }
        var menu by remember { mutableStateOf<Menu>(Menu.Closed) }
        var window by remember { mutableStateOf(false) }

        MaterialTheme(colorScheme = darkColorScheme()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface),
            ) {
                // Under the row, not around it: a right click on a button or the field is theirs alone.
                Box(
                    Modifier
                        .matchParentSize()
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (event.type != PointerEventType.Press) continue
                                    if (event.button != PointerButton.Secondary) continue
                                    // This scope's Density is the very buffer scale the offset was produced
                                    // at, so it converts exactly into the logical space ContextMenu wants.
                                    val x = (event.changes.first().position.x / density).roundToInt()
                                    menu = Menu.OpenAt(IntOffset(x, bar.size.height))
                                }
                            }
                        },
                )

                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(onClick = { clicks++ }) {
                        Text("clicked $clicks")
                    }

                    TextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        modifier = Modifier.width(240.dp).fillMaxHeight(),
                    )

                    Button(onClick = { tall = !tall }) {
                        Text(if (tall) "shrink me" else "grow me")
                    }

                    Button(onClick = { window = !window }) {
                        Text(if (window) "hide window" else "show window")
                    }

                    Text(
                        text = "typed: $text",
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    Text(
                        text = "size: ${bar.size.width} by ${bar.size.height}",
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        when (val open = menu) {
            Menu.Closed -> Unit
            is Menu.OpenAt -> BarMenu(
                at = open.at,
                crashLog = crashLog,
                onClosed = { menu = Menu.Closed },
            )
        }

        if (window) DemoWindow(crashLog = crashLog, onClosed = { window = false })
    }
}

/** Whether the bar's context menu is open, and where it opened. */
private sealed interface Menu {
    data object Closed : Menu

    data class OpenAt(val at: IntOffset) : Menu
}

/** The bar's context menu, opened at [at] on the bar; picking an item closes it, and [onClosed] follows. */
@Composable
private fun BarMenu(
    at: IntOffset,
    crashLog: Path,
    onClosed: () -> Unit,
) {
    val menu = rememberSurfaceState()
    when (val status = menu.status) {
        is SurfaceStatus.Ended -> LaunchedEffect(status) {
            logIfCrashed(crashLog, status.result)
            onClosed()
        }

        else -> ContextMenu(
            at = at,
            menuSize = IntSize(width = 160, height = 120),
            state = menu,
        ) {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Column(
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    MENU_ITEMS.forEach { item ->
                        Text(
                            text = item,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { close() }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The window the bar opens: a click counter, a readout of the size the compositor gave the window, and a button
 * that closes it. A crash goes to [crashLog], and [onClosed] follows however the window ends.
 *
 * The count is the window's own content state, so it stands through every move, resize and re-tile the compositor
 * makes. A close the compositor asks for opens [CloseDialog] on the window instead of closing it.
 */
@Composable
private fun DemoWindow(
    crashLog: Path,
    onClosed: () -> Unit,
) {
    val state = rememberWindowState()
    when (val status = state.status) {
        is WindowStatus.Ended -> LaunchedEffect(status) {
            logIfCrashed(crashLog, status.result)
            onClosed()
        }

        else -> Window(title = WINDOW_TITLE, state = state) {
            val window = this
            var clicks by remember { mutableStateOf(0) }

            MaterialTheme(colorScheme = darkColorScheme()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(onClick = { clicks++ }) {
                        Text("clicked $clicks")
                    }

                    Text(
                        text = "size: ${window.size.width} by ${window.size.height}",
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    Button(onClick = onClosed) {
                        Text("close me")
                    }
                }
            }

            if (state.closeRequested) {
                CloseDialog(
                    crashLog = crashLog,
                    onKeep = { state.declineClose() },
                    onClose = onClosed,
                )
            }
        }
    }
}

/**
 * The dialog the window shows when the compositor asks for that window to close: [onClose] takes the window away,
 * [onKeep] keeps it and lets the next ask through. A crash goes to [crashLog].
 */
@Composable
private fun CloseDialog(
    crashLog: Path,
    onKeep: () -> Unit,
    onClose: () -> Unit,
) {
    val state = rememberWindowState()
    when (val status = state.status) {
        is WindowStatus.Ended -> LaunchedEffect(status) {
            logIfCrashed(crashLog, status.result)
            onKeep()
        }

        else -> Dialog(title = DIALOG_TITLE, state = state) {
            // The compositor asking the dialog itself to close is an answer too: the window stays.
            val dismissed = state.closeRequested
            LaunchedEffect(dismissed) { if (dismissed) onKeep() }

            MaterialTheme(colorScheme = darkColorScheme()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "The compositor asked for the window to close.",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = onClose) {
                            Text("close it")
                        }

                        Button(onClick = onKeep) {
                            Text("keep it")
                        }
                    }
                }
            }
        }
    }
}

/** Stands in for a bar that stopped, saying why; [onDismiss] follows however the popup itself ends. */
@Composable
private fun CrashPopup(
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

private val THICKNESS = 56.dp
private val TALL_THICKNESS = 96.dp

private const val WINDOW_TITLE = "kortex demo"
private const val DIALOG_TITLE = "Close this window?"

private val MENU_ITEMS = listOf("Option 1", "Option 2", "Option 3")
