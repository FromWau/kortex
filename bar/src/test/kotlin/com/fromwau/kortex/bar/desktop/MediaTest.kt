package com.fromwau.kortex.bar.desktop

import com.fromwau.kortex.bar.BarError
import com.fromwau.kortex.bar.state.Reading
import com.fromwau.kortex.dbus.DBusError
import com.fromwau.kortex.mpris.MprisError
import com.fromwau.kortex.mpris.PlaybackStatus
import com.fromwau.kortex.mpris.Player
import com.fromwau.kortex.mpris.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/** Which player the bar shows, and what it shows of it. */
class MediaTest {
    @Test
    fun `a playing player is chosen over a paused one listed before it`() {
        val chosen = listOf(player("a", PlaybackStatus.Paused), player("b", PlaybackStatus.Playing)).chosen()

        assertEquals("b", chosen?.busName)
    }

    @Test
    fun `with nothing playing, a paused player with a track is shown so it can be resumed`() {
        val players = listOf(player("a", PlaybackStatus.Paused, title = null), player("b", PlaybackStatus.Paused))

        val chosen = players.chosen()

        assertEquals("b", chosen?.busName)
    }

    @Test
    fun `a stopped player is not shown at all`() {
        assertNull(listOf(player("a", PlaybackStatus.Stopped)).chosen())
    }

    @Test
    fun `a player becomes the line the bar draws, at the position it was given`() {
        val now = player("a", PlaybackStatus.Playing).nowPlaying(position = 61.seconds)

        assertEquals("A song", now.title)
        assertEquals(listOf("Someone"), now.artists)
        assertEquals(true, now.playing)
        assertEquals(61.seconds, now.position)
        assertEquals(200.seconds, now.length)
    }

    @Test
    fun `not connected yet is pending, and anything else is the bar saying why`() {
        assertEquals(Reading.Pending, MprisError.NotConnected.reading())
        val down = MprisError.BusDown(DBusError.Disconnected)
        assertEquals(Reading.Unavailable(BarError.NoMedia(down)), down.reading())
    }

    private fun player(
        name: String,
        status: PlaybackStatus,
        title: String? = "A song",
    ): Player = Player(
        busName = name,
        identity = name,
        desktopEntry = null,
        status = status,
        track = Track(
            id = null,
            title = title,
            artists = listOf("Someone"),
            album = null,
            artUrl = null,
            length = 200.seconds,
        ),
        position = null,
        rate = 1.0,
        volume = null,
        loop = null,
        shuffle = null,
        canControl = true,
        canPlay = true,
        canPause = true,
        canGoNext = true,
        canGoPrevious = true,
        canSeek = true,
        canRaise = false,
    )
}
