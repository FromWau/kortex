package com.fromwau.kortex.hyprland

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result

/**
 * One running Hyprland, as the folder holding its two sockets.
 *
 * @param folder the directory with `.socket.sock` and `.socket2.sock` in it, which for a real session is
 *   `$XDG_RUNTIME_DIR/hypr/$HYPRLAND_INSTANCE_SIGNATURE`.
 */
public data class HyprlandInstance(public val folder: String) {
    internal val requests: String get() = "$folder/.socket.sock"
    internal val events: String get() = "$folder/.socket2.sock"

    public companion object {
        /** The Hyprland this process was started under, or [HyprlandError.NoInstance] when there is none. */
        public fun fromEnvironment(): Result<HyprlandInstance, HyprlandError.NoInstance> =
            fromEnvironment(System.getenv())

        internal fun fromEnvironment(
            environment: Map<String, String>,
        ): Result<HyprlandInstance, HyprlandError.NoInstance> {
            val runtime = environment[RUNTIME_DIR]?.takeIf { it.isNotEmpty() }
            val signature = environment[SIGNATURE]?.takeIf { it.isNotEmpty() }
            if (runtime == null || signature == null) return Err(HyprlandError.NoInstance)
            return Ok(HyprlandInstance("$runtime/hypr/$signature"))
        }

        private const val RUNTIME_DIR = "XDG_RUNTIME_DIR"
        private const val SIGNATURE = "HYPRLAND_INSTANCE_SIGNATURE"
    }
}
