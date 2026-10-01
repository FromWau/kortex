package com.fromwau.kortex.watch

import com.fromwau.kern.dirs.FileError
import com.fromwau.kern.dirs.FileType
import com.fromwau.kern.dirs.div
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Both overloads against the real filesystem, since what is being tested is what the operating system does.
 *
 * Nothing here needs a desktop, a compositor or a bus, which makes this the one kortex module whose whole
 * suite runs on a bare machine.
 */
class WatchTextTest {
    @Test
    fun `the first value is the file as it stands, before anything changes`() = watchTest {
        val file = (newTempDir() / "config").writeRaw("one")

        assertEquals(Ok("one"), file.watchText().first())
    }

    @Test
    fun `a file replaced by a save is read again`() = watchTest {
        val file = (newTempDir() / "config").writeRaw("one")

        watching(file.watchText()) { reads ->
            assertEquals(Ok("one"), reads.receive())
            file.saveOver("two")
            assertEquals(Ok("two"), reads.receive())
        }
    }

    /** The reason a caller needs no `distinctUntilChanged()` of their own. */
    @Test
    fun `a save that leaves the contents identical emits nothing`() = watchTest {
        val file = (newTempDir() / "config").writeRaw("one")

        watching(file.watchText()) { reads ->
            assertEquals(Ok("one"), reads.receive())

            file.saveOver("one")
            file.saveOver("two")

            assertEquals(Ok("two"), reads.receive(), "the identical save emitted a value of its own")
        }
    }

    @Test
    fun `a file that is not there yet is reported, and so is its arrival`() = watchTest {
        val file = newTempDir() / "later"

        watching(file.watchText()) { reads ->
            assertEquals(Err(WatchError.Unreadable(file, FileError.NotFound(file))), reads.receive())
            file.saveOver("here now")
            assertEquals(Ok("here now"), reads.receive())
        }
    }

    @Test
    fun `a deleted file is reported, and so is its return`() = watchTest {
        val file = (newTempDir() / "config").writeRaw("one")

        watching(file.watchText()) { reads ->
            assertEquals(Ok("one"), reads.receive())

            file.deleteRaw()
            assertEquals(Err(WatchError.Unreadable(file, FileError.NotFound(file))), reads.receive())

            file.saveOver("back")
            assertEquals(Ok("back"), reads.receive())
        }
    }

    /**
     * A write in place empties the file before filling it, so a blank read is possible and real.
     *
     * Pinned rather than hidden: it is why a caller who cares debounces, and why every other test here
     * saves atomically. What must hold is that the contents arrive, not that nothing intermediate does.
     */
    @Test
    fun `a write in place arrives, whatever is read on the way`() = watchTest {
        val file = (newTempDir() / "config").writeRaw("one")

        watching(file.watchText()) { reads ->
            assertEquals(Ok("one"), reads.receive())
            file.writeRaw("rewritten in place")
            reads.until(Ok("rewritten in place"))
        }
    }

    /**
     * The whole reason the interval overload exists, and the case that silently fails without this check.
     *
     * procfs makes a file's contents up as it is read, so inotify has no write to report. The watch is
     * accepted by the kernel and then reports nothing forever, which is a widget that looks fine and never
     * updates, so the watcher refuses instead and names the filesystem.
     */
    @Test
    fun `a procfs file is refused rather than watched in silence`() = watchTest {
        val file = Path("/proc/meminfo")

        val values = file.watchText().toList()

        assertEquals(listOf(Err(WatchError.Unwatchable(file, Pseudofilesystem.Proc))), values)
    }

    @Test
    fun `a file whose directory is not there says so about the directory`() = watchTest {
        val folder = newTempDir() / "missing"
        val file = folder / "config"

        val values = file.watchText().toList()

        assertEquals(listOf(Err(WatchError.FolderUnreadable(file, FileError.NotFound(folder)))), values)
    }

