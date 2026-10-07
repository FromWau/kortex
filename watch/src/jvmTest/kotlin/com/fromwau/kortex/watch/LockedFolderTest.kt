package com.fromwau.kortex.watch

import com.fromwau.kern.dirs.FileError
import com.fromwau.kern.dirs.div
import com.fromwau.kern.result.errorOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path as JvmPath
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/** On the JVM alone, since taking a directory's permissions away needs the platform's own file API. */
class LockedFolderTest {
    /** Unlike a missing one, which is waited for: nothing in a folder this process may not read is reported. */
    @Test
    fun `a folder that may not be read stops the watch and says so`() = runBlocking<Unit> {
        val folder = newTempDir() / "locked"
        val file = folder / "config"
        val locked = Files.createDirectory(JvmPath.of(folder.toString()))
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"))

        try {
            val values = withTimeout(10.seconds) { file.watchText().toList() }

            val refusal = assertIs<WatchError.FolderUnreadable>(values.single().errorOrNull())
            assertEquals(file, refusal.path)
            assertEquals(folder, assertIs<FileError.Inaccessible>(refusal.cause).path)
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"))
        }
    }
}
