package com.fromwau.kortex.watch

import com.fromwau.kern.dirs.list
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.errorOrNull
import com.fromwau.kern.result.getOrElse
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
import java.nio.file.NoSuchFileException
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.StandardWatchEventKinds.OVERFLOW
import java.nio.file.WatchEvent
import java.nio.file.WatchKey
import java.nio.file.WatchService

internal actual val blockingReads: CoroutineDispatcher = Dispatchers.IO

/**
 * inotify, through the JDK's own binding to it.
 *
 * The directory is registered rather than the file. An editor saves by writing a temporary file and
 * renaming it over the original, which never touches the original's inode, and a file that does not exist
 * yet has no inode to watch at all; both reach a watch on the directory. While the directory itself is
 * missing, the nearest one above it that exists is watched instead, for the next directory down to appear.
 */
internal actual fun changes(path: Path): Flow<Result<Unit, WatchError.Stopped>> = channelFlow {
    val file = JvmPath.of(path.toString()).toAbsolutePath()
    val folder = file.parent ?: run {
        send(Err(WatchError.NoFolderAbove(path)))
        return@channelFlow
    }

    unreporting(folder.nearestExisting())?.let { filesystem ->
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

    launch(blockingReads) { follow(service, path, file) }

    // A blocking take does not notice a cancelled coroutine, so closing the service is what ends it, the
    // way closing a socket is what ends a read on one.
    awaitClose { service.close() }
}

/**
 * Sends a signal for each change to [file] until the service closes or a watch cannot be placed.
 *
 * Whenever the watch moves, down to a directory that has appeared or up from one that was deleted, the file
 * may have come or gone with it, so that is a signal too.
 */
private suspend fun ProducerScope<Result<Unit, WatchError.Stopped>>.follow(
    service: WatchService,
    path: Path,
    file: JvmPath,
) {
    val folder = file.parent
    var watch = service.watch(folder, path).getOrElse { stopped ->
        send(Err(stopped))
        close()
        return
    }

    // Live, and that is itself the first signal: it is what makes the first read happen.
    send(Ok(Unit))

    while (true) {
        val key = try {
            service.take()
        } catch (_: ClosedWatchServiceException) {
            return
        }

        val events = key.pollEvents()
        val alive = key.reset()
        // A key cancelled when the watch moved can still hand over what it saw before.
        if (key != watch.key) continue

        val awaited = if (watch.dir == folder) file.fileName else folder.nextBelow(watch.dir)
        val signalled = events.any { event -> event.isAbout(awaited) }

        if (alive && (watch.dir == folder || !signalled)) {
            if (signalled) send(Ok(Unit))
            continue
        }

        key.cancel()
        watch = service.watch(folder, path).getOrElse { stopped ->
            send(Err(stopped))
            close()
            return
        }
        send(Ok(Unit))
    }
}

/** A live watch on [dir], which is the folder above the file or, while that is missing, an ancestor of it. */
private class Watch(
    val dir: JvmPath,
    val key: WatchKey,
)

/**
 * A watch on [folder], or on the nearest directory above it that exists.
 *
 * An ancestor is watched only for creations, since all it has to report is the next directory down
 * appearing. One made between looking and watching would never be reported, so it is looked for again
 * once the watch is live.
 */
private fun WatchService.watch(
    folder: JvmPath,
    path: Path,
): Result<Watch, WatchError.Stopped> {
    while (true) {
        val dir = folder.nearestExisting()
        val kinds = if (dir == folder) arrayOf(ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE) else arrayOf(ENTRY_CREATE)
        val key = try {
            dir.register(this, *kinds)
        } catch (_: NoSuchFileException) {
            continue
        } catch (failure: IOException) {
            return Err(dir.whyNotWatched(failure, path))
        }

        if (dir != folder && Files.exists(dir.resolve(folder.nextBelow(dir)))) {
            key.cancel()
            continue
        }
        return Ok(Watch(dir, key))
    }
}

/** This directory if it exists, else the nearest one above it that does, which the root always is. */
private fun JvmPath.nearestExisting(): JvmPath =
    generateSequence(this) { it.parent }.first { Files.isDirectory(it) }

/** The name of the directory one level down from [ancestor] on the way to this one. */
private fun JvmPath.nextBelow(ancestor: JvmPath): JvmPath = ancestor.relativize(this).getName(0)

/** An overflow is about everything, since what was dropped is unknown and a read is the only answer. */
private fun WatchEvent<*>.isAbout(name: JvmPath): Boolean = kind() == OVERFLOW || context() == name

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
 * A listing is what tells a directory that may not be read from one that reads perfectly, where the
 * refusal was about the system's own limit on watches rather than about the directory at all.
 */
private fun JvmPath.whyNotWatched(failure: IOException, path: Path): WatchError.Stopped =
    Path(toString()).list().errorOrNull()
        ?.let { folder -> WatchError.FolderUnreadable(path, folder) }
        ?: WatchError.WatchRefused(path, failure.reason)

private val IOException.reason: String get() = message ?: toString()
