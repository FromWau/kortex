package com.fromwau.kortex.shell

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import kotlin.time.Duration

/** What a script printed and the code it exited with, whatever that code was. */
public data class ShellOutput(
    /** Exactly as printed, trailing newline included. */
    public val stdout: String,
    public val stderr: String,
    public val exitCode: Int,
)

/**
 * Runs [script] with `bash -c` and waits for it to finish.
 *
 * ```kotlin
 * val ssid = shell("nmcli -t -f active,ssid dev wifi | grep '^yes'")
 *     .map { it.stdout.substringAfter(':').trimEnd() }
 * ```
 *
 * The script inherits this process's environment and working directory, and reads an empty stdin. A script that
 * exits non-zero is still `Ok`: `grep` finding nothing exits 1, which is an answer rather than a failure.
 *
 * Everything the script starts belongs to it. When the script exits, runs past [timeout] or the caller is
 * cancelled, whatever it started that is still running is killed with it, `cmd &` included, so nothing outlives
 * the call and output printed after the script's own exit is not captured. A process meant to outlive it is
 * started with `setsid` and its output redirected, `setsid cmd >/dev/null 2>&1 &`: one that keeps the script's
 * stdout or stderr open keeps this call waiting for it.
 *
 * @param script bash, run exactly as given.
 * @param timeout how long to wait before killing the script; null waits for as long as it takes.
 * @return the output and exit code once the script has ended, or a [ShellError] when it could not start or ran
 *   past [timeout].
 */
public suspend fun shell(
    script: String,
    timeout: Duration? = null,
): Result<ShellOutput, ShellError> = shell(script, timeout, BASH)

internal suspend fun shell(
    script: String,
    timeout: Duration?,
    executable: String,
): Result<ShellOutput, ShellError> {
    val bash = onPath(executable) ?: return Err(ShellError.NotStarted("$executable is not on PATH"))
    // A session of its own makes the script a process group leader, so one signal reaches all it started.
    val process = try {
        ProcessBuilder(SETSID, bash, "-c", script).start()
    } catch (failure: IOException) {
        return Err(ShellError.NotStarted(failure.message ?: SETSID))
    }
    process.outputStream.close()
    val group = Group(bash, process.pid())

    return coroutineScope {
        // Both at once: a script that fills one pipe while nobody reads it never exits.
        val stdout = async(Dispatchers.IO) { process.inputStream.readAllBytes().decodeToString() }
        val stderr = async(Dispatchers.IO) { process.errorStream.readAllBytes().decodeToString() }

        val exitCode = try {
            when (timeout) {
                null -> process.awaitExit()
                else -> withTimeoutOrNull(timeout) { process.awaitExit() }
            }
        } catch (cancelled: CancellationException) {
            group.kill()
            throw cancelled
        }
        // Before the readers are waited on: they reach the end of their pipes only once every writer is gone.
        group.kill()

        when (exitCode) {
            null -> Err(ShellError.TimedOut(checkNotNull(timeout), stdout.await()))
            else -> Ok(ShellOutput(stdout.await(), stderr.await(), exitCode))
        }
    }
}

/** The process group a script runs in, which `setsid` made it the leader of. */
private class Group(
    private val bash: String,
    private val id: Long,
) {
    /** SIGKILL to every member at once, with nothing started in between left out. An empty group is no error. */
    fun kill() {
        ProcessBuilder(bash, "-c", "kill -KILL -- -$id 2>/dev/null")
            .start()
            .waitFor()
    }
}

private suspend fun Process.awaitExit(): Int = runInterruptible(Dispatchers.IO) { waitFor() }

/** [name] as the path a lookup through `PATH` finds, or [name] itself where it already is a path. */
private fun onPath(name: String): String? {
    if ('/' in name) return name.takeIf { File(it).canExecute() }
    val folders = System.getenv("PATH")?.split(':').orEmpty()
    return folders
        .map { folder -> File(folder, name) }
        .firstOrNull { it.canExecute() }
        ?.path
}

private const val BASH = "bash"
private const val SETSID = "setsid"
