package com.fromwau.kortex.wayland

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.flatMap
import com.fromwau.kern.result.map
import com.fromwau.kortex.compose.KortexPlatform

/**
 * Runs [content] as a bar on every connected output, until the compositor goes away.
 *
 * ```kotlin
 * fun main() {
 *     runBar(height = 56.dp, keyboard = KeyboardInteractivity.OnDemand) { Bar() }
 *         .onError { error("kortex: $it") }
 * }
 * ```
 *
 * Blocks for the lifetime of the bar. Outputs may come and go while it runs; a bar appears and
 * disappears with each. Everything it opens — the connection, the surfaces, the buffers — is closed
 * before it returns, however it returns.
 *
 * @param namespace what the compositor calls these surfaces, e.g. in `hyprctl layers`.
 * @param height how tall the bar is; the anchored axis spans the output.
 * @param keyboard whether the bar can take keyboard focus. Changing this at runtime is not
 *   supported by every compositor, so it is fixed for the bar's lifetime.
 * @param content the composition, drawn on every output.
 */
public fun runBar(
    namespace: String = "kortex",
    height: Dp = 32.dp,
    keyboard: KeyboardInteractivity = KeyboardInteractivity.None,
    platform: KortexPlatform = KortexPlatform.None,
    content: @Composable () -> Unit,
): EmptyResult<KortexError> =
    WaylandDisplay.connect().flatMap { display ->
        display.use {
            KortexShell.create(display, namespace, height, platform, keyboard, content)
                .map { shell -> shell.use { it.runEventLoop() } }
        }
    }
