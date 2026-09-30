package com.fromwau.kortex.notification

import com.fromwau.kortex.dbus.DBusType
import com.fromwau.kortex.dbus.DBusValue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Everything `notify-send` can express, surviving the trip into a notification. */
class NotificationTest {
    @Test
    fun `the arguments a notification is mostly made of arrive in order`() {
        val notification = assertNotNull(
            notificationFrom(
                id = 7u,
                body = notifyBody(
                    appName = "Signal",
                    appIcon = "signal-desktop",
                    summary = "New message",
                    body = "from somebody",
                ),
            ),
        )

        assertEquals(7u, notification.id)
        assertEquals("Signal", notification.appName)
        assertEquals("signal-desktop", notification.appIcon)
        assertEquals("New message", notification.summary)
        assertEquals("from somebody", notification.body)
    }

    /**
     * The one argument the wire spells with two sentinels among ordinary values.
     *
     * -1 and 0 are both negative-or-zero millisecond counts, and nothing but a comment tells them from a
     * real duration, which is the whole reason this is a type.
     */
    @Test
    fun `an expiry of minus one means the server decides and zero means never`() {
        assertEquals(Expiry.ServerDefault, expiryOf(-1))
        assertEquals(Expiry.Never, expiryOf(0))
        assertEquals(Expiry.After(5.seconds), expiryOf(5_000))
        assertEquals(Expiry.After(1.milliseconds), expiryOf(1))
    }

    @Test
    fun `a negative expiry that is not the sentinel reads as no opinion rather than as a duration`() {
        assertEquals(Expiry.ServerDefault, expiryOf(-250))
    }

    @Test
    fun `every urgency level the specification names is recognised`() {
        assertEquals(Urgency.Low, urgencyOf(0))
        assertEquals(Urgency.Normal, urgencyOf(1))
        assertEquals(Urgency.Critical, urgencyOf(2))
    }

    @Test
    fun `an urgency nothing recognises, or none at all, reads as normal`() {
        assertEquals(Urgency.Normal, urgencyOf(9))
        assertEquals(
            Urgency.Normal,
            assertNotNull(notificationFrom(1u, notifyBody())).urgency,
            "an application that sends no urgency hint gets the middle one",
        )
    }

    /** The wire has no type for a pair, so actions arrive as a flat list of key then label. */
    @Test
    fun `actions arrive as alternating keys and labels`() {
        val notification = assertNotNull(
            notificationFrom(1u, notifyBody(actions = listOf("default", "Open", "reply", "Reply"))),
        )

        assertEquals(
            listOf(NotificationAction("default", "Open"), NotificationAction("reply", "Reply")),
            notification.actions,
        )
    }

    @Test
    fun `an action left without a label is dropped rather than half built`() {
        val notification = assertNotNull(notificationFrom(1u, notifyBody(actions = listOf("default"))))

        assertTrue(notification.actions.isEmpty())
    }

    @Test
    fun `every standard hint reaches its own field`() {
        val notification = assertNotNull(
            notificationFrom(
                1u,
                notifyBody(
                    hints = mapOf(
                        "urgency" to DBusValue.U8(2),
                        "category" to DBusValue.Text("im.received"),
                        "desktop-entry" to DBusValue.Text("org.signal.Signal"),
                        "transient" to DBusValue.Bool(true),
                        "resident" to DBusValue.Bool(true),
                        "image-path" to DBusValue.Text("/tmp/avatar.png"),
                        "sound-file" to DBusValue.Text("/usr/share/sounds/ping.oga"),
                        "sound-name" to DBusValue.Text("message-new-instant"),
                        "suppress-sound" to DBusValue.Bool(true),
                        "action-icons" to DBusValue.Bool(true),
                        "x" to DBusValue.I32(120),
                        "y" to DBusValue.I32(40),
                    ),
                ),
            ),
        )

        assertEquals(Urgency.Critical, notification.urgency)
        assertEquals("im.received", notification.category)
        assertEquals("org.signal.Signal", notification.desktopEntry)
        assertTrue(notification.isTransient)
        assertTrue(notification.isResident)
        assertEquals("/tmp/avatar.png", notification.imagePath)
        assertEquals("/usr/share/sounds/ping.oga", notification.soundFile)
        assertEquals("message-new-instant", notification.soundName)
        assertTrue(notification.suppressSound)
        assertTrue(notification.actionIcons)
        assertEquals(ScreenHint(120, 40), notification.screenHint)
    }

