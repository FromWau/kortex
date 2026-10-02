package com.fromwau.kortex.shell

import com.fromwau.kern.result.IError
import kotlin.time.Duration

/**
 * Why a script did not run to its end.
 *
 * A script that ran and exited non-zero is not one of these: that is a [ShellOutput] with its [ShellOutput.exitCode].
 */
public sealed interface ShellError : IError {
    /** The code a shell would report for this, so a caller can treat every outcome as an exit code if it likes. */
    public val exitCode: Int

    /** `bash` could not be started; [detail] is the system's own wording. */
    public data class NotStarted(public val detail: String) : ShellError {
        /** 127, what a shell reports for a command it could not find. */
        override val exitCode: Int get() = 127
    }

    /** The script was still running [after] its timeout and was killed, with everything it had started. */
    public data class TimedOut(
        public val after: Duration,
        /** What it had printed to stdout before it was killed. */
        public val stdout: String,
    ) : ShellError {
        /** 124, what coreutils `timeout` reports for a command it had to stop. */
        override val exitCode: Int get() = 124
    }
}
