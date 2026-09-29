package com.fromwau.kortex.dbus

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import java.nio.file.Path

/** Where the session bus listens, as much of the address grammar as a JVM socket can reach. */
internal data class BusAddress(val path: String) {
    companion object {
        private const val SESSION_VARIABLE = "DBUS_SESSION_BUS_ADDRESS"
        private const val RUNTIME_VARIABLE = "XDG_RUNTIME_DIR"
        private const val UNIX = "unix:"

        /**
         * Where this session's bus is, from the environment.
         *
         * `DBUS_SESSION_BUS_ADDRESS` first, then the `$XDG_RUNTIME_DIR/bus` that systemd puts there and
         * that the specification names as the fallback.
         */
        fun fromEnvironment(environment: (String) -> String? = System::getenv): Result<BusAddress, DBusError> {
            environment(SESSION_VARIABLE)?.let { return parse(it) }

            val runtime = environment(RUNTIME_VARIABLE) ?: return Err(DBusError.NoSessionBus)
            return Ok(BusAddress("$runtime/bus"))
        }

        /**
         * One address out of the semicolon-separated list the variable may hold.
         *
         * The first one a JVM socket can reach wins, which is what a client is meant to do with a list.
         * `unix:abstract=` cannot be reached at all: the abstract namespace needs a leading NUL in the
         * address and [java.net.UnixDomainSocketAddress] has no way to write one.
         */
        fun parse(address: String): Result<BusAddress, DBusError> {
            address
                .split(';')
                .filter { it.isNotBlank() }
                .forEach { candidate -> pathOf(candidate)?.let { return Ok(BusAddress(it)) } }
            return Err(DBusError.UnreachableBusAddress(address))
        }

        private fun pathOf(candidate: String): String? {
            if (!candidate.startsWith(UNIX)) return null

            val keys = candidate
                .removePrefix(UNIX)
                .split(',')
                .mapNotNull { pair ->
                    val key = pair.substringBefore('=', missingDelimiterValue = "")
                    key.takeIf { it.isNotEmpty() }?.to(pair.substringAfter('='))
                }
                .toMap()

            keys["path"]?.let { return it.unescaped() }
            // "runtime=yes" is the only value the key takes, and it means the same place as the fallback.
            if (keys["runtime"] == "yes") return System.getenv(RUNTIME_VARIABLE)?.let { "$it/bus" }
            return null
        }

        /** The address grammar percent-encodes anything outside its optionally-escaped set. */
        private fun String.unescaped(): String = Regex("%([0-9A-Fa-f]{2})")
            .replace(this) { match -> match.groupValues[1].toInt(radix = 16).toChar().toString() }
    }
}

/**
 * This process's user id, which `EXTERNAL` authentication is the whole of.
 *
 * Read off `/proc/self` rather than through `jdk.security.auth`, so no extra module is needed. A bus that
 * refuses it answers `REJECTED`, which is [DBusError.AuthenticationRejected] and not a crash.
 */
internal fun currentUid(procSelf: Path = Path.of("/proc/self")): Result<Int, DBusError> = try {
    Ok(java.nio.file.Files.getAttribute(procSelf, "unix:uid") as Int)
} catch (failure: java.io.IOException) {
    Err(DBusError.NoCredentials(failure.message.orEmpty()))
} catch (failure: UnsupportedOperationException) {
    Err(DBusError.NoCredentials(failure.message.orEmpty()))
}
