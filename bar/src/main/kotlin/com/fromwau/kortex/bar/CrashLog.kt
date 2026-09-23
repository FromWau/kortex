package com.fromwau.kortex.bar

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.IError
import com.fromwau.kern.result.Ok
import com.fromwau.kortex.compose.ContentFailure
import com.fromwau.kortex.wayland.KortexError
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.time.Clock

/** Appending a crash to the crash log failed. */
data class CrashLogWriteFailed(val cause: IOException) : IError

/**
 * Where the bar's crash log lives, per the XDG Base Directory spec: under the state home, defaulted to
 * `$HOME/.local/state` and ignored when it names a relative path.
 */
fun crashLogPath(env: Map<String, String>): Path {
    val stateHome = env["XDG_STATE_HOME"]
        ?.let(Path::of)
        ?.takeIf(Path::isAbsolute)
        ?: Path.of(
            checkNotNull(env["HOME"]) { "HOME is not set; cannot resolve the crash log's default directory" },
            ".local",
            "state",
        )
    return stateHome.resolve("kortex-bar").resolve("crash.log")
}

/** Appends [crash]'s time, surface name, failure kind and cause's stack trace to the log file at [path]. */
fun appendCrash(path: Path, crash: KortexError.SurfaceCrashed): EmptyResult<CrashLogWriteFailed> =
    try {
        Files.createDirectories(path.parent)
        Files.writeString(path, crashLogEntry(crash), StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        Ok(Unit)
    } catch (failure: IOException) {
        Err(CrashLogWriteFailed(failure))
    }

private fun crashLogEntry(crash: KortexError.SurfaceCrashed): String {
    val kind = when (crash.failure) {
        is ContentFailure.Composition -> "Composition"
        is ContentFailure.KeyInput -> "KeyInput"
        is ContentFailure.PointerInput -> "PointerInput"
    }
    return "${Clock.System.now()} ${crash.surface} $kind\n${crash.failure.cause.stackTraceToString()}\n"
}
