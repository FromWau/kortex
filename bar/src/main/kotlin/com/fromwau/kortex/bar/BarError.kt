package com.fromwau.kortex.bar

import com.fromwau.kern.result.IError
import com.fromwau.kortex.dbus.DBusError
import com.fromwau.kortex.hyprland.HyprlandError
import com.fromwau.kortex.notification.NotificationError
import com.fromwau.kortex.tray.TrayError
import com.fromwau.kortex.watch.WatchError
import com.fromwau.kortex.bar.system.ParseFailure

/**
 * Why a widget has nothing to show. Every source the bar reads answers its own error type, and each is
 * mapped into this one where it is read, so the state and the UI match on one closed set.
 */
sealed interface BarError : IError {
    /** The file behind a widget could not be read or watched, as `:watch` reported it. */
    data class Unreadable(val error: WatchError) : BarError

    /** The file was read, and did not hold what the widget expected. */
    data class Unparseable(val error: ParseFailure) : BarError

    /** Nothing under [lookedIn] reports what the widget needs, so there is no file to watch. */
    data class NoSensor(val lookedIn: String) : BarError

    /** The session bus could not be reached, so neither the tray nor notifications can be. */
    data class NoBus(val error: DBusError) : BarError

    /** The tray could not be read, as `:tray` reported it. */
    data class NoTray(val error: TrayError) : BarError

    /** This shell is not the notification server, as `:notification` reported it. */
    data class NotServing(val error: NotificationError) : BarError

    /** Hyprland's workspaces or focused window could not be read, as `:hyprland` reported it. */
    data class NoHyprland(val error: HyprlandError) : BarError
}
