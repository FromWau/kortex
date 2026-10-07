package com.fromwau.kortex.bar.desktop

import com.fromwau.kortex.bar.BarError
import com.fromwau.kortex.bar.state.NowPlaying
import com.fromwau.kortex.bar.state.Reading
import com.fromwau.kortex.mpris.MprisError
import com.fromwau.kortex.mpris.PlaybackStatus
import com.fromwau.kortex.mpris.Player
import kotlin.time.Duration

/**
 * The one player the bar shows: the first that is playing, else the first paused one that has a track,
 * else none. A stopped player has nothing to show and a click would only start it from the top.
 */
fun List<Player>.chosen(): Player? =
    firstOrNull { it.status == PlaybackStatus.Playing }
        ?: firstOrNull { it.status == PlaybackStatus.Paused && it.track.title != null }

/** [this] as the bar names it, at [position]. */
fun Player.nowPlaying(position: Duration?): NowPlaying = NowPlaying(
    player = busName,
    app = identity,
    title = track.title.orEmpty(),
    artists = track.artists,
    playing = status == PlaybackStatus.Playing,
    position = position,
    length = track.length,
)

/** Why there is nothing to show, where [MprisError.NotConnected] is only a moment before the first read. */
fun MprisError.reading(): Reading<Nothing> = when (this) {
    MprisError.NotConnected -> Reading.Pending
    else -> Reading.Unavailable(BarError.NoMedia(this))
}
