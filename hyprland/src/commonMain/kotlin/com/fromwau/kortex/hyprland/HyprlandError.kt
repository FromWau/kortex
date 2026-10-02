package com.fromwau.kortex.hyprland

import com.fromwau.kern.result.IError

/** Why a [Hyprland] flow is carrying no data. */
public sealed interface HyprlandError : IError {
    /**
     * Nobody is watching yet, or the first answer has not come back.
     *
     * Passes on its own, so a bar should draw nothing rather than report it.
     */
    public data object NotConnected : HyprlandError

    /** There is no Hyprland to ask: the cases under this one say how that was found out. */
    public sealed interface NotRunning : HyprlandError

    /** `HYPRLAND_INSTANCE_SIGNATURE` or `XDG_RUNTIME_DIR` is unset, so this process was not started under Hyprland. */
    public data object NoInstance : NotRunning

    /** Nothing exists at [path], where the instance's socket should be. */
    public data class NoSocket(public val path: String) : NotRunning

    /**
     * The socket at [path] exists and could not be used; [detail] is the system's own wording.
     *
     * Only a message, because what else a connect or a read may fail for is not a set anything can close,
     * and the JDK throws a bare `IOException` for all of it.
     */
    public data class Unreachable(
        public val path: String,
        public val detail: String,
    ) : HyprlandError

    /** The event socket was live and Hyprland closed it. The flow keeps trying to reconnect. */
    public data object Disconnected : HyprlandError

    /**
     * Hyprland answered [request] with [answer] instead of what was asked for: `unknown request` for a query it
     * does not know, or a Lua error for a dispatch it could not run.
     */
    public data class Refused(
        public val request: String,
        public val answer: String,
    ) : HyprlandError

    /** [number] is below 1, and only a workspace numbered from 1 up can be asked for by its number. */
    public data class NotNumbered(public val number: Int) : HyprlandError

    /** Hyprland answered [request] with JSON of a shape kortex does not read; [detail] says where. */
    public data class Unparseable(
        public val request: String,
        public val detail: String,
    ) : HyprlandError
}
