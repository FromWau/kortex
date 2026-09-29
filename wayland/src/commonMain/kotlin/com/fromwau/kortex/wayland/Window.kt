package com.fromwau.kortex.wayland

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.Result

/**
 * Keeps a window of your own on screen while this call is in composition, drawing [content] in it. The compositor
 * treats it as it treats any other application's window: it decides where the window goes and how big it is, moves
 * it between workspaces, and lists it wherever it lists the windows that are open. Call it in [kortexApplication]'s
 * content, or in a surface's content to open a window for as long as that surface runs.
 *
 * ```kotlin
 * kortexApplication {
 *     val notes = rememberWindowState()
 *     var showing by remember { mutableStateOf(true) }
 *     val status = notes.status
 *
 *     if (showing) {
 *         Window(title = "Notes", appId = "com.example.notes", state = notes) {
 *             Button(onClick = { showing = false }) { Text("Close") }
 *         }
 *     }
 *     LaunchedEffect(status) { if (status is WindowStatus.Ended) exitApplication() }
 * }
 * ```
 *
 * The window appears shortly after the call enters composition, and goes when the call leaves it. It keeps running
 * and draws the newest [content] throughout, and a changed [title] or [appId] reaches the window on screen shortly
 * after, ending nothing. State [content] keeps behind `remember` stands through those changes, and lasts until the
 * window ends.
 *
 * A changed [width] or [height] draws content at the new size, and the window keeps it until the compositor next
 * sizes the window, which a compositor that tiles does whenever the layout around it changes. [state] reports the
 * size the window is drawn at, whichever it is.
 *
 * The compositor can ask for the window to close, which sets [WindowState.closeRequested] and does nothing else.
 * Take the call out of composition to close it, leave it in to refuse, and take as long as you like to ask the
 * user first. Say a refusal with [WindowState.declineClose], so the next ask is seen as one.
 *
 * Asks go the other way too: [WindowState.askMaximized], [WindowState.askFullscreen] and
 * [WindowState.askMinimized] put the case to the compositor, which answers with the states it chose.
 * [WindowState.canMaximize], [WindowState.canFullscreen] and [WindowState.canMinimize] say which of the three
 * this compositor will honour, so a control for one it ignores need never be shown.
 *
 * Once the window has ended, in any of the ways [WindowStatus.Ended] lists, the call shows nothing until you take it
 * out of composition and put it back, which places a window again and takes [state] with it.
 *
 * @param title what the compositor shows for the window wherever it names it, such as a task bar or a window
 *   switcher, exactly as written.
 * @param appId what the compositor matches the window by, which is how a window rule finds it. Write it as the
 *   application's desktop entry is named, e.g. `com.example.notes`. Named none, a window is matched as `kortex`.
 * @param width how wide content is drawn, at first and again each time this changes, until the compositor next
 *   gives the window a size of its own, which a compositor that tiles does as it places it.
 * @param height how tall content is drawn, on the same terms as [width].
 * @param state where to read what the window is doing: [WindowStatus.Placing] until it reaches the screen,
 *   [WindowStatus.OnScreen] with the size it is drawn at, and [WindowStatus.Ended] with how it ended, once and for
 *   good. Pass a state of your own to keep reading it after the call has left composition; one call at a time
 *   publishes to a state.
 * @param content what is drawn in the window. It reaches the window itself as `this`, the clipboard as
 *   [LocalKortexClipboard], and the same window through `LocalKortexSurface.current` in a composable further down.
 * @throws IllegalStateException when called outside [kortexApplication].
 * @throws IllegalArgumentException when [width] or [height] rounds to less than one pixel, which ends more than
 *   this window: the application when this call is in [kortexApplication]'s own content, and otherwise the
 *   surface whose content it is in.
 */
@Composable
public fun Window(
    title: String,
    appId: String = DEFAULT_APP_ID,
    width: Dp = 640.dp,
    height: Dp = 480.dp,
    state: WindowState = rememberWindowState(),
    content: @Composable SurfaceScope.() -> Unit,
) {
    require(width.toLogicalPx() > 0 && height.toLogicalPx() > 0) {
        "a window is drawn at a size of at least one pixel on each axis, not $width by $height"
    }
    SurfaceCall(
        settings = WindowSettings(title = title, appId = appId, width = width, height = height),
        published = state.published,
        content = content,
    )
}