    @Test
    fun `a filesystem root has no directory above it to watch`() = watchTest {
        val root = Path("/")

        val values = root.watchText().toList()

        assertEquals(listOf(Err(WatchError.NoFolderAbove(root))), values)
    }

    /** A watch that was live and stops is its own answer, and not the same as one that never started. */
    @Test
    fun `a watch whose directory is deleted ends and says so`() = watchTest {
        val folder = newTempDir()
        val file = (folder / "config").writeRaw("one")

        watching(file.watchText()) { reads ->
            assertEquals(Ok("one"), reads.receive())

            file.deleteRaw()
            folder.deleteRaw()

            reads.until(Err(WatchError.WatchEnded(file)))
        }
    }

    /** And the interval overload reads it, which is the other half of that answer. */
    @Test
    fun `an interval watcher sees a file procfs makes up as it reads`() = watchTest {
        val file = Path("/proc/meminfo")

        watching(file.readTextEvery(50.milliseconds)) { reads ->
            val first = assertIs<Ok<String>>(reads.receive())
            // Moves MemAvailable, so the next read differs without waiting on whatever else the machine does.
            val ballast = ByteArray(64 * 1024 * 1024) { 1 }
            val second = assertIs<Ok<String>>(reads.receive())

            assertNotEquals(first.value, second.value, "two reads of /proc/meminfo were identical")
            assertEquals(1, ballast[0], "the allocation was optimised away, so nothing moved")
        }
    }

    @Test
    fun `an interval watcher emits only what changed`() = watchTest {
        val file = (newTempDir() / "config").writeRaw("one")

        watching(file.readTextEvery(50.milliseconds)) { reads ->
            assertEquals(Ok("one"), reads.receive())
            file.saveOver("two")
            assertEquals(Ok("two"), reads.receive(), "an unchanged read emitted a value")
        }
    }

    @Test
    fun `a file past the limit a caller set is too large to read`() = watchTest {
        val file = (newTempDir() / "config").writeRaw("more than two bytes")

        val value = file.readTextEvery(50.milliseconds, maxBytes = 2).first()

        assertEquals(Err(WatchError.Unreadable(file, FileError.TooLarge(file, 2, 19))), value)
    }

    @Test
    fun `a directory is not a file to watch`() = watchTest {
        val dir = newTempDir()

        val value = dir.readTextEvery(50.milliseconds).first()

        assertEquals(
            Err(WatchError.Unreadable(dir, FileError.NotRegularFile(dir, FileType.Directory))),
            value,
        )
    }

    /** Cold, so two widgets on one file each read for themselves unless the caller shares the flow. */
    @Test
    fun `each collector reads for itself`() = watchTest {
        val file = (newTempDir() / "config").writeRaw("one")
        val watcher = file.watchText()

        assertEquals(Ok("one"), watcher.first())
        assertEquals(Ok("one"), watcher.first())
    }

    /**
     * A test with a budget of its own, since every one here waits on the operating system.
     *
     * `runTest`'s own minute is too long to wait for a watcher that has gone quiet, which is the failure
     * this module exists to prevent and so the one most likely to be seen here.
     */
    private fun watchTest(body: suspend TestScope.() -> Unit) = runTest(timeout = BUDGET, testBody = body)

    /** Collects [flow] in the background for the length of [body], handing over what it emits in order. */
    private suspend fun <T> watching(
        flow: Flow<Result<String, WatchError>>,
        body: suspend (ReceiveChannel<Result<String, WatchError>>) -> T,
    ): T = coroutineScope {
        val reads = Channel<Result<String, WatchError>>(Channel.UNLIMITED)
        val collecting = launch(Dispatchers.Default) { flow.collect(reads::send) }
        try {
            body(reads)
        } finally {
            collecting.cancel()
        }
    }

    /** Receives until [expected] arrives, letting the test's own timeout be the failure. */
    private suspend fun ReceiveChannel<Result<String, WatchError>>.until(
        expected: Result<String, WatchError>,
    ) {
        while (receive() != expected) continue
    }

    private companion object {
        val BUDGET = 15.seconds
    }
}
