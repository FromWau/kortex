package com.fromwau.kortex.watch

import com.fromwau.kern.dirs.FileError
import com.fromwau.kern.result.IError
import kotlinx.io.files.Path

/** Why a watcher has no contents to hand you. Every case names the [path] it is about. */
public sealed interface WatchError : IError {
    public val path: Path

    /**
     * The file could not be read; [cause] is kern's own reason, and names the same path.
     *
     * Not the end of anything: a file that is missing now may be written later, and the watcher is still
     * watching. [Stopped] is the half of this set that means otherwise.
     */
    public data class Unreadable(
        override val path: Path,
        public val cause: FileError,
    ) : WatchError

    /**
     * The watcher has stopped, so nothing further will arrive and the flow is over.
     *
     * Either a watch that never started or one that was live and ended. Match this where the question is
     * whether to wait, and the cases under it where the question is what to tell somebody. The overload
     * that reads on an interval never answers one of these, because it places no watch to lose.
     */
    public sealed interface Stopped : WatchError

    /**
     * The directory that has to be watched could not be read; [cause] is kern's reason, and names it.
     *
     * The directory above the file is what a watch is really placed on, so a missing or unreadable one
     * stops the watch before the file is ever reached.
     */
    public data class FolderUnreadable(
        override val path: Path,
        public val cause: FileError,
    ) : Stopped

    /** [path] is a filesystem root, so there is no directory above it to place a watch on. */
    public data class NoFolderAbove(override val path: Path) : Stopped

    /**
     * Files on [filesystem] report no change at all, so a watcher waiting for one would never emit again.
     *
     * procfs and sysfs make a file's contents up as it is read, so there is no write for the kernel to
     * report and `inotify(7)` names both as unmonitorable. Read such a file on an interval instead, with
     * the [fileWatcher] overload that takes one.
     */
    public data class Unwatchable(
        override val path: Path,
        public val filesystem: Pseudofilesystem,
    ) : Stopped

    /** The watch was live and has stopped: the directory above [path] was deleted or unmounted. */
    public data class WatchEnded(override val path: Path) : Stopped

    /**
     * The system refused the watch itself, with the directory readable; [detail] is its own wording.
     *
     * Running out of watches is the reason to expect, `fs.inotify.max_user_watches` on Linux. This is the
     * one case here with nothing better than a message, because what else a system may refuse a watch for
     * is not a set anything can close, and the JDK throws a bare `IOException` for all of it.
     */
    public data class WatchRefused(
        override val path: Path,
        public val detail: String,
    ) : Stopped
}

/** The filesystems whose files report no change, which are the ones `inotify(7)` names as unmonitorable. */
public enum class Pseudofilesystem(internal val typeName: String) {
    Proc("proc"),
    Sysfs("sysfs"),
    DevPts("devpts"),
    ;

    internal companion object {
        /** The one a filesystem's own type name stands for, or null where files on it do report changes. */
        fun named(type: String): Pseudofilesystem? = entries.firstOrNull { it.typeName == type }
    }
}
