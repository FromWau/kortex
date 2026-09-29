package com.fromwau.kortex.wayland

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.IError
import com.fromwau.kern.result.Result

/**
 * The desktop's clipboard, as a surface's content reaches it through [LocalKortexClipboard]: put a text or an image on
 * it, clear it, or read what is on it, each call returning a typed result.
 *
 * Compose's `LocalClipboard`, which Compose's own text fields use, reaches the same clipboard, except inside
 * Compose's own `Popup` and `Dialog`, from `androidx.compose.ui.window`, where it is AWT's clipboard rather than
 * the desktop's. It carries text alone, and it cannot say why a copy or paste failed: a failed paste gets no
 * entry, and a failed copy does nothing. Call this instead when your content needs to know why, or when it copies
 * or pastes an image; it reaches the desktop's clipboard wherever a surface's content reads it, inside those two
 * and inside kortex's own [Window], [Dialog] and [Popup] alike.
 *
 * ```kotlin
 * val clipboard = LocalKortexClipboard.current
 * val scope = rememberCoroutineScope()
 * Button(onClick = { scope.launch { copied = clipboard.setText(link) } }) { Text("Copy link") }
 * ```
 *
 * Call it from any thread while [kortexApplication] runs. Once it has returned, every call throws
 * [IllegalStateException]; a call made just as it returns may never return.
 */
public sealed interface KortexClipboard {
    /**
     * Puts [text] on the clipboard, for this and every other application to paste.
     *
     * The text stays there only while your application runs: once [kortexApplication] returns, the text comes off
     * the clipboard again, unless a clipboard manager has kept a copy of it.
     *
     * @return `Ok` once the compositor has been asked, which does not confirm a copy;
     *   [ClipboardError.NoInputSerial] before the user has pressed a key, clicked, or given keyboard focus to any
     *   of your surfaces; or [ClipboardError.NoClipboard] on a compositor that has no clipboard.
     * @throws IllegalStateException once [kortexApplication] has returned.
     */
    public suspend fun setText(text: String): EmptyResult<ClipboardError>

    /**
     * Empties the clipboard, whichever application filled it.
     *
     * @return the same as [setText].
     * @throws IllegalStateException once [kortexApplication] has returned.
     */
    public suspend fun clear(): EmptyResult<ClipboardError>

    /**
     * The text on the clipboard.
     *
     * Text your application copied reads back at once, with or without keyboard focus, though the compositor never
     * confirms that it took the copy. Text another application copied reads back only while one of your surfaces
     * has keyboard focus, since only then does the compositor say what is on the clipboard; without it, the read
     * is [ClipboardError.NoSelection].
     *
     * Cancelling your coroutine does not cut a read short: the coroutine sees its cancellation once the read has
     * returned, at most a second after the read began.
     *
     * @return the text, or why there is none: [ClipboardError.NoSelection], [ClipboardError.NoText],
     *   [ClipboardError.NoClipboard], [ClipboardError.PipeFailed], [ClipboardError.ReadTimedOut] or
     *   [ClipboardError.TooLarge].
     * @throws IllegalStateException once [kortexApplication] has returned.
     */
    public suspend fun readText(): Result<String, ClipboardError>

    /**
     * Puts [image] on the clipboard as both a PNG and a JPEG, for this and every other application to paste.
     *
     * The image stays there only while your application runs: once [kortexApplication] returns, it comes off the
     * clipboard again, unless a clipboard manager has kept a copy of it.
     *
     * @return `Ok` once the compositor has been asked, which does not confirm a copy; [ClipboardError.TooLarge]
     *   where the image encodes to more than 64 MiB; [ClipboardError.NoInputSerial] before the user has pressed a
     *   key, clicked, or given keyboard focus to any of your surfaces; or [ClipboardError.NoClipboard] on a
     *   compositor that has no clipboard.
     * @throws IllegalStateException once [kortexApplication] has returned.
     */
    public suspend fun setImage(image: ImageBitmap): EmptyResult<ClipboardError>

