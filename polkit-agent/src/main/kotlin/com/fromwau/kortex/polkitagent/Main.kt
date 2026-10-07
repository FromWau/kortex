package com.fromwau.kortex.polkitagent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.errorOrNull
import com.fromwau.kern.result.getOrNull
import com.fromwau.kern.result.onError
import com.fromwau.kortex.dbus.SystemBus
import com.fromwau.kortex.polkit.PolkitAgent
import com.fromwau.kortex.polkit.PolkitError
import com.fromwau.kortex.polkit.PolkitRequest
import com.fromwau.kortex.theme.rememberFileTheme
import com.fromwau.kortex.wayland.Edge
import com.fromwau.kortex.wayland.ExclusiveZone
import com.fromwau.kortex.wayland.KeyboardInteractivity
import com.fromwau.kortex.wayland.Layer
import com.fromwau.kortex.wayland.LayerSurface
import com.fromwau.kortex.wayland.Length
import com.fromwau.kortex.wayland.Monitor
import com.fromwau.kortex.wayland.SurfaceStatus
import com.fromwau.kortex.wayland.kortexApplication
import com.fromwau.kortex.wayland.rememberMonitors
import com.fromwau.kortex.wayland.rememberSurfaceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.io.files.Path
import kotlin.system.exitProcess

/**
 * This session's polkit agent, as a process of its own so the password lives where nothing else does.
 *
 * Nothing is on screen until polkitd asks for a password. Then every monitor dims and the first one shows the
 * prompt, holding the keyboard until it is answered or cancelled. Requests that arrive meanwhile wait their turn.
 */
fun main() {
    kortexApplication {
        val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
        DisposableEffect(scope) {
            onDispose { scope.cancel() }
        }

        val served by remember { PolkitAgent.serve(SystemBus(scope), scope) }.collectAsState()
        val monitors by rememberMonitors()
        val colors = rememberColors()

        LaunchedEffect(served) {
            val failure = served.errorOrNull() ?: return@LaunchedEffect
            // The bus coming and going passes on its own; the rest leaves this agent nothing to do.
            if (failure is PolkitError.NotConnected || failure is PolkitError.BusDown) return@LaunchedEffect
            System.err.println("kortex-polkit-agent: ${failure.saidPlainly()}")
            exitProcess(1)
        }

        served.getOrNull()?.firstOrNull()?.let { request ->
            key(request) { Prompt(request, monitors, colors) }
        }
    }.onError { failure ->
        System.err.println("kortex-polkit-agent: $failure")
        exitProcess(1)
    }
}

/**
 * The dimmed screens for [request], and the prompt on the first of [monitors].
 *
 * The dimming and the card are separate surfaces because every frame redraws a surface whole: the card's caret
 * and progress bar redrawing a full-screen buffer made the prompt lag, and the dimming alone never redraws.
 */
@Composable
private fun Prompt(
    request: PolkitRequest,
    monitors: List<Monitor>,
    colors: ColorScheme,
) {
    val holder = remember(request) { PromptHolder(request.message, request.user, request.conversation) }
    val state by holder.state.collectAsState()
    val scope = rememberCoroutineScope()
    val onAction: (PromptAction) -> Unit = { action -> scope.launch { holder.onAction(action) } }

    LaunchedEffect(holder) { holder.run() }

    // Placed before the card, so the card is the newer surface on the layer and the compositor stacks it on top.
    for (monitor in monitors) key(monitor) {
        LayerSurface(
            monitor = monitor,
            namespace = "$NAMESPACE-dim-${monitor.name}",
            layer = Layer.Overlay,
            anchor = Edge.entries.toSet(),
            exclusiveZone = ExclusiveZone.Overlap,
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(SCRIM),
            )
        }
    }

    val cardOn = monitors.firstOrNull() ?: return
    key(cardOn) {
        val surface = rememberSurfaceState()
        val status = surface.status

        // A prompt nobody can see can never be answered, so its going ends the request.
        LaunchedEffect(status) {
            if (status is SurfaceStatus.Ended) holder.onAction(PromptAction.Cancel)
        }

        LayerSurface(
            monitor = cardOn,
            namespace = "$NAMESPACE-${cardOn.name}",
            layer = Layer.Overlay,
            width = Length.Of(CARD_ROOM_WIDTH),
            height = Length.Of(CARD_ROOM_HEIGHT),
            exclusiveZone = ExclusiveZone.Overlap,
            keyboard = KeyboardInteractivity.Exclusive,
            state = surface,
        ) {
            MaterialTheme(colorScheme = colors) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    PromptCard(state = state, onAction = onAction)
                }
            }
        }
    }
}

/** The generated theme the bar draws with, and material3's dark one while there is none to read. */
@Composable
private fun rememberColors(): ColorScheme =
    rememberFileTheme(THEME_FILE).getOrNull()?.active ?: darkColorScheme()

private fun PolkitError.saidPlainly(): String = when (this) {
    is PolkitError.Refused ->
        "polkitd would not take this agent: ${message ?: name}. Another agent may already serve this session."

    PolkitError.NoDisplaySession -> "logind knows of no graphical session for this user to be the agent for."
    else -> toString()
}

private const val NAMESPACE = "kortex-polkit"
private val SCRIM = Color.Black.copy(alpha = 0.8f)

// Room for the card with a few of PAM's notes under it; what the card does not fill stays transparent.
private val CARD_ROOM_WIDTH = 480.dp
private val CARD_ROOM_HEIGHT = 480.dp

/** Where matugen leaves a theme, the same file the bar reads. */
private val THEME_FILE = Path(
    System.getenv("XDG_CACHE_HOME") ?: "${System.getProperty("user.home")}/.cache",
    "matugen",
    "colors.json",
)