/** What a window is doing, as [WindowState.status] reports it. */
public sealed interface WindowStatus {
    /** The window has not reached the screen yet: it has no size, and nothing of its content is drawn. */
    public data object Placing : WindowStatus

    /**
     * The window is on screen, drawing its content.
     *
     * @property size the size content is drawn at, in the logical pixels it lays out in, which is smaller than
     *   the size in physical pixels by the scale of the monitor it is on.
     */
    public data class OnScreen(public val size: IntSize) : WindowStatus

    /**
     * The window has gone for good: nothing takes its place while the call that showed it stands, and this stays
     * the status until that call leaves composition.
     *
     * @property result how it ended. `Ok` carries a [SurfaceEnd]: `close()`, the compositor, or the call leaving
     *   composition. `Err` carries what ended it instead: [KortexError.SurfaceCrashed] when your content threw;
     *   [KortexError.MissingGlobal] when the compositor puts no application windows on screen at all;
     *   [KortexError.ClientSideDecorationRequired] when it leaves this window's title bar and resize handles to the
     *   application, which kortex does not draw; [KortexError.MissingSeatDevice],
     *   [KortexError.SurfaceNotConfigured] or [KortexError.ShmAllocationFailed] when the compositor would not give
     *   the window what it needs to draw; and the same error [kortexApplication] returns when the connection to the
     *   compositor failed.
     */
    public data class Ended(public val result: Result<SurfaceEnd, KortexError>) : WindowStatus
}

/**
 * What one window call's window is doing, as state, and what a [Dialog] call's dialog is doing too: whatever
 * reads one of these while it composes is recomposed each time that one changes.
 *
 * ```kotlin
 * val notes = rememberWindowState()
 * var showing by remember { mutableStateOf(true) }
 * val status = notes.status
 * LaunchedEffect(status) { if (status is WindowStatus.Ended) showing = false }
 *
 * when {
 *     showing -> Window(title = "Notes", state = notes) { Text("...") }
 *     else -> Bar { Text("the notes window stopped: $status") }
 * }
 * ```
 *
 * [rememberWindowState] keeps one where the call is, which is enough to read it beside that call. Remembering your
 * own further up keeps it readable after you have taken the call out of composition, where it holds the ending it
 * last saw. Putting the call back reuses the same state: it goes to [WindowStatus.Placing] and on as the new window
 * is placed, so you read a window that ended and came back.
 *
 * Decide what to show from state of your own, not from [status]: a call you compose only while its status is not
 * [WindowStatus.Ended] can never come back, because that call is what would clear the ending. Read [status] for
 * what happened, and keep what to show beside it.
 *
 * One state belongs to one window call at a time. Two calls each holding a window cannot share one, and handing a
 * second call a state the first is still publishing to ends what that call is part of: a call in
 * [kortexApplication]'s own content ends the application with [KortexError.ApplicationCrashed], while a call in a
 * surface's content ends that surface with [KortexError.SurfaceCrashed] and leaves the application running.
 */
public class WindowState {
    internal val published: PublishedProgress = PublishedProgress()

    /** What the window is doing now. */
    public val status: WindowStatus
        get() = published.statusOf(WindowStatus.Placing, WindowStatus::OnScreen, WindowStatus::Ended)

    /**
     * The compositor has asked for this window to close. Nothing happens until the caller acts on it: take the
     * window's call out of composition to close it, or call [declineClose] to keep it.
     */
    public val closeRequested: Boolean get() = published.windowStates.closeRequested

