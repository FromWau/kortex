package com.fromwau.kortex.wayland

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset

/**
 * Opens a popup of your own over the surface this call's content is on, drawing [content] in it, for as long as
 * the call is in composition. A menu, a tooltip or a popover: the compositor places it, keeps it on screen, and
 * stacks it above the surface it belongs to.
 *
 * Call it in a surface's content, never in [kortexApplication]'s own: a popup belongs to the surface it opens
 * over, and both kinds of kortex surface can hold one, a window as much as a bar.
 *
 * ```kotlin
 * Bar(monitor = monitor) {
 *     var menu by remember { mutableStateOf<IntOffset?>(null) }
 *
 *     Text("File", modifier = Modifier.clickable { menu = IntOffset(0, size.height) })
 *
 *     menu?.let { at ->
 *         Popup(at = at, width = 160.dp, height = 120.dp) {
 *             Text("Quit", modifier = Modifier.clickable { menu = null })
 *         }
 *     }
 * }
 * ```
 *
 * The popup appears shortly after the call enters composition, and goes when the call leaves it. It keeps
 * running and draws the newest [content] throughout, and state [content] keeps behind `remember` lasts until
 * the popup ends.
 *
 * Everything about where the popup sits and how big it is is settled as it opens, so changing [at], [width] or
 * [height] opens another popup in its place: the content on it keeps its state and its running effects, and
 * reads the size it last had until the new one is on screen.
 *
 * A popup takes no keyboard focus, so typing goes on reaching whatever had it. Dismiss it by taking the call out
 * of composition, from a click on one of its own items, say, or from a click elsewhere in your own content.
 *
 * Once the popup has ended, in any of the ways [SurfaceStatus.Ended] lists, the call shows nothing until you
 * take it out of composition and put it back, which opens a popup again and takes [state] with it.
 *
 * @param at where the popup's top-left corner goes, in logical pixels from the top-left corner of the surface
 *   this call's content is on. Near that screen's right or bottom edge the popup opens the other way instead,
 *   to the left of [at] or upwards from it, each axis decided on its own, so that all of it stays visible.
 * @param width how wide the popup is. It must round to at least one logical pixel.
 * @param height how tall the popup is, on the same terms as [width].
 * @param state where to read what the popup is doing: [SurfaceStatus.Placing] until it reaches the screen,
 *   [SurfaceStatus.OnScreen] with the size it is drawn at, and [SurfaceStatus.Ended] with how it ended, once and
 *   for good. Pass a state of your own to keep reading it after the call has left composition; one call at a
 *   time publishes to a state.
 * @param content what is drawn in the popup. It reaches the popup itself as `this`, the clipboard as
 *   [LocalKortexClipboard], and the same popup through `LocalKortexSurface.current` in a composable further
 *   down.
 * @throws IllegalStateException when called outside a surface's content. Uncatchable here: it ends the
 *   application as [KortexError.ApplicationCrashed], the same as any other throw from your own content.
 * @throws IllegalArgumentException when [width] or [height] rounds to less than one pixel, which ends the
 *   surface this call's content is on, not just this popup, the same as any other throw from your content there.
 */
@Composable
public fun Popup(
    at: IntOffset,
    width: Dp,
    height: Dp,
    state: SurfaceState = rememberSurfaceState(),
    content: @Composable SurfaceScope.() -> Unit,
) {
    checkNotNull(LocalSurfaceSlot.current) { POPUP_WITHOUT_PARENT }
    require(width.toLogicalPx() > 0 && height.toLogicalPx() > 0) {
        "a popup is drawn at a size of at least one pixel on each axis, not $width by $height"
    }
    SurfaceCall(
        settings = PopupSettings(at = at, width = width, height = height),
        published = state.published,
        content = content,
    )
}

/** What a [Popup] call asks for. */
internal data class PopupSettings(
    val at: IntOffset,
    val width: Dp,
    val height: Dp,
) : SurfaceSettings() {
    /** All of it lives in a positioner the compositor copies as the popup is created, so a change is a new one. */
    override fun rebuildsOverSameKind(placed: SurfaceSettings): Boolean = this != placed
}

/** What a popup call outside any surface's content fails with; a test reads it. */
internal const val POPUP_WITHOUT_PARENT =
    "a popup opens over another surface: call it in a surface's content, not in kortexApplication's own"
