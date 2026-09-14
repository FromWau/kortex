package com.fromwau.kortex.wayland

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.IError
import com.fromwau.kern.result.Result

/**
 * The desktop's clipboard, as a shell's content reaches it through `LocalKortexHost.current.clipboard`: put text
 * on it, clear it, or read the text on it, each call returning a typed result.
 *
 * Compose's `LocalClipboard`, which Compose's own text fields use, reaches the same clipboard but cannot say why
 * a copy or paste failed: a failed paste gets no entry, and a failed copy does nothing. Call this instead when
 * your content needs to know.
 *
 * ```kotlin
 * val clipboard = LocalKortexHost.current.clipboard
 * val scope = rememberCoroutineScope()
 * Button(onClick = { scope.launch { copied = clipboard.setText(link) } }) { Text("Copy link") }
 * ```
 *
 * Call it from any thread.
 */
public interface KortexClipboard {
    /**
     * Puts [text] on the clipboard, for this and every other application to paste.
     *
     * @return `Ok` once the shell has asked the compositor, which does not confirm a copy;
     *   [ClipboardError.NoInputSerial] before the user has pressed a key, clicked, or given keyboard focus to any
     *   of the shell's surfaces; or [ClipboardError.NoClipboard] on a compositor that has no clipboard.
     */
    public suspend fun setText(text: String): EmptyResult<ClipboardError>

    /**
     * Empties the clipboard, whichever application filled it.
     *
     * @return the same as [setText].
     */
    public suspend fun clear(): EmptyResult<ClipboardError>

    /**
     * The text on the clipboard.
     *
     * The compositor tells a shell what is on the clipboard only while one of its surfaces has keyboard focus, so
     * a shell that has never had it reads [ClipboardError.NoSelection], even for text it copied itself.
     *
     * Cancelling your coroutine does not cut a read short: the coroutine sees its cancellation once the read has
     * returned, which is within a second.
     *
     * @return the text, or why there is none: [ClipboardError.NoSelection], [ClipboardError.NoText],
     *   [ClipboardError.NoClipboard], [ClipboardError.PipeFailed], [ClipboardError.ReadTimedOut] or
     *   [ClipboardError.TooLarge].
     */
    public suspend fun readText(): Result<String, ClipboardError>
}

/** Why a [KortexClipboard] call failed. */
public sealed interface ClipboardError : IError {
    /** The compositor has no clipboard to offer, so every call fails this way. */
    public data object NoClipboard : ClipboardError

    /**
     * There is nothing to read: the clipboard is empty, or the compositor has not told the shell what is on it,
     * which it does only while one of the shell's surfaces has keyboard focus.
     */
    public data object NoSelection : ClipboardError

    /** What is on the clipboard is not text, an image say. */
    public data object NoText : ClipboardError

    /**
     * The user has not yet pressed a key, clicked, or given keyboard focus to any of the shell's surfaces. The
     * compositor takes a copy or a clear only in answer to one of those, so try again once the user has.
     */
    public data object NoInputSerial : ClipboardError

    /** The text could not be carried over from the application that copied it. */
    public data object PipeFailed : ClipboardError

    /** The application that copied the text did not finish sending it within a second. */
    public data object ReadTimedOut : ClipboardError

    /** The text on the clipboard is larger than 16 MiB, the most a read takes. */
    public data object TooLarge : ClipboardError
}
