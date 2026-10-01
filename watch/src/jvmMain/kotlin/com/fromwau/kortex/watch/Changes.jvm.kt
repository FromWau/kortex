package com.fromwau.kortex.watch

import com.fromwau.kern.dirs.list
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.errorOrNull
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.io.files.Path
import java.io.IOException
import java.nio.file.Path as JvmPath
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.StandardWatchEventKinds.OVERFLOW
import java.nio.file.WatchService

internal actual val blockingReads: CoroutineDispatcher = Dispatchers.IO

/**
 * inotify, through the JDK's own binding to it.
 *
 * The directory is registered rather than the file. An editor saves by writing a temporary file and
 * renaming it over the original, which never touches the original's inode, and a file that does not exist
 * yet has no inode to watch at all; both reach a watch on the directory.
 */
internal actual fun changes(path: Path): Flow<Result<Unit, WatchError.Stopped>> = channelFlow {
    val file = JvmPath.of(path.toString()).toAbsolutePath()
    val folder = file.parent ?: run {
        send(Err(WatchError.NoFolderAbove(path)))
        return@channelFlow
    }

    unreporting(folder)?.let { filesystem ->
        send(Err(WatchError.Unwatchable(path, filesystem)))
        return@channelFlow
    }

    val service = try {
        FileSystems.getDefault().newWatchService()
    } catch (failure: IOException) {
        // Nothing about the directory: the system would not hand out a watch at all.
        send(Err(WatchError.WatchRefused(path, failure.reason)))
        return@channelFlow
    }
    try {
        folder.register(service, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE)
    } catch (failure: IOException) {
        service.close()
        send(Err(folder.whyNotWatched(failure, path)))
        return@channelFlow
    }

    // Live, and that is itself the first signal: it is what makes the first read happen.
    send(Ok(Unit))
    launch(blockingReads) { report(service, path, name = file.fileName) }

    // A blocking take does not notice a cancelled coroutine, so closing the service is what ends it, the
    // way closing a socket is what ends a read on one.
    awaitClose { service.close() }
}

/** Sends a signal for each change to [file] until the service closes or stops reporting. */
private suspend fun ProducerScope<Result<Unit, WatchError.Stopped>>.report(
    service: WatchService,
    path: Path,
    name: JvmPath,
) {
    while (true) {
        val key = try {
            service.take()
        } catch (_: ClosedWatchServiceException) {
            return
        }

        // An overflow means events were dropped, so what happened is unknown and a read is the only
        // answer. Everything else is filtered to this one file, since the whole directory is watched.
        val ours = key.pollEvents().any { event -> event.kind() == OVERFLOW || event.context() == name }
        val alive = key.reset()

        if (ours) send(Ok(Unit))
        if (!alive) {
            send(Err(WatchError.WatchEnded(path)))
            close()
            return
        }
    }
}

/**
 * [folder]'s filesystem when it is one whose files report no change, else null.
 *
 * Asked of the filesystem rather than of the path's spelling: a prefix test would be a guess, and
 * `getFileStore` is the kernel's own answer. A folder that cannot be looked up is not refused here, since
 * the attempt that follows says why in better words.
 */
private fun unreporting(folder: JvmPath): Pseudofilesystem? {
    val type = try {
        Files.getFileStore(folder).type()
    } catch (_: IOException) {
        return null
    }
    return Pseudofilesystem.named(type)
}

/**
 * Why a watch on this directory was refused, asked of kern rather than read off [failure].
 *
 * A listing is what tells a missing directory from one that may not be read, and it also answers the case
 * no exception type covers: a directory that reads perfectly, where the refusal was about the system's own
 * limit on watches rather than about the directory at all.
 */
private fun JvmPath.whyNotWatched(failure: IOException, path: Path): WatchError.Stopped =
    Path(toString()).list().errorOrNull()
        ?.let { folder -> WatchError.FolderUnreadable(path, folder) }
        ?: WatchError.WatchRefused(path, failure.reason)

private val IOException.reason: String get() = message ?: toString()