    /**
     * Refuses the close the compositor asked for and keeps the window: [closeRequested] reads false again shortly
     * after, and the next time the compositor asks is an ask of its own.
     *
     * Call it where the user chooses to keep the window, such as a prompt over unsaved changes they turned down.
     * Nothing else about the window changes: it stays on screen, its content keeps running and keeps what it
     * holds, and its [status] stands. Call it from wherever you read the answer, on any thread. Call it only
     * where [closeRequested] reads true: refusing an ask that never came can swallow one arriving at that moment.
     * Once the window has ended it does nothing.
     *
     * ```kotlin
     * val state = rememberWindowState()
     *
     * if (state.closeRequested) {
     *     ConfirmQuit(
     *         onKeep = { state.declineClose() },
     *         onQuit = { showWindow = false },
     *     )
     * }
     * ```
     */
    public fun declineClose() {
        published.boundTo?.declineClose()
    }

    /**
     * The window fills the screen except for whatever the compositor keeps reserved, such as a panel. Read it
     * as what the compositor reports, not as a measurement of what the window currently covers.
     */
    public val maximized: Boolean get() = published.windowStates.maximized

    /** The window has the whole screen, with nothing else over it. */
    public val fullscreen: Boolean get() = published.windowStates.fullscreen

    /**
     * The window shares at least one edge with the layout around it, so it cannot choose its own size. Read it
     * as what the compositor reports, not as a measurement of the window's actual placement, and note that a
     * compositor may not have said either way when the window first appears: this reads false until it does.
     */
    public val tiled: Boolean get() = published.windowStates.tiled

    /** The window is the one the user is working in, and is where their typing goes. */
    public val activated: Boolean get() = published.windowStates.activated

    /**
     * Whether the compositor will honour [askMaximized] at all. Hide or disable whatever offers the ask where
     * this reads false: a compositor ignores one it does not support, so the control would do nothing.
     *
     * True until the compositor says otherwise, which covers every compositor too old to say: `wm_capabilities`
     * arrives from `xdg_wm_base` 5, and asking one that never sent it costs nothing.
     */
    public val canMaximize: Boolean get() = honours(XdgToplevelCapability.Maximize)

    /** Whether the compositor will honour [askFullscreen], on the terms [canMaximize] describes. */
    public val canFullscreen: Boolean get() = honours(XdgToplevelCapability.Fullscreen)

    /** Whether the compositor will honour [askMinimized], on the terms [canMaximize] describes. */
    public val canMinimize: Boolean get() = honours(XdgToplevelCapability.Minimize)

    private fun honours(capability: XdgToplevelCapability): Boolean =
        capability in published.windowStates.capabilities

    /**
     * Asks the compositor to maximize this window, or to take that back, and reports what it chose through
     * [maximized].
     *
     * Asking is not getting. The compositor answers an ask with the states it chose, which may be the ones the
     * window already had, and a compositor that does not maximize windows at all ignores it. So read
     * [maximized] for what the window is, and never take the ask itself as the answer: nothing about the window
     * changes at the call.
     *
     * ```kotlin
     * val state = rememberWindowState()
     *
     * Button(onClick = { state.askMaximized(!state.maximized) }) {
     *     Text(if (state.maximized) "Restore" else "Maximize")
     * }
     * ```
     *
     * Call it from any thread, as [declineClose] takes one: the ask reaches the compositor in the shell's next
     * pass. Until the window is on screen, and once it has ended, it does nothing.
     */
    public fun askMaximized(maximized: Boolean) {
        ask { _, toplevel -> toplevel.askMaximized(maximized) }
    }

    /**
     * Asks for this window to have the whole of a monitor with nothing else over it, or to take that back, on
     * the terms [askMaximized] describes; [fullscreen] reports what the compositor chose.
     *
     * Which monitor that is, the compositor chooses.
     */
    public fun askFullscreen(fullscreen: Boolean) {
        ask { _, toplevel -> toplevel.askFullscreen(fullscreen) }
    }

    /**
     * Asks for this window to be minimized, on the terms [askMaximized] describes, with one difference: the
     * protocol carries no state for it and no ask back. Nothing here reports whether the compositor minimized
     * the window, and nothing here brings it back, which is the user's to do.
     */
    public fun askMinimized() {
        ask { _, toplevel -> toplevel.askMinimized() }
    }

