package com.fromwau.kortex.wayland

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.getOrElse
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.Path

/**
 * Two buttons that copy files to the clipboard and read files off it, so a real file manager can be the
 * other end of each.
 *
 * `ClipboardFocusTest` already checks `setUris` against `wl-paste`, byte for byte including the list's last
 * CRLF. What it cannot check is whether a file manager **pastes** them, and that is a different question:
 * `text/uri-list` is what the specification has, and a file manager may want more of its own beside it to
 * treat a paste as files rather than ignore it. Only pasting into one answers that.
 *
 * Copy writes two files under `/tmp` first, so a paste has something real to land on and the result is a
 * file on disk rather than a dialog.
 *
 * Run it, then:
 * - press Copy, and paste into a file manager. Two files should appear where you pasted.
 * - copy files in the file manager, click the probe to give it the keyboard, then press Read.
 *
 * Read needs that click: a copy of this client's own answers from memory, but another application's reads
 * back only while one of these surfaces has keyboard focus, and without it the answer is `NoSelection`.
 *
 * Under `WAYLAND_DEBUG=client` the `wl_data_source` and `wl_data_offer` traffic reads beside each result.
 */
public fun main() {
    val display = WaylandDisplay.connect().getOrElse { error("live: no compositor answered: $it") }
    val files = writeFiles()
    System.err.println("LIVE: copying will offer ${files.joinToString()}")

    display.use { wayland ->
        val shell = KortexShell
            .createApplication(wayland) {
                TestSurface(
                    NAMESPACE,
                    // OnDemand, because reading what another application copied needs keyboard focus and
                    // the compositor only says what is on the clipboard to a client that has it. Exclusive
                    // would take it the moment the probe starts and leave the file manager unusable.
                    keyboard = KeyboardInteractivity.OnDemand,
                    anchor = setOf(Edge.Top, Edge.Left),
                    margins = Margins(top = MARGIN.dp, left = MARGIN.dp),
                    width = WIDTH.dp,
                    height = HEIGHT.dp,
                ) { Buttons(files) }
            }
            .getOrElse { error("live: shell create failed: $it") }

        try {
            check(shell.pumpOrFail(PLACE_MILLIS) { shell.shownSurfaces.size == 1 }) {
                "live: the buttons never reached the screen"
            }
            System.err.println("LIVE: placed at ${Screen.geometry(NAMESPACE)}")
            System.err.println("LIVE: press Copy, then paste into a file manager. ${WAIT_MILLIS / 1_000}s")

            shell.pumpOrFail(WAIT_MILLIS)
        } finally {
            shell.close()
        }
    }
}

/** Two real files, because a paste that lands on nothing says nothing about whether the paste worked. */
private fun writeFiles(): List<String> = listOf("kortex-one.txt", "kortex two.txt").map { name ->
    val path = Path.of("/tmp", name)
    Files.writeString(path, "copied out of kortex\n")
    // toUri percent-encodes the space in the second, which is the part a hand-built string gets wrong.
    path.toUri().toString()
}

@Composable
private fun Buttons(files: List<String>) {
    val clipboard = LocalKortexClipboard.current
    val scope = rememberCoroutineScope()

    Row(
        Modifier.fillMaxSize().background(Color(0xFF1E1E2E)),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // weight rather than a fraction: in a Row a fraction measures against what is left, so the second
        // of two would come out half the width of the first.
        Button("Copy", Color(0xFF2B6CB0), Modifier.weight(1f)) {
            scope.launch { System.err.println("LIVE: setUris -> ${clipboard.setUris(files)}") }
        }

        Button("Read", Color(0xFF2F855A), Modifier.weight(1f)) {
            scope.launch { System.err.println("LIVE: readUris -> ${clipboard.readUris()}") }
        }
    }
}

@Composable
private fun Button(label: String, colour: Color, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .fillMaxHeight()
            .background(colour)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label)
    }
}

private const val NAMESPACE = "kortex-live-clipboard"
private const val WIDTH = 260
private const val HEIGHT = 80
private const val MARGIN = 200

private const val PLACE_MILLIS = 4_000L
private const val WAIT_MILLIS = 180_000L
