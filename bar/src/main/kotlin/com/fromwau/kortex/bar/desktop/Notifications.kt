package com.fromwau.kortex.bar.desktop

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kortex.bar.BarError
import com.fromwau.kortex.bar.state.Posted
import com.fromwau.kortex.bar.state.Reading
import com.fromwau.kortex.notification.Notification
import com.fromwau.kortex.notification.NotificationError

/**
 * [this] as the popup draws it, carrying whatever the application offered for an icon rather than
 * resolving it.
 *
 * Three fields can carry one, and in practice the last two are the ones that do: `notify-send
 * --icon=dialog-information` leaves `appIcon` empty and puts the name in the `image-path` hint, and
 * `image` only arrives from an application that sent raw pixels. Finding the file a name stands for is
 * `:icons`' job, where the drawing happens, so the state keeps the description and nothing here reads a
 * directory.
 */
fun Notification.posted(): Posted = Posted(
    id = id,
    revision = revision,
    appName = appName,
    summary = summary,
    body = body,
    urgency = urgency,
    expiry = expiry,
    image = image,
    iconName = listOfNotNull(imagePath, appIcon.ifBlank { null }).firstOrNull(),
)

/**
 * One reading of the notifications, where [NotificationError.NotConnected] is [Reading.Pending]: nobody
 * collects yet or the first connection is not up, which passes in a moment and is no reason to say so.
 */
fun Result<List<Notification>, NotificationError>.readable(): Reading<List<Posted>> = when (this) {
    is Ok -> Reading.Value(value.map { notification -> notification.posted() })
    is Err -> when (error) {
        NotificationError.NotConnected -> Reading.Pending
        else -> Reading.Unavailable(BarError.NotServing(error))
    }
}
