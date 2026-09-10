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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.onError
import com.fromwau.kortex.compose.LocalKortexSurface
import com.fromwau.kortex.wayland.KeyboardInteractivity
import com.fromwau.kortex.wayland.LocalKortexHost
import com.fromwau.kortex.wayland.OutputGeometry
import com.fromwau.kortex.wayland.OutputTarget
import com.fromwau.kortex.wayland.SurfaceConfig
import com.fromwau.kortex.wayland.SurfaceSpec
import com.fromwau.kortex.wayland.runBar
import kotlin.math.roundToInt

fun main() {
    // Static keyboard interactivity, like the reference's dock preset: a layer surface that changes it
    // at runtime never gets the keyboard back to the focused window (hyprwm/Hyprland#8293).
    runBar(height = 56.dp, keyboard = KeyboardInteractivity.OnDemand) { Bar() }
        .onError { error("kortex: $it") }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Bar() {
    var clicks by remember { mutableStateOf(0) }
    var text by remember { mutableStateOf("") }
    val host = LocalKortexHost.current

    MaterialTheme(colorScheme = darkColorScheme()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type != PointerEventType.Press || event.button != PointerButton.Secondary) {
                                continue
                            }
                            val output = host.output ?: continue
                            // The pointer offset arrives in buffer (physical) pixels, the same space the
                            // scene renders in (PointerInput.toScenePixels); contextMenu wants the logical
                            // (surface-local) space a configure reports, so divide by the output's scale.
                            val logical = event.changes.first().position / output.scale.toFloat()
                            // The bar is anchored Top, Left and Right with no margins, so a bar-local point
                            // is an output point on both axes for this demo's config.
                            val at = IntOffset(logical.x.roundToInt(), logical.y.roundToInt())
                            host.open(contextMenuSpec(at, output))
                        }
                    }
                },
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(onClick = { clicks++ }) { Text("clicked $clicks") }

                TextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    modifier = Modifier.width(320.dp).fillMaxHeight(),
                )

                Text(text = "typed: $text", color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

private fun contextMenuSpec(at: IntOffset, output: OutputGeometry): SurfaceSpec {
    val config = SurfaceConfig
        .contextMenu(
            at = at,
            menuSize = IntSize(MENU_WIDTH, MENU_HEIGHT),
            outputSize = IntSize(output.width / output.scale, output.height / output.scale),
        )
        .copy(namespace = "kortex-menu")
    return SurfaceSpec(config, OutputTarget.NamedOutput(output.name)) { ContextMenu() }
}

@Composable
private fun ContextMenu() {
    val surface = LocalKortexSurface.current

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
                        .clickable { surface.close() }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

private const val MENU_WIDTH = 160
private const val MENU_HEIGHT = 120
private val MENU_ITEMS = listOf("Option 1", "Option 2", "Option 3")
