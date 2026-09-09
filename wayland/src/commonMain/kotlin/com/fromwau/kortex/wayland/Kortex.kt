package com.fromwau.kortex.wayland

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.flatMap
import com.fromwau.kern.result.map
import com.fromwau.kortex.compose.KortexPlatform

/**
 * Runs every surface in [specs] on one connection, each where its [SurfaceSpec.target] says.
 *
 * ```kotlin
 * fun main() {
 *     runSurfaces(
 *         SurfaceSpec(panelConfig) { Panel() },
 *         SurfaceSpec(osdConfig, OutputTarget.CompositorChoice) { Osd() },
 *     ).onError { error("kortex: $it") }
 * }
 * ```
 *
 * Blocks until the compositor goes away, or until no surface is left and none can return: content that
 * closes its own surface ends the run once the last one is gone, while an [OutputTarget.EveryOutput]
 * spec with no output to place on keeps waiting for one. Everything it opens — the connection, the
 * surfaces, the buffers — is closed before it returns, however it returns.
 *
 * @param specs what to put on screen, and where.
 * @param platform host hooks the compositions drive, e.g. the cursor shape a hover asks for.
 */
public fun runSurfaces(
    vararg specs: SurfaceSpec,
    platform: KortexPlatform = KortexPlatform.None,
): EmptyResult<KortexError> =
    WaylandDisplay.connect().flatMap { display ->
        display.use {
            KortexShell.create(display, *specs, platform = platform)
                .map { shell -> shell.use { it.runEventLoop() } }
        }
    }

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
 * disappears with each. It also returns once content has closed the last bar while an output is still
 * connected, since nothing would put another one there. Everything it opens — the connection, the
 * surfaces, the buffers — is closed before it returns, however it returns.
 *
 * @param namespace what the compositor calls these surfaces, e.g. in `hyprctl layers`.
 * @param height how tall the bar is; the anchored axis spans the output.
 * @param width how wide the bar is; 0 (the default) spans the output the same way [height] can.
 * @param margins insets from the anchor point; an edge the default anchor does not pin ignores its margin.
 * @param keyboard whether the bar can take keyboard focus. Changing this at runtime is not
 *   supported by every compositor, so it is fixed for the bar's lifetime.
 * @param content the composition, drawn on every output.
 */
public fun runBar(
    namespace: String = "kortex",
    height: Dp = 32.dp,
    width: Dp = 0.dp,
    margins: Margins = Margins.None,
    keyboard: KeyboardInteractivity = KeyboardInteractivity.None,
    platform: KortexPlatform = KortexPlatform.None,
    content: @Composable () -> Unit,
): EmptyResult<KortexError> {
    val config = SurfaceConfig(
        namespace = namespace,
        width = width,
        height = height,
        margins = margins,
        exclusiveZone = ExclusiveZone.Reserve(height),
        keyboard = keyboard,
    )
    return runSurfaces(
        SurfaceSpec(config = config, target = OutputTarget.EveryOutput, content = content),
        platform = platform,
    )
}
