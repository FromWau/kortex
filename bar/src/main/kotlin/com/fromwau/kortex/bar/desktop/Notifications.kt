package com.fromwau.kortex.bar.desktop

import com.fromwau.kortex.notification.Notification
import com.fromwau.kortex.bar.state.Posted

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
