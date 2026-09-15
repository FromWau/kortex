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
import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.onError
import com.fromwau.kortex.wayland.Bar
import com.fromwau.kortex.wayland.ContextMenu
import com.fromwau.kortex.wayland.KeyboardInteractivity
import com.fromwau.kortex.wayland.KortexError
import com.fromwau.kortex.wayland.Monitor
import com.fromwau.kortex.wayland.Osd
import com.fromwau.kortex.wayland.Show
import com.fromwau.kortex.wayland.SurfaceError
import com.fromwau.kortex.wayland.kortexApplication
import com.fromwau.kortex.wayland.rememberMonitors
import java.nio.file.Path
import kotlin.math.roundToInt
import kotlin.system.exitProcess

fun main() {
    val crashLog = crashLogPath(System.getenv())
    kortexApplication {
        val monitors by rememberMonitors()
        for (monitor in monitors) key(monitor) {
            var ended by remember { mutableStateOf<SurfaceError<Nothing>?>(null) }
            when (val stopped = ended) {
                null -> Show(
                    DemoBar(
                        screen = monitor,
                        crashLog = crashLog,
                        onClose = { result ->
                            logIfCrashed(crashLog, result)
                            result.onError { failure -> ended = failure }
                        },
                    ),
                )

                else -> Show(
                    CrashPopup(
                        monitor = monitor,
                        stopped = stopped,
                        onClose = { result ->
                            logIfCrashed(crashLog, result)
                            ended = null
                        },
                    ),
                )
            }
        }
    }.onError { error ->
        System.err.println("kortex: $error")
        exitProcess(1)
    }
}

private val SurfaceError<Nothing>.crash: KortexError.SurfaceCrashed?
    get() = (this as? SurfaceError.Failed)?.error as? KortexError.SurfaceCrashed

/** Appends the crash a surface ended with to the crash log at [path], if it ended with one. */
private fun logIfCrashed(
    path: Path,
    result: EmptyResult<SurfaceError<Nothing>>,
) {
    result.onError { failure -> failure.crash?.let { crash -> logCrash(path, crash) } }
}

private fun logCrash(path: Path, crash: KortexError.SurfaceCrashed) {
    appendCrash(path, crash).onError { writeFailure ->
        System.err.println("kortex: surface crashed: $crash")
        System.err.println(crash.failure.cause.stackTraceToString())
        System.err.println("kortex: could not write the crash log: $writeFailure")
    }
}

/**
 * The bar on [screen]: a click counter, a text field, and a context menu for a right click on its background, whose
 * crash goes to [crashLog].
 */
private class DemoBar(
    private val screen: Monitor,
    private val crashLog: Path,
    onClose: (EmptyResult<SurfaceError<Nothing>>) -> Unit,
) : Bar<Nothing>(
    monitor = screen,
    thickness = 56.dp,
    // Static keyboard interactivity: a layer surface that changes it at runtime never gets the keyboard back to the
    // focused window (hyprwm/Hyprland#8293).
    keyboard = KeyboardInteractivity.OnDemand,
    namespace = "kortex-${screen.name}",
    onClose = onClose,
) {
    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    override fun invoke() {
        var clicks by remember { mutableStateOf(0) }
        var text by remember { mutableStateOf("") }
        var menuAt by remember { mutableStateOf<IntOffset?>(null) }

        MaterialTheme(colorScheme = darkColorScheme()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface),
            ) {
                // Under the row, not around it: a right click on the button or the field is theirs alone.
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
                                    // Bar-local is monitor-local only while nothing else reserves the Top
                                    // edge; a second bar above this one displaces the menu by its height.
                                    menuAt = IntOffset(x, this@DemoBar.size.height)
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
                        modifier = Modifier.width(320.dp).fillMaxHeight(),
                    )

                    Text(
                        text = "typed: $text",
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        menuAt?.let { at ->
            Show(
                BarMenu(
                    monitor = screen,
                    at = at,
                    onClose = { result ->
                        logIfCrashed(crashLog, result)
                        menuAt = null
                    },
                ),
            )
        }
    }
}

/** The bar's context menu, opened at [at] on [monitor]; picking an item closes it. */
private class BarMenu(
    monitor: Monitor,
    at: IntOffset,
    onClose: (EmptyResult<SurfaceError<Nothing>>) -> Unit,
) : ContextMenu<Nothing>(
    monitor = monitor,
    at = at,
    menuSize = IntSize(width = 160, height = 120),
    namespace = "kortex-menu",
    onClose = onClose,
) {
    @Composable
    override fun invoke() {
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

/** Stands in for a bar that stopped, saying why, until a click dismisses it. */
private class CrashPopup(
    monitor: Monitor,
    private val stopped: SurfaceError<Nothing>,
    onClose: (EmptyResult<SurfaceError<Nothing>>) -> Unit,
) : Osd<Nothing>(
    monitor = monitor,
    width = 480.dp,
    height = 120.dp,
    namespace = "kortex-stopped",
    onClose = onClose,
) {
    @Composable
    override fun invoke() {
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

private val MENU_ITEMS = listOf("Option 1", "Option 2", "Option 3")
