package com.fromwau.kortex.bar.ui

import androidx.compose.ui.graphics.Color
import com.fromwau.kortex.dbus.DBusError
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
fun Float.loadColor(): Color = when {
    this >= 0.85f -> HOT
    this >= 0.6f -> WARM
    else -> COOL
}

/** The same three bands for a temperature, where the thresholds are degrees rather than a share. */
fun Int.heatColor(): Color = when {
    this >= 85 -> HOT
    this >= 70 -> WARM
    else -> COOL
}

private val PLAIN: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val DETAILED: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM  HH:mm:ss")

private val COOL = Color(0xFF8BC48A)
private val WARM = Color(0xFFE0B341)
private val HOT = Color(0xFFE06C6C)

private const val SECONDS_IN_MINUTE = 60
private const val KIBIBYTE = 1024L
private const val MEBIBYTE = 1024L * 1024L
private const val KIBIBYTES_IN_GIBIBYTE = 1024.0 * 1024.0
