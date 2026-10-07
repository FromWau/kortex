package com.fromwau.kortex.bar.state

import com.fromwau.kortex.notification.CloseReason
import com.fromwau.kortex.powerprofiles.PowerProfile
import com.fromwau.kortex.tray.ItemAddress

/** Everything a person can do to the bar. */
sealed interface BarAction {
    /** The clock was clicked, which turns the date and the seconds on and off. */
    data object ClockClicked : BarAction

    /**
     * The focus timer was clicked. One target, because a bar has no room for three: it starts an idle
     * timer, pauses a running one, resumes a paused one, and clears one that has elapsed.
     */
    data object TimerClicked : BarAction

    /** The focus timer was right-clicked, which abandons the session whatever it was doing. */
    data object TimerReset : BarAction

    /** The tray item at [address] was clicked with [button], [x] logical pixels from the bar's left edge. */
    data class TrayClicked(
        val address: ItemAddress,
        val button: TrayButton,
        val x: Int,
    ) : BarAction

    /** The entry with [id] in the open tray menu was picked, which also closes the menu. */
    data class TrayMenuEntryPicked(val id: Int) : BarAction

    /** The open tray menu went away without anything in it being picked. */
    data object TrayMenuDismissed : BarAction

    /** The notification with [id] was clicked away or ran out its time, and its application is told [reason]. */
    data class NotificationClosed(
        val id: UInt,
        val reason: CloseReason,
    ) : BarAction

    /** The workspace numbered [id] was clicked, which switches to it, making it if it is the empty one. */
    data class WorkspaceClicked(val id: Int) : BarAction

    /** The now-playing widget was clicked, which plays the player at the bus name [player] or pauses it. */
    data class MediaClicked(val player: String) : BarAction

    /** The power profile was clicked, which switches to [next]. */
    data class ProfileClicked(val next: PowerProfile) : BarAction

    /** The scheme button was clicked, which moves to the next of [BarScheme]. */
    data object SchemeCycled : BarAction
}

/** Which button clicked a tray item. */
enum class TrayButton {
    Left,
    Middle,
    Right,
}