    /**
     * Hands the window to the compositor to move with the pointer, until the user lets go of the button they
     * are holding.
     *
     * Call it from a press on whatever your content offers to drag the window by, a strip across its top say.
     * The compositor takes the gesture from there, so your content hears nothing more of it: no movement, no
     * release, and no report of where the window came to rest. Read [WindowStatus] for the size it ends at.
     *
     * ```kotlin
     * val state = rememberWindowState()
     *
     * Box(
     *     Modifier
     *         .fillMaxWidth()
     *         .height(28.dp)
     *         .pointerInput(Unit) { detectTapGestures(onPress = { state.askMove() }) },
     * )
     * ```
     *
     * It quotes the newest press this window's pointer took, which is the serial a compositor checks the ask
     * against, and sends nothing at all before the user has pressed anything. Asking is not getting, as
     * [askMaximized] describes: a compositor refuses a move by ignoring it, and there is no answer to read
     * either way.
     *
     * Call it from any thread. Until the window is on screen, and once it has ended, it does nothing.
     */
    public fun askMove() {
        ask { window, _ -> window.askMove() }
    }

    /**
     * Hands the window to the compositor to resize by [edge], on the terms [askMove] describes.
     *
     * [ResizeEdge] has one entry for each of the eight resize cursors, so whatever decides which cursor the
     * pointer is under decides what to pass here.
     *
     * ```kotlin
     * Box(
     *     Modifier
     *         .align(Alignment.BottomEnd)
     *         .size(12.dp)
     *         .pointerHoverIcon(PointerIcon(Cursor(Cursor.SE_RESIZE_CURSOR)))
     *         .pointerInput(Unit) {
     *             detectTapGestures(onPress = { state.askResize(ResizeEdge.BottomRight) })
     *         },
     * )
     * ```
     */
    public fun askResize(edge: ResizeEdge) {
        ask { window, _ -> window.askResize(edge) }
    }

    /**
     * Asks the compositor to open its own menu for this window at [at], on the terms [askMove] describes.
     *
     * The menu is the compositor's rather than yours: it carries whatever that desktop offers for a window,
     * and it looks and behaves like the one every other window on screen gets. Raise it from a secondary
     * click on whatever your content offers to drag the window by.
     *
     * A compositor that has no such menu ignores the ask, and nothing here reports that, so offer it as
     * something that may do nothing rather than as a control that must.
     *
     * @param at where the menu opens, in logical pixels from this window's top-left corner.
     */
    public fun askWindowMenu(at: IntOffset) {
        ask { window, _ -> window.askWindowMenu(at) }
    }

    private fun ask(request: (KortexSurface, XdgToplevelSurface) -> Unit) {
        published.boundTo?.askWindow(request)
    }
}

/**
 * A [WindowState] remembered where it is called, for the window call beside it.
 *
 * [WindowState] says what its status is for, and why what to show belongs in state of your own beside it.
 */
@Composable
public fun rememberWindowState(): WindowState = remember { WindowState() }

/**
 * Which edge or corner of a window a resize drags, numbered as `xdg_toplevel.resize_edge` numbers them.
 *
 * One for each of the eight resize cursors, so whatever picks the cursor under the pointer picks the edge to
 * hand [WindowState.askResize].
 */
public enum class ResizeEdge(internal val wireValue: Int) {
    Top(1),
    Bottom(2),
    Left(4),
    Right(8),
    TopLeft(5),
    TopRight(9),
    BottomLeft(6),
    BottomRight(10),
}

/** The app id a window whose caller named none carries, and the one every dialog carries. */
internal const val DEFAULT_APP_ID = "kortex"

/** What a call that places an xdg toplevel asks for, which is a [Window] or a [Dialog]. */
internal sealed class ToplevelSettings : SurfaceSettings() {
    abstract val title: String
    abstract val appId: String
    abstract val width: Dp
    abstract val height: Dp

    /** Nothing an xdg toplevel is placed with is fixed at creation, so a change never needs a new surface. */
    override fun rebuildsOverSameKind(placed: SurfaceSettings): Boolean = false
}

/** What a [Window] call asks for. */
internal data class WindowSettings(
    override val title: String,
    override val appId: String,
    override val width: Dp,
    override val height: Dp,
) : ToplevelSettings()
