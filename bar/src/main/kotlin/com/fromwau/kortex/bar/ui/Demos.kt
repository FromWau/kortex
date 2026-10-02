package com.fromwau.kortex.bar.ui

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
import com.fromwau.kortex.bar.logIfCrashed
import com.fromwau.kortex.bar.saidPlainly
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

/**
 * The toolkit's other surfaces, reachable from a right click on the bar.
 *
 * Not part of a bar anybody would ship: they are here because a popup, a window and a modal dialog are
 * most of what `:wayland` offers beyond the bar itself, and a repository with no example of opening one
 * leaves a reader to the tests. `:wayland`'s own suite is what proves they work.
 */
internal sealed interface Menu {
    data object Closed : Menu

    data class OpenAt(val at: IntOffset) : Menu
}

/** The bar's context menu, opened at [at] on the bar; picking an item closes it, and [onClosed] follows. */
@Composable
internal fun BarMenu(
    at: IntOffset,
    crashLog: Path,
    onWindow: () -> Unit,
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
                    modifier = Modifier.fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "Open a window",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().clickable { onWindow() }.padding(8.dp),
                    )

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
internal fun DemoWindow(
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
internal fun CloseDialog(
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

private val MENU_ITEMS = listOf("Option 1", "Option 2", "Option 3")

private const val WINDOW_TITLE = "kortex demo"
private const val DIALOG_TITLE = "Close this window?"
