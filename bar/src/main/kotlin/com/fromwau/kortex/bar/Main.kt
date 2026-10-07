package com.fromwau.kortex.bar

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.onError
import com.fromwau.kortex.bar.desktop.BusDesktop
import com.fromwau.kortex.bar.desktop.Desktop
import com.fromwau.kortex.bar.state.BarStateHolder
import com.fromwau.kortex.bar.system.ProcfsMetrics
import com.fromwau.kortex.bar.system.secondTicks
import com.fromwau.kortex.bar.ui.BarContent
import com.fromwau.kortex.bar.ui.TrayMenu
import com.fromwau.kortex.bar.ui.BarMenu
import com.fromwau.kortex.bar.ui.DemoWindow
import com.fromwau.kortex.bar.ui.Menu
import com.fromwau.kortex.bar.ui.NotificationPopup
import com.fromwau.kortex.bar.ui.colorsFor
import com.fromwau.kortex.notification.ServerInformation
import com.fromwau.kortex.wayland.Bar
import com.fromwau.kortex.wayland.Edge
import com.fromwau.kortex.wayland.KeyboardInteractivity
import com.fromwau.kortex.wayland.KortexError
import com.fromwau.kortex.wayland.Monitor
import com.fromwau.kortex.wayland.SurfaceStatus
import com.fromwau.kortex.wayland.WaylandInterface
import com.fromwau.kortex.wayland.kortexApplication
import com.fromwau.kortex.wayland.rememberMonitors
import com.fromwau.kortex.wayland.rememberSurfaceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.math.roundToInt
import kotlin.system.exitProcess

fun main() {
    val crashLog = crashLogPath(System.getenv())

    kortexApplication {
        val desktop = rememberDesktop()
        val monitors by rememberMonitors()

        // An empty list draws no bar and no crash popup, so this line is the only trace that it happened.
        LaunchedEffect(monitors) { System.err.println("kortex-bar: monitors ${monitors.map { it.name }}") }

        for ((index, monitor) in monitors.withIndex()) key(monitor) {
            val bar = rememberSurfaceState()
            var stopped by remember { mutableStateOf<Stopped>(Stopped.NotYet) }
            val status = bar.status

            LaunchedEffect(status) {
                if (status is SurfaceStatus.Ended) stopped = Stopped.With(status.result)
            }

            when (val ended = stopped) {
                // Dismissing puts the bar back under the same state, which reads Placing again as it is
                // replaced, so a crash is recoverable without restarting the shell.
                is Stopped.With -> CrashPopup(
                    monitor = monitor,
                    stopped = ended.ending,
                    crashLog = crashLog,
                    onDismiss = { stopped = Stopped.NotYet },
                )

                Stopped.NotYet -> Shell(
                    monitor = monitor,
                    desktop = desktop,
                    crashLog = crashLog,
                    popupHere = index == 0,
                )
            }
        }
    }.onError { failure ->
        System.err.println("kortex-bar: ${failure.saidPlainly()}")
        exitProcess(1)
    }
}

