package com.fromwau.kortex.bar.state

import com.fromwau.kortex.notification.Expiry
import com.fromwau.kortex.notification.Urgency
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class PostedTest {
    @Test
    fun `a notification that leaves the time to the server stays the default`() {
        assertEquals(DEFAULT, posted(Expiry.ServerDefault).showsFor(DEFAULT))
    }

    @Test
    fun `a notification that names its own time stays exactly that long`() {
        assertEquals(10.seconds, posted(Expiry.After(10.seconds)).showsFor(DEFAULT))
    }

    @Test
    fun `a notification that asks never to expire stays until it is closed`() {
        assertEquals(null, posted(Expiry.Never).showsFor(DEFAULT))
    }

    @Test
    fun `a critical notification stays whatever time it names`() {
        assertEquals(null, posted(Expiry.After(10.seconds), Urgency.Critical).showsFor(DEFAULT))
        assertEquals(null, posted(Expiry.ServerDefault, Urgency.Critical).showsFor(DEFAULT))
    }

    private fun posted(
        expiry: Expiry,
        urgency: Urgency = Urgency.Normal,
    ): Posted = Posted(
        id = 1u,
        revision = 1u,
        appName = "an-app",
        summary = "a summary",
        body = "",
        urgency = urgency,
        expiry = expiry,
        image = null,
        iconName = null,
    )

    private companion object {
        val DEFAULT = 2.seconds
    }
}
