package com.fromwau.kortex.bar.desktop

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kortex.bar.BarError
import com.fromwau.kortex.bar.state.Reading
import com.fromwau.kortex.notification.Expiry
import com.fromwau.kortex.notification.Notification
import com.fromwau.kortex.notification.NotificationAction
import com.fromwau.kortex.notification.NotificationError
import com.fromwau.kortex.notification.NotificationImage
import com.fromwau.kortex.notification.Urgency
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * What the popup is handed for one notification.
 *
 * Only the choosing is tested here. Finding the file an icon name stands for, and reading the pixels an
 * application sent, both belong to `:icons` now and are tested there: this is the part that decides
 * which of the three fields an application filled in is the one worth drawing.
 */
class NotificationsTest {
    /**
     * The precedence, which is not the order the specification lists the fields in.
     *
     * `notify-send --icon=dialog-information` leaves `appIcon` empty and puts the name in the
     * `image-path` hint, so the hint has to win over the field that looks like it should.
     */
    @Test
    fun `the image-path hint is preferred to the app icon, which is what notify-send fills in`() {
        val both = notification(imagePath = "mail-unread", appIcon = "thunderbird")

        assertEquals("mail-unread", both.posted().iconName)
    }

    @Test
    fun `an app icon is used where no hint was sent`() {
        assertEquals("thunderbird", notification(appIcon = "thunderbird").posted().iconName)
    }

    /** A blank field is not a name, and a popup that looked one up would search for the empty string. */
    @Test
    fun `an app icon that is blank is no name at all`() {
        assertNull(notification(appIcon = "   ").posted().iconName)
    }

    /**
     * Pixels are carried through rather than decoded, which is what lets the popup prefer them.
     *
     * Identity, because an application that sent an image meant that image: a copy here would be a
     * second decode of bytes that already arrived.
     */
    @Test
    fun `an inline image is carried through untouched`() {
        val sent = NotificationImage(
            width = 1,
            height = 1,
            rowStride = 4,
            hasAlpha = true,
            bitsPerSample = 8,
            channels = 4,
            pixels = ByteArray(4),
        )

        assertSame(sent, notification(image = sent).posted().image)
    }

    @Test
    fun `a notification becomes what the popup draws, and keeps the revision that tells two postings apart`() {
        val posted = notification(
            id = 7u,
            revision = 3u,
            appName = "notify-send",
            summary = "Build finished",
            body = "in 4.2s",
            urgency = Urgency.Critical,
        ).posted()

        assertEquals(7u, posted.id)
        assertEquals(3u, posted.revision)
        assertEquals("notify-send", posted.appName)
        assertEquals("Build finished", posted.summary)
        assertEquals("in 4.2s", posted.body)
        assertEquals(Urgency.Critical, posted.urgency)
        assertNull(posted.image)
        assertNull(posted.iconName)
    }

    @Test
    fun `a server not connected yet is pending, and one somebody else holds the name of says who`() {
        assertEquals(Reading.Pending, Err(NotificationError.NotConnected).readable())

        val taken = NotificationError.AlreadyServed(owner = ":1.7", pid = 1, process = "dunst")
        assertEquals(Reading.Unavailable(BarError.NotServing(taken)), Err(taken).readable())
    }

    @Test
    fun `a server that is serving hands over what is posted`() {
        val posted = Ok(listOf(notification(appIcon = "thunderbird"))).readable()

        assertEquals(listOf("thunderbird"), (posted as Reading.Value).value.map { it.iconName })
    }
}

internal fun notification(
    id: UInt = 1u,
    imagePath: String? = null,
    revision: UInt = 1u,
    appName: String = "an-app",
    appIcon: String = "",
    summary: String = "a summary",
    body: String = "",
    actions: List<NotificationAction> = emptyList(),
    urgency: Urgency = Urgency.Normal,
    expiry: Expiry = Expiry.ServerDefault,
    image: NotificationImage? = null,
): Notification = Notification(
    id = id,
    revision = revision,
    appName = appName,
    appIcon = appIcon,
    summary = summary,
    body = body,
    actions = actions,
    urgency = urgency,
    expiry = expiry,
    category = null,
    desktopEntry = null,
    isTransient = false,
    isResident = false,
    image = image,
    imagePath = imagePath,
    soundFile = null,
    soundName = null,
    suppressSound = false,
    actionIcons = false,
    screenHint = null,
    hints = emptyMap(),
)
