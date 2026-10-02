package com.fromwau.kortex.bar.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.fromwau.kortex.dbus.DBusError
import com.fromwau.kortex.hyprland.HyprlandError
import com.fromwau.kortex.notification.NotificationError
import com.fromwau.kortex.tray.TrayError
import com.fromwau.kortex.watch.WatchError
import com.fromwau.kortex.bar.BarError
import com.fromwau.kortex.bar.system.ParseFailure
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** [this] as the bar's clock shows it, with the date and the seconds only when [detailed]. */
fun LocalDateTime.formatted(detailed: Boolean): String =
    format(if (detailed) DETAILED else PLAIN)

/** [this] as a percentage, which is how a load is read at a glance. */
fun percent(fraction: Float): String = "${(fraction * 100).toInt()}%"

/** [this] seconds as `mm:ss`, which is what a countdown under an hour wants. */
fun Long.asClock(): String = "%d:%02d".format(this / SECONDS_IN_MINUTE, this % SECONDS_IN_MINUTE)

/** [this] many bytes per second, in the largest unit that leaves a number a person can read. */
fun Long.perSecond(): String = when {
    this >= MEBIBYTE -> "%.1fM".format(Locale.ROOT, this / MEBIBYTE.toDouble())
    this >= KIBIBYTE -> "%dk".format(this / KIBIBYTE)
    else -> "${this}B"
}

/** [kibibytes] as gibibytes with one decimal, which is the only sensible unit for a machine's memory. */
fun gibibytes(kibibytes: Long): String = "%.1f".format(Locale.ROOT, kibibytes / KIBIBYTES_IN_GIBIBYTE)

/**
 * Why a widget has nothing to show, in the few characters a bar has.
 *
 * This is the one place a typed error becomes a string. Every error the bar can hold is mapped here rather
 * than at the point it was raised, so the wording is the UI's and `toString()` of a kortex data class never
 * reaches a person's screen.
 */
fun BarError.shortly(): String = when (this) {
    is BarError.Unreadable -> error.shortly()
    is BarError.Unparseable -> error.shortly()
    is BarError.NoSensor -> "no sensor"
    is BarError.NoBus -> error.shortly()
    is BarError.NoTray -> error.shortly()
    is BarError.NotServing -> error.shortly()
    is BarError.NoHyprland -> error.shortly()
}

private fun HyprlandError.shortly(): String = when (this) {
    HyprlandError.NotConnected -> "connecting"
    HyprlandError.NoInstance -> "not on hyprland"
    is HyprlandError.NoSocket -> "no hyprland socket"
    is HyprlandError.Unreachable -> "hyprland unreachable"
    HyprlandError.Disconnected -> "hyprland closed"
    is HyprlandError.Refused -> "hyprland refused"
    is HyprlandError.NotNumbered -> "no workspace $number"
    is HyprlandError.Unparseable -> "unreadable reply"
}

private fun WatchError.shortly(): String = when (this) {
    is WatchError.Unreadable -> "cannot read ${path.name}"
    is WatchError.Unwatchable -> "${filesystem.typeName} needs polling"
    is WatchError.NoFolderAbove -> "no folder above ${path.name}"
    is WatchError.FolderUnreadable -> "cannot read the folder of ${path.name}"
    is WatchError.WatchRefused -> "watch refused for ${path.name}"
    is WatchError.WatchEnded -> "watch on ${path.name} ended"
}

private fun TrayError.shortly(): String = when (this) {
    TrayError.NotConnected -> "connecting"
    TrayError.NoWatcher -> "no watcher"
    TrayError.MenuUnreadable -> "menu unreadable"
    is TrayError.BusFailed -> cause.shortly()
}

/**
 * Why this shell is not showing notifications, which on a desktop that already runs a daemon is the
 * ordinary answer rather than a failure.
 *
 * `AlreadyServed` carries the process behind the name as well as the name itself, so this says "dunst
 * serves" rather than ":1.1860 serves", which is the difference between a readout and a riddle.
 */
private fun NotificationError.shortly(): String = when (this) {
    is NotificationError.AlreadyServed -> "${process ?: owner} serves"
    is NotificationError.BusFailed -> cause.shortly()
    is NotificationError.NoSuchNotification -> "no notification $id"
}

/**
 * The bus's own failure, in the characters a bar has.
 *
 * The one `when` here with an `else`, and deliberately. `DBusError` has eighteen cases, and fifteen of
 * them are a message that would not decode: a bar can do nothing differently about a nesting depth or an
 * endianness marker, and naming each would be fifteen lines of the same sentence.
 */
private fun DBusError.shortly(): String = when (this) {
    DBusError.NoSessionBus -> "no session bus"
    DBusError.Disconnected -> "bus closed"
    DBusError.ReplyTimedOut -> "bus timed out"
    is DBusError.CallFailed -> "bus refused"
    else -> "bus failed"
}

private fun ParseFailure.shortly(): String = when (this) {
    is ParseFailure.MissingLine -> "no $prefix line"
    is ParseFailure.NotANumber -> "$field is not a number"
    is ParseFailure.OutOfRange -> "$field out of range"
}


/** Green while there is room to spare, amber as it fills, red when it is full. */
/**
 * The colour for a reading in its three bands, taken from the theme rather than fixed.
 *
 * Fixed green, amber and red said the same thing under every palette and ignored the one the desktop
 * generated, which on a dark scheme left the bar reading as black and white. The bands survive, since a
 * machine at 90% should still look alarming: what changes is that calm and warm are now the palette's own
 * accents, so a generated theme is visible in the thing that moves.
 */
@Composable
fun loadColour(fraction: Float): Color = when {
    fraction >= ALARMING -> MaterialTheme.colorScheme.error
    fraction >= WARM -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.primary
}

/** The same three bands for a temperature, where the thresholds are degrees rather than a share. */
@Composable
fun heatColour(celsius: Int): Color = when {
    celsius >= ALARMING_DEGREES -> MaterialTheme.colorScheme.error
    celsius >= WARM_DEGREES -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.onSurface
}

private val PLAIN: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val DETAILED: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM  HH:mm:ss")

private const val WARM = 0.6f
private const val ALARMING = 0.85f
private const val WARM_DEGREES = 70
private const val ALARMING_DEGREES = 85

private const val SECONDS_IN_MINUTE = 60
private const val KIBIBYTE = 1024L
private const val MEBIBYTE = 1024L * 1024L
private const val KIBIBYTES_IN_GIBIBYTE = 1024.0 * 1024.0
