package com.fromwau.kortex.mpris

import com.fromwau.kortex.dbus.DBusType
import com.fromwau.kortex.dbus.DBusValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** What a player's properties read as, and where its position is between readings. */
class PlayerTest {
    @Test
    fun `while playing, the position moves on from the last reading at the player's rate`() {
        val now = player(PlaybackStatus.Playing, read = 10.seconds, ago = 4.seconds, rate = 2.0).positionNow()!!

        assertTrue(now in 18.seconds..19.seconds, "expected about 18s, was $now")
    }

    @Test
    fun `while paused, the position stays where it was read`() {
        assertEquals(10.seconds, player(PlaybackStatus.Paused, read = 10.seconds, ago = 4.seconds).positionNow())
    }

    @Test
    fun `the position never runs past the end of the track`() {
        val now = player(PlaybackStatus.Playing, read = 170.seconds, ago = 30.seconds, length = 180.seconds)

        assertEquals(180.seconds, now.positionNow())
    }

    @Test
    fun `artists arrive as a list, and a player that sends one string is read as one artist`() {
        val listed = DBusValue.Sequence(DBusType.Basic.Text, listOf(DBusValue.Text("A"), DBusValue.Text("B")))

        assertEquals(listOf("A", "B"), trackFrom(mapOf("xesam:artist" to listed)).artists)
        assertEquals(listOf("A"), trackFrom(mapOf("xesam:artist" to DBusValue.Text("A"))).artists)
    }

    @Test
    fun `a length is read from whichever integer the player sent it as`() {
        assertEquals(3.seconds, trackFrom(mapOf("mpris:length" to DBusValue.I64(3_000_000))).length)
        assertEquals(3.seconds, trackFrom(mapOf("mpris:length" to DBusValue.U64(3_000_000u))).length)
    }

    @Test
    fun `a player that leaves out what the specification has defaults for gets those defaults`() {
        val bare = playerFrom("org.mpris.MediaPlayer2.bare", emptyMap(), emptyMap(), position = null)

        assertEquals("bare", bare.identity)
        assertEquals(PlaybackStatus.Stopped, bare.status)
        assertEquals(1.0, bare.rate)
    }

    private fun player(
        status: PlaybackStatus,
        read: Duration,
        ago: Duration,
        rate: Double = 1.0,
        length: Duration? = null,
    ): Player = playerFrom(
        "org.mpris.MediaPlayer2.test",
        emptyMap(),
        buildMap {
            put("PlaybackStatus", DBusValue.Text(status.wireName))
            put("Rate", DBusValue.F64(rate))
            length?.let { put("Metadata", dictionary("mpris:length" to DBusValue.I64(it.inWholeMicroseconds))) }
        },
        PlayPosition(read, TimeSource.Monotonic.markNow() - ago),
    )
}
