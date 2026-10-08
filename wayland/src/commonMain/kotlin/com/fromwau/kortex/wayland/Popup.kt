package com.fromwau.kortex.wayland

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

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
 * A popup takes no keyboard focus, so typing goes on reaching whatever had it, and a click outside it, on another
 * application or on the desktop, leaves it open. Dismiss it by taking the call out of composition, from a click on
 * one of its own items, say, or from a click elsewhere in your own content. That is what a tooltip or a popover
 * wants; for a menu, which the desktop should dismiss on a click away from it, call [ContextMenu] instead.
 *
 * A popup belongs to the surface it opened over for as long as it lives, and cannot be moved to another. So it also
 * ends with `Ok(SurfaceEnd.LeftComposition)` when that surface is moved to another monitor or namespace, while this
 * call stands: open it again by taking the call out of composition and putting it back.
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
    PopupCall(at = at, size = PopupSize.Fixed(width, height), grab = false, state = state, content = content)
}

/**
 * Opens a popup as [Popup] does, sized by its [content] rather than to a size given up front: as wide and as tall as
 * [content] measures, up to [maxSize], and resized whenever [content] comes to measure differently.
 *
 * ```kotlin
 * Popup(at = IntOffset(0, size.height), maxSize = DpSize(320.dp, Dp.Infinity)) {
 *     Text(status.longDescription)
 * }
 * ```
 *
 * Text wraps at [maxSize]'s width, and content that fills whatever room it is given, such as
 * `Modifier.fillMaxSize()`, takes all of [maxSize], or none where [maxSize] is unbounded. Content measuring at
 * nothing is drawn one pixel square. A resize opens another popup in its place as a change of [at] does, so
 * content that sizes itself by the `size` it reads can open popup after popup without settling.
 *
 * The popup opens shortly after [content] has been composed and measured, and everything else about it is as
 * [Popup] describes.
 *
 * @param at where the popup's top-left corner goes, as [Popup] takes it.
 * @param maxSize the most room [content] is measured in, in logical pixels; each side unbounded by default and at
 *   least one pixel otherwise.
 * @param state where to read what the popup is doing, as [Popup] takes it.
 * @param content what is drawn in the popup, as [Popup] takes it.
 * @throws IllegalStateException when called outside a surface's content, as [Popup] does.
 * @throws IllegalArgumentException when a side of [maxSize] rounds to less than one pixel, which ends the surface
 *   this call's content is on, as [Popup] does for its size.
 */
@Composable
public fun Popup(
    at: IntOffset,
    maxSize: DpSize = DpSize(Dp.Infinity, Dp.Infinity),
    state: SurfaceState = rememberSurfaceState(),
    content: @Composable SurfaceScope.() -> Unit,
) {
    PopupCall(at = at, size = PopupSize.FitContent(maxSize), grab = false, state = state, content = content)
}

/**
 * What every popup call places through. They differ in how the popup's size is decided, and in whether it takes an
 * explicit grab: a menu does, and is dismissed by a click away from it; a tooltip or a popover does not, and stays
 * until its call leaves.
 */
@Composable
internal fun PopupCall(
    at: IntOffset,
    size: PopupSize,
    grab: Boolean,
    state: SurfaceState,
    clearance: Int = 0,
    content: @Composable SurfaceScope.() -> Unit,
) {
    checkNotNull(LocalSurfaceSlot.current) { POPUP_WITHOUT_PARENT }
    when (size) {
        is PopupSize.Fixed -> require(size.width.toLogicalPx() > 0 && size.height.toLogicalPx() > 0) {
            "a popup is drawn at a size of at least one pixel on each axis, not ${size.width} by ${size.height}"
        }

        is PopupSize.FitContent -> require(size.maxSize.width.atLeastOnePx && size.maxSize.height.atLeastOnePx) {
            "a popup measures its content in at least one pixel on each axis, not ${size.maxSize}"
        }
    }
    SurfaceCall(
        settings = PopupSettings(at = at, size = size, grab = grab, clearance = clearance),
        published = state.published,
        content = content,
    )
}

private val Dp.atLeastOnePx: Boolean get() = this == Dp.Infinity || toLogicalPx() > 0

/** How a popup's size is decided. */
internal sealed interface PopupSize {
    data class Fixed(val width: Dp, val height: Dp) : PopupSize

    /**
     * As big as the content measures within [maxSize]. [measured] is that size in logical pixels, which the shell
     * fills in as it measures: a call asks with none.
     */
    data class FitContent(val maxSize: DpSize, val measured: IntSize? = null) : PopupSize
}

/** What a [Popup] call asks for. */
internal data class PopupSettings(
    val at: IntOffset,
    val size: PopupSize,
    /** Whether to take `xdg_popup.grab`, which gives the popup the keyboard and the click that dismisses it. */
    val grab: Boolean = false,
    /**
     * How far below [at] the popup opens, in logical pixels, and 0 for a popup whose corner is [at]. A popup that
     * would run off the bottom of the screen opens above [at] instead, which keeps the room in between clear either
     * way: a tooltip keeps the pointer uncovered by it.
     */
    val clearance: Int = 0,
) : SurfaceSettings() {

    /** The size the popup opens at, in logical pixels; a popup sized by its content is measured first. */
    val logicalSize: IntSize
        get() = when (size) {
            is PopupSize.Fixed -> IntSize(size.width.toLogicalPx(), size.height.toLogicalPx())
            is PopupSize.FitContent -> checkNotNull(size.measured) { "a popup sized by its content opened unmeasured" }
        }

    /** All of it lives in a positioner the compositor copies as the popup is created, so a change is a new one. */
    override fun rebuildsOverSameKind(placed: SurfaceSettings): Boolean = this != placed
}

/** What a popup call outside any surface's content fails with; a test reads it. */
internal const val POPUP_WITHOUT_PARENT =
    "a popup opens over another surface: call it in a surface's content, not in kortexApplication's own"
