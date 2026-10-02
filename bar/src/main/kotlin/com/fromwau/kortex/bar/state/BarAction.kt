package com.fromwau.kortex.bar.state

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

    /**
     * The pointer moved onto the tray item at [address], or off every one of them when it is null.
     *
     * The bar has no room for a tooltip beside every icon, so one item's hover text is shown at a time
     * and the bar is the thing that knows which. A tray item is never clicked: a host that activates one
     * opens somebody's window, and this bar only reads.
     */
    data class TrayHovered(val address: ItemAddress?) : BarAction

    /** The notification with [id] was dismissed, which closes it and tells its application why. */
    data class NotificationDismissed(val id: UInt) : BarAction

    /** The scheme button was clicked, which moves to the next of [BarScheme]. */
    data object SchemeCycled : BarAction
}