    /**
     * The image on the clipboard.
     *
     * An image your application copied reads back at once, with or without keyboard focus, though the compositor
     * never confirms that it took the copy. An image another application copied reads back only while one of your
     * surfaces has keyboard focus, since only then does the compositor say what is on the clipboard; without it,
     * the read is [ClipboardError.NoSelection].
     *
     * Cancelling your coroutine does not cut a read short: the coroutine sees its cancellation once the read has
     * returned, at most a second after the read began.
     *
     * @return the image, or why there is none: [ClipboardError.NoSelection], [ClipboardError.NoImage],
     *   [ClipboardError.NoClipboard], [ClipboardError.PipeFailed], [ClipboardError.ReadTimedOut] or
     *   [ClipboardError.TooLarge].
     * @throws IllegalStateException once [kortexApplication] has returned.
     */
    public suspend fun readImage(): Result<ImageBitmap, ClipboardError>

    /**
     * The files on the clipboard, as the URIs the application that copied them named them by, such as
     * `file:///home/you/notes.txt`.
     *
     * Files another application copied read back only while one of your surfaces has keyboard focus, since
     * only then does the compositor say what is on the clipboard; without it, the read is
     * [ClipboardError.NoSelection]. Nothing your own application copies reads back here: kortex puts text and
     * images on the clipboard and never a list of files.
     *
     * They arrive percent-encoded and under any scheme, so turn one into a path yourself, with
     * `Path.of(URI(uri))` for a `file` and your own handling for the rest.
     *
     * Cancelling your coroutine does not cut a read short: the coroutine sees its cancellation once the read
     * has returned, at most a second after the read began.
     *
     * @return the URIs, or why there are none: [ClipboardError.NoSelection], [ClipboardError.NoUris],
     *   [ClipboardError.NoClipboard], [ClipboardError.PipeFailed], [ClipboardError.ReadTimedOut] or
     *   [ClipboardError.TooLarge].
     * @throws IllegalStateException once [kortexApplication] has returned.
     */
    public suspend fun readUris(): Result<List<String>, ClipboardError>
}

/**
 * The desktop's clipboard, in the content of every surface on screen. Reading it anywhere else, the
 * application's own content included, throws [IllegalStateException].
 */
public val LocalKortexClipboard: ProvidableCompositionLocal<KortexClipboard> =
    staticCompositionLocalOf { error("LocalKortexClipboard is provided only in a surface's content") }

/** Why a [KortexClipboard] call, or a read off a [KortexDragOffer], failed. */
public sealed interface ClipboardError : IError {
    /** The compositor has no clipboard to offer, so every call fails this way. */
    public data object NoClipboard : ClipboardError

    /**
     * There is nothing to read: the clipboard is empty, or another application's copy is on it while none of your
     * surfaces has keyboard focus, which reading that copy needs.
     */
    public data object NoSelection : ClipboardError

    /** What is on the clipboard is not text, an image say. */
    public data object NoText : ClipboardError

    /**
     * What is on the clipboard is not an image, or is in a format other than PNG and JPEG, the two
     * [KortexClipboard.readImage] decodes.
     */
    public data object NoImage : ClipboardError

    /** What is on the clipboard, or what a drag carries, is not a list of files or other URIs. */
    public data object NoUris : ClipboardError

    /**
     * What a drag carries is not a sandboxed application's transfer key. No clipboard call answers this:
     * only a drag is read for one.
     */
    public data object NoPortalKey : ClipboardError

    /**
     * The user has not yet pressed a key, clicked, or given keyboard focus to any of your surfaces. The
     * compositor takes a copy or a clear only in answer to one of those, so try again once the user has.
     */
    public data object NoInputSerial : ClipboardError

    /** What was copied could not be carried over from the application that copied it. */
    public data object PipeFailed : ClipboardError

    /** The application that made the copy did not finish sending it within a second. */
    public data object ReadTimedOut : ClipboardError

    /**
     * What is on the clipboard is larger than one transfer takes: 16 MiB for a text, 64 MiB for an image. A copy
     * fails this way too, where the image to copy encodes to more than 64 MiB.
     */
    public data object TooLarge : ClipboardError
}
