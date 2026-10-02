package com.fromwau.kortex.shell

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ShellTest {
    @Test
    fun stdoutComesBackExactlyAsPrinted() = runBlocking {
        assertEquals(Ok(ShellOutput("hi\n", "", 0)), shell("echo hi"))
    }

    @Test
    fun stderrAndANonZeroExitAreStillAnAnswer() = runBlocking {
        assertEquals(Ok(ShellOutput("out\n", "err\n", 3)), shell("echo out; echo err >&2; exit 3"))
    }

    @Test
    fun grepFindingNothingIsOkWithItsExitCode() = runBlocking {
        assertEquals(Ok(ShellOutput("", "", 1)), shell("printf 'a\\n' | grep b"))
    }

    @Test
    fun aMultilineScriptRunsAsBash() = runBlocking {
        val script = """
            total=0
            for n in 1 2 3; do
                total=$((total + n))
            done
            echo "${'$'}total"
        """.trimIndent()

        assertEquals("6\n", stdoutOf(shell(script)))
    }

    @Test
    fun aMegabyteOnBothStreamsDoesNotStall() = runBlocking {
        val script = "head -c $MEGABYTE /dev/zero | tr '\\0' a; head -c $MEGABYTE /dev/zero | tr '\\0' b >&2"

        val output = assertIs<Ok<ShellOutput>>(withTimeout(TIMEOUT) { shell(script) }).value

        assertEquals(MEGABYTE, output.stdout.length)
        assertEquals(MEGABYTE, output.stderr.length)
    }

    @Test
    fun stdinIsEmptySoAReadEndsAtOnce() = runBlocking {
        assertEquals(Ok(ShellOutput("", "", 0)), withTimeout(TIMEOUT) { shell("cat") })
    }

    @Test
    fun aTimeoutKillsTheScriptAndWhatItStarted() = runBlocking {
        val pidFile = Files.createTempFile("kortex-shell", ".pid")

        // Bounded well under the sleep: a child left running holds stdout open, and the call would wait it out.
        val result = withTimeout(TIMEOUT) {
            shell("echo started; sleep 30 & echo $! > '$pidFile'; wait", timeout = 500.milliseconds)
        }

        assertEquals(Err(ShellError.TimedOut(500.milliseconds, "started\n")), result)
        assertEquals(124, (result as Err).error.exitCode)
        assertGone(pidFile)
    }

    @Test
    fun cancellingTheCallerKillsTheScriptAndWhatItStarted() = runBlocking {
        val pidFile = Files.createTempFile("kortex-shell", ".pid")
        Files.delete(pidFile)

        val caller = launch(Dispatchers.Default) { shell("sleep 30 & echo $! > '$pidFile'; wait") }
        withTimeout(TIMEOUT) { while (!pidFile.exists() || pidFile.readText().isBlank()) delay(10) }
        withTimeout(TIMEOUT) {
            caller.cancel()
            caller.join()
        }

        assertGone(pidFile)
    }

    @Test
    fun whatTheScriptLeftRunningIsKilledWhenItExits() = runBlocking {
        val pidFile = Files.createTempFile("kortex-shell", ".pid")

        // Nested, so the sleep is no child of bash's own and only its process group still finds it.
        val result = withTimeout(TIMEOUT) { shell("( (sleep 30; echo late) & echo $! > '$pidFile' ); echo done") }

        assertEquals(Ok(ShellOutput("done\n", "", 0)), result)
        assertGone(pidFile)
    }

    @Test
    fun aProcessStartedWithItsOwnSessionOutlivesTheScript() = runBlocking {
        val pidFile = Files.createTempFile("kortex-shell", ".pid")

        shell("setsid bash -c 'echo $$ > \"$pidFile\"; exec sleep 30' >/dev/null 2>&1 &")
        withTimeout(TIMEOUT) { while (pidFile.readText().isBlank()) delay(10) }
        val pid = pidFile.readText().trim().toLong()

        try {
            delay(200)
            assertTrue(ProcessHandle.of(pid).map { it.isAlive }.orElse(false), "the setsid process was killed")
        } finally {
            ProcessHandle.of(pid).ifPresent { it.destroyForcibly() }
        }
    }

    @Test
    fun aShellThatCannotStartSaysSoWithTheCodeForIt() = runBlocking {
        val result = shell("echo hi", timeout = null, executable = "kortex-no-such-shell")

        val error = assertIs<ShellError.NotStarted>((result as Err).error)
        assertEquals(127, error.exitCode)
    }

    private fun stdoutOf(result: Result<ShellOutput, ShellError>): String =
        assertIs<Ok<ShellOutput>>(result).value.stdout

    /** The process whose pid the script wrote to [pidFile] is no longer running. */
    private suspend fun assertGone(pidFile: Path) {
        val pid = pidFile.readText().trim().toLong()
        withTimeout(TIMEOUT) {
            while (ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) delay(10)
        }
        assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false), "process $pid outlived the script")
    }

    private companion object {
        const val MEGABYTE = 1_048_576
        val TIMEOUT = 5.seconds
    }
}