    @Test
    fun `a notification with no hints takes every default and points nowhere`() {
        val notification = assertNotNull(notificationFrom(1u, notifyBody()))

        assertNull(notification.category)
        assertNull(notification.desktopEntry)
        assertNull(notification.imagePath)
        assertNull(notification.soundFile)
        assertNull(notification.soundName)
        assertNull(notification.screenHint)
        assertNull(notification.image)
        assertTrue(!notification.isTransient)
        assertTrue(!notification.isResident)
        assertTrue(!notification.suppressSound)
        assertTrue(!notification.actionIcons)
    }

    /** Raw and unencoded, unlike a menu entry's icon, and with a stride that need not match the width. */
    @Test
    fun `inline image data keeps its layout and its pixels`() {
        val pixels = ByteArray(2 * 8) { it.toByte() }
        val notification = assertNotNull(
            notificationFrom(1u, notifyBody(hints = mapOf("image-data" to image(2, 2, stride = 8, pixels = pixels)))),
        )

        val image = assertNotNull(notification.image)
        assertEquals(2, image.width)
        assertEquals(2, image.height)
        assertEquals(8, image.rowStride, "a stride is not width times channels when rows are padded")
        assertTrue(image.hasAlpha)
        assertEquals(8, image.bitsPerSample)
        assertEquals(4, image.channels)
        assertContentEquals(pixels, image.pixels)
    }

    /** `icon_data` is what specification 1.0 called it, and applications still send it. */
    @Test
    fun `the older spelling of inline image data is read too`() {
        val pixels = ByteArray(4)
        val notification = assertNotNull(
            notificationFrom(1u, notifyBody(hints = mapOf("icon_data" to image(1, 1, stride = 4, pixels = pixels)))),
        )

        assertNotNull(notification.image)
    }

    @Test
    fun `image-data is preferred where an application sends both spellings`() {
        val newer = ByteArray(4) { 1 }
        val older = ByteArray(4) { 2 }
        val notification = assertNotNull(
            notificationFrom(
                1u,
                notifyBody(
                    hints = mapOf(
                        "image-data" to image(1, 1, stride = 4, pixels = newer),
                        "icon_data" to image(1, 1, stride = 4, pixels = older),
                    ),
                ),
            ),
        )

        assertContentEquals(newer, assertNotNull(notification.image).pixels)
    }

    /**
     * Hints no specification lists are kept, because applications rely on them.
     *
     * dunst's own `x-dunst-stack-tag` and the three spellings of a synchronous-replace tag are all real,
     * and a caller that wants to honour one has nowhere else to read it.
     */
    @Test
    fun `a hint no specification names is still there to be read`() {
        val notification = assertNotNull(
            notificationFrom(
                1u,
                notifyBody(hints = mapOf("x-dunst-stack-tag" to DBusValue.Text("volume"))),
            ),
        )

        assertEquals(DBusValue.Text("volume"), notification.hints["x-dunst-stack-tag"])
        assertEquals(1, notification.hints.size)
    }

    @Test
    fun `a call of the wrong width is not a Notify at all`() {
        assertNull(notificationFrom(1u, emptyList()))
        assertNull(notificationFrom(1u, notifyBody().dropLast(1)))
        assertNull(notificationFrom(1u, notifyBody() + DBusValue.Text("extra")))
    }

    private fun expiryOf(millis: Int): Expiry =
        assertNotNull(notificationFrom(1u, notifyBody(expireMillis = millis))).expiry

    private fun urgencyOf(level: Int): Urgency =
        assertNotNull(notificationFrom(1u, notifyBody(hints = mapOf("urgency" to DBusValue.U8(level.toByte())))))
            .urgency

    private fun image(width: Int, height: Int, stride: Int, pixels: ByteArray): DBusValue = DBusValue.Struct(
        listOf(
            DBusValue.I32(width),
            DBusValue.I32(height),
            DBusValue.I32(stride),
            DBusValue.Bool(true),
            DBusValue.I32(8),
            DBusValue.I32(4),
            DBusValue.Bytes(pixels),
        ),
    )
}