/**
 * One monitor's bar: the widgets, the notification popup, and a right click for the other surfaces.
 *
 * [popupHere] draws the notification popup on this monitor. One popup for the session rather than one per
 * monitor, since a notification is posted to the shell and not to a screen.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Shell(
    monitor: Monitor,
    desktop: Desktop,
    crashLog: java.nio.file.Path,
    popupHere: Boolean,
) {
    Bar(
        monitor = monitor,
        edge = Edge.Top,
        thickness = THICKNESS,
        keyboard = KeyboardInteractivity.None,
        namespace = "$NAMESPACE-${monitor.name}",
    ) {
        val holder = rememberBarStateHolder(monitor, desktop)
        val state by holder.state.collectAsState()
        var menu by remember { mutableStateOf<Menu>(Menu.Closed) }
        var window by remember { mutableStateOf(false) }
        val density = LocalDensity.current.density

        MaterialTheme(colorScheme = colorsFor(state.scheme)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(density) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.type != PointerEventType.Press) continue
                                if (event.button != PointerButton.Secondary) continue
                                // A widget that answers a right click of its own has consumed it.
                                if (event.changes.any { it.isConsumed }) continue
                                // This scope's Density is the buffer scale the offset was produced at, so
                                // it converts exactly into the logical space ContextMenu wants.
                                val x = (event.changes.first().position.x / density).roundToInt()
                                menu = Menu.OpenAt(IntOffset(x, THICKNESS.value.roundToInt()))
                            }
                        }
                    },
            ) {
                BarContent(state = state, onAction = holder::onAction)
            }
        }

        if (popupHere) {
            NotificationPopup(
                monitor = monitor,
                notifications = state.notifications,
                onAction = holder::onAction,
            )
        }

        state.trayMenu?.let { open ->
            TrayMenu(menu = open, below = THICKNESS, colors = colorsFor(state.scheme), onAction = holder::onAction)
        }

        when (val open = menu) {
            Menu.Closed -> Unit
            is Menu.OpenAt -> BarMenu(
                at = open.at,
                crashLog = crashLog,
                onWindow = {
                    menu = Menu.Closed
                    window = true
                },
                onClosed = { menu = Menu.Closed },
            )
        }

        if (window) DemoWindow(crashLog = crashLog, onClosed = { window = false })
    }
}

/**
 * The desktop's own services, on one bus connection for the whole shell.
 *
 * Application-wide rather than per bar, because the connection, the tray's registry and match rules, and
 * the notification name all belong to the process: a second bar is one more collector, not one more
 * connection, and two shells asking for `org.freedesktop.Notifications` is exactly the failure
 * `:notification` reports.
 */
@Composable
private fun rememberDesktop(): Desktop {
    val shell = remember {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        ShellServices(scope, BusDesktop(scope, identity = IDENTITY))
    }

    DisposableEffect(shell) {
        onDispose { shell.scope.cancel() }
    }

    return shell.desktop
}

/**
 * The state holder for the bar on [monitor], kept for as long as that bar is on screen.
 *
 * Its sources are collected on a scope of this composable's own rather than one from
 * `rememberCoroutineScope`, because that one runs on the thread that draws every surface, and this bar
 * reads four files on a timer. [desktop] is not on that scope: it outlives any one bar.
 */
@Composable
private fun rememberBarStateHolder(monitor: Monitor, desktop: Desktop): BarStateHolder {
    val sources = remember(monitor, desktop) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        Sources(
            scope = scope,
            holder = BarStateHolder(
                scope = scope,
                metrics = ProcfsMetrics(),
                desktop = desktop,
                monitor = monitor.name,
                clock = secondTicks(),
            ),
        )
    }

    DisposableEffect(sources) {
        onDispose { sources.scope.cancel() }
    }

    return sources.holder
}

/** The shell's own desktop services and the scope they live on, which ends with the application. */
private class ShellServices(
    val scope: CoroutineScope,
    val desktop: Desktop,
)

/** One bar's state holder and the scope its sources are collected on, which ends with the bar. */
private class Sources(
    val scope: CoroutineScope,
    val holder: BarStateHolder,
)

/**
 * What to print for [this] on the way out, where a person reads it off a terminal.
 *
 * Only the failures a person can do something about are worded. Everything else is a defect or a
 * connection that went away, and there the data class's own fields are what helps.
 */
internal fun KortexError.saidPlainly(): String = when (this) {
    is KortexError.MissingGlobal if global == WaylandInterface.LayerShell ->
        "this compositor does not support ${global.wireName}, which kortex needs for bars, docks and " +
            "every other layer surface. It runs on wlroots compositors, Hyprland and sway among them, " +
            "and on KWin."

    is KortexError.MissingGlobal -> "this compositor does not support ${global.wireName}, which kortex needs."
    else -> toString()
}

private val THICKNESS = 34.dp
private const val NAMESPACE = "kortex-bar"

/** What this shell tells an application about itself when it asks the notification server who it is. */
private val IDENTITY = ServerInformation(
    name = "kortex-bar",
    vendor = "fromwau",
    version = "0.1.0",
    // Only what the popup can actually honour: it draws plain text, inline images and no action buttons.
    capabilities = listOf("body", "icon-static", "persistence"),
)
