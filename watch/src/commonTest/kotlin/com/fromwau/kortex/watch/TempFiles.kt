package com.fromwau.kortex.watch

import com.fromwau.kern.dirs.div
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.writeString
import kotlin.random.Random

// Linux native's SystemTemporaryDirectory is relative when neither TMPDIR nor TMP is set, which no test
// can write into.
private val tempBase: Path = SystemTemporaryDirectory.takeIf { it.isAbsolute } ?: Path("/tmp")

// One folder per test process, so two runs against different targets never share a file.
private val runDir: Path = (tempBase / "kortex-watch-${Random.nextLong()}").also {
    SystemFileSystem.createDirectories(it)
}

internal fun newTempDir(): Path = (runDir / Random.nextLong().toString()).also {
    SystemFileSystem.createDirectories(it)
}

/** Writes through kotlinx-io directly, so a test's setup never leans on the code under test. */
internal fun Path.writeRaw(text: String): Path = also { path ->
    SystemFileSystem.sink(path).buffered().use { sink -> sink.writeString(text) }
}

/**
 * Writes [text] beside this path and renames it over it, which is how an editor saves.
 *
 * Tests use this rather than [writeRaw] wherever they assert on an exact sequence of values: a write in
 * place empties the file first, so a watcher can read between the emptying and the writing and see a file
 * that was briefly blank. That is real behaviour, pinned by its own test, rather than something to hide.
 */
internal fun Path.saveOver(text: String): Path = also { path ->
    val staging = Path("$path.staging")
    staging.writeRaw(text)
    SystemFileSystem.atomicMove(staging, path)
}

internal fun Path.deleteRaw() {
    SystemFileSystem.delete(this, mustExist = false)
}
