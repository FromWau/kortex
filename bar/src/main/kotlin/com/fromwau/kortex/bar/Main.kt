package com.fromwau.kortex.bar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.onError
import com.fromwau.kortex.wayland.KeyboardInteractivity
import com.fromwau.kortex.wayland.runBar

fun main() {
    // Static keyboard interactivity, like the reference's dock preset: a layer surface that changes it
    // at runtime never gets the keyboard back to the focused window (hyprwm/Hyprland#8293).
    runBar(height = 56.dp, keyboard = KeyboardInteractivity.OnDemand) { Bar() }
        .onError { error("kortex: $it") }
}

@Composable
private fun Bar() {
    var clicks by remember { mutableStateOf(0) }
    var text by remember { mutableStateOf("") }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
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
