package com.fromwau.kortex.notification

import com.fromwau.kern.result.IError
import com.fromwau.kortex.dbus.DBusError

/** Why kortex is not serving notifications, or why a command about one did not land. */
public sealed interface NotificationError : IError {
    /**
     * Somebody else is already the notification server, and kortex asked not to be queued behind them.
     *
     * Only one connection can hold the name, so this is the ordinary outcome on a desktop that already has
     * a notification daemon rather than an edge case. [process] is what turns "the name is taken" into
     * "dunst is already running", which is two more round trips on a path that has already failed and the
     * difference between a diagnosable message and a riddle.
     */
    public data class AlreadyServed(
        public val owner: String,
        public val pid: Int?,
        public val process: String?,
    ) : NotificationError

    /** The bus itself failed, and [cause] says how. */
    public data class BusFailed(public val cause: DBusError) : NotificationError

    /** No notification with that id is being carried, so there is nothing to close or act on. */
    public data class NoSuchNotification(public val id: UInt) : NotificationError
}
