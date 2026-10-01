package com.fromwau.kortex.watch

import com.fromwau.kern.result.Result
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.io.files.Path

/**
 * Every change the platform reports at [path], as signals rather than as contents.
 *
 * What each platform promises:
 * - `Ok(Unit)` once as soon as the watch is live, which is what makes the first read happen.
 * - `Ok(Unit)` for each change to the file at [path], a rename over it included, and for a dropped-event
 *   overflow, where "read it again" is the only honest thing left to say.
 * - One [WatchError.Stopped], then completion, when the watch cannot be set up or has stopped reporting. A
 *   platform that cannot report changes for [path] at all says so here rather than going quiet.
 *
 * The error type is the public one, narrowed to the half a watch answers for. Reading the file belongs to
 * this function's caller, so [WatchError.Unreadable] is not a value any platform here can produce, and the
 * two sets stay one set rather than two that have to be kept in step.
 *
 * Nothing is thrown: a mechanism that fails halfway is a value, like every other failure here.
 */
internal expect fun changes(path: Path): Flow<Result<Unit, WatchError.Stopped>>

/**
 * Where a read runs, since reading a file blocks and the thread a shell draws on must not.
 *
 * Declared per platform rather than written as `Dispatchers.IO`, which is not part of coroutines' common
 * API: it resolves in common code only while a module has one target, and stops the moment it has several.
 */
internal expect val blockingReads: CoroutineDispatcher
