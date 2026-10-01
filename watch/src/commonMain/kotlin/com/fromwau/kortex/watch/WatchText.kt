package com.fromwau.kortex.watch

import com.fromwau.kern.dirs.readText
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.fold
import com.fromwau.kern.result.mapError
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.io.files.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * This file's text, and its text again each time the file changes, as the operating system reports it.
 *
 * ```kotlin
 * val config: StateFlow<Settings?> = Path("$home/.config/mybar.toml").watchText()
 *     .map { read -> read.getOrNull()?.let(::parseSettings) }
 *     .stateIn(scope, SharingStarted.WhileSubscribed(), null)
 * ```
 *
 * Nothing is read until something collects, and the reading stops with the last collector. Two collectors
 * each get their own, so share one flow where that matters, with `stateIn` or `shareIn`.
 *
 * This one can end. A [WatchError.Stopped] is the last value, and the flow completes behind it, because
 * there is no watch left to report anything. Everything else leaves it running.
 *
 * The first value is always the file as it stands, or why it could not be read, so a collector never waits
 * for a change to learn where it is starting from. After that only changes arrive: a write that leaves the
 * contents identical emits nothing, and neither does a repeat of the same failure, so there is no need to
 * put `distinctUntilChanged()` after this.
 *
 * Reading happens off the collector's thread, because it blocks and a shell's own thread is also the one
 * that draws.
 *
 * **Not every file can be watched this way.** Files under procfs and sysfs, which is most of what a status
 * bar reads, report no changes at all: the kernel makes their contents up as they are read, so there is
 * nothing to report. Those answer [WatchError.Unwatchable] as their first and only value, naming the
 * filesystem, and want [readTextEvery] instead.
 *
 * A file that is not there yet is not an error that ends anything: the first value says it is missing, and
 * the contents arrive if it is created. A file saved by writing a temporary copy and renaming it over the
 * original, which is how editors save, is followed across the rename.
 *
 * Finding the file is yours, not this module's. A sysfs reading rarely has a path you can write down:
 * `/sys/class/hwmon/hwmonN` is numbered in probe order and not stable across boots, and which `tempN_input`
 * is the one you want is decided by the `tempN_label` beside it. List and choose with `kern:dirs`, whose
 * `list` and `walkTopDown` are already on this module's classpath, then watch what you found.
 *
 * @param maxBytes the largest read, in bytes; a file above it is [WatchError.Unreadable] carrying kern's
 *   own `FileError.TooLarge`. Null, the default, reads whatever is there however large.
 */
public fun Path.watchText(
    maxBytes: Long? = null,
): Flow<Result<String, WatchError>> = changes(this)
    .map { signal -> signal.fold({ text(maxBytes) }, { stopped -> Err(stopped) }) }
    .distinctUntilChanged()
    .flowOn(blockingReads)

/**
 * This file's text, read again every [every], and emitted when it has changed.
 *
 * ```kotlin
 * val memory: StateFlow<MemInfo?> = Path("/proc/meminfo").readTextEvery(2.seconds)
 *     .map { read -> read.getOrNull()?.let(::parseMemInfo) }
 *     .stateIn(scope, SharingStarted.WhileSubscribed(), null)
 * ```
 *
 * This is the one for procfs and sysfs, and for anything on a filesystem whose changes do not reach this
 * machine, a network share being the usual one. It asks the operating system nothing: it reads, waits
 * [every], and reads again, which is the only thing that works where a file's contents are made up as they
 * are read and both its size and its timestamp stand still.
 *
 * Everything else matches [watchText], deliberately, so that moving a widget between them changes nothing
 * but the call: the same success and error types, the first value always the file as it stands, and nothing
 * emitted for a read that found no change.
 *
 * **So [every] is not the time between values.** A file that stops changing emits nothing until it changes
 * again, which is what makes a readout simple and what makes a rate wrong: anything dividing by [every] to
 * get a per-second figure will overstate it by however many quiet intervals went by. Timestamp each value
 * and divide by the gap you measured. A counter in procfs, bytes through an interface being the usual one,
 * is where this bites.
 *
 * Unlike [watchText] this never completes, and it never answers [WatchError.Stopped], because it places no
 * watch that could be lost. A file that cannot be read is [WatchError.Unreadable] and the next read tries
 * again.
 *
 * @param every how long to wait between reads. There is no interval that suits both a temperature and a
 *   config file, so this is what a caller spends their bar's time on.
 * @param maxBytes as [watchText]'s.
 */
public fun Path.readTextEvery(
    every: Duration = 1.seconds,
    maxBytes: Long? = null,
): Flow<Result<String, WatchError>> = flow {
    while (true) {
        emit(text(maxBytes))
        delay(every)
    }
}
    .distinctUntilChanged()
    .flowOn(blockingReads)

private fun Path.text(maxBytes: Long?): Result<String, WatchError> =
    readText(maxBytes).mapError { failure -> WatchError.Unreadable(this, failure) }
