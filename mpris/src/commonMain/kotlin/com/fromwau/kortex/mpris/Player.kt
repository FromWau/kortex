package com.fromwau.kortex.mpris

import com.fromwau.kortex.dbus.DBusValue
import com.fromwau.kortex.dbus.asBoolean
import com.fromwau.kortex.dbus.asDictionary
import com.fromwau.kortex.dbus.asDouble
import com.fromwau.kortex.dbus.asItems
import com.fromwau.kortex.dbus.asObjectPath
import com.fromwau.kortex.dbus.asText
import com.fromwau.kortex.dbus.flag
import com.fromwau.kortex.dbus.text
import com.fromwau.kortex.dbus.unwrapped
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.TimeSource

/** Whether a player is playing, as its `PlaybackStatus` says. */
public enum class PlaybackStatus(internal val wireName: String) {
    Playing("Playing"),
    Paused("Paused"),
    Stopped("Stopped"),
}

/** What a player does at the end of a track or a playlist, as its `LoopStatus` says. */
public enum class LoopStatus(internal val wireName: String) {
    None("None"),
    Track("Track"),
    Playlist("Playlist"),
}

/** The track a player has loaded, from its `Metadata`. Every field is one a player may leave out. */
public data class Track(
    public val id: String?,
    public val title: String?,
    public val artists: List<String>,
    public val album: String?,
    public val artUrl: String?,
    public val length: Duration?,
)

/**
 * Where playback was at one moment.
 *
 * MPRIS never announces the position as it moves, only when it jumps, so a reading is a position and the
 * moment it was taken, and [Player.positionNow] carries it forward from there.
 */
public data class PlayPosition(
    public val at: Duration,
    public val measured: TimeSource.Monotonic.ValueTimeMark,
)

/**
 * One media player on the bus, as it last said it was.
 *
 * [busName] is the name commands go to. A player that does not offer a property gets the value the
 * specification gives as its default where there is one, and null where there is not.
 */
public data class Player(
    public val busName: String,
    public val identity: String,
    public val desktopEntry: String?,
    public val status: PlaybackStatus,
    public val track: Track,
    public val position: PlayPosition?,
    public val rate: Double,
    public val volume: Double?,
    public val loop: LoopStatus?,
    public val shuffle: Boolean?,
    public val canControl: Boolean,
    public val canPlay: Boolean,
    public val canPause: Boolean,
    public val canGoNext: Boolean,
    public val canGoPrevious: Boolean,
    public val canSeek: Boolean,
    public val canRaise: Boolean,
)

/**
 * The position now: carried forward at [Player.rate] from the last reading while playing, and held while
 * not, never past the track's end.
 */
public fun Player.positionNow(): Duration? {
    val reading = position ?: return null
    if (status != PlaybackStatus.Playing) return reading.at
    val moved = reading.at + reading.measured.elapsedNow() * rate
    return track.length?.let { moved.coerceAtMost(it) } ?: moved
}

/** [busName]'s player from what `GetAll` gave for the root interface and the player interface. */
internal fun playerFrom(
    busName: String,
    root: Map<String, DBusValue>,
    player: Map<String, DBusValue>,
    position: PlayPosition?,
): Player = Player(
    busName = busName,
    identity = root.text("Identity") ?: busName.removePrefix(PLAYER_PREFIX),
    desktopEntry = root.text("DesktopEntry"),
    status = PlaybackStatus.entries.firstOrNull { it.wireName == player.text("PlaybackStatus") }
        ?: PlaybackStatus.Stopped,
    track = trackFrom(player["Metadata"]?.asDictionary.orEmpty()),
    position = position,
    rate = player["Rate"]?.asDouble ?: 1.0,
    volume = player["Volume"]?.asDouble,
    loop = LoopStatus.entries.firstOrNull { it.wireName == player.text("LoopStatus") },
    shuffle = player["Shuffle"]?.asBoolean,
    canControl = player.flag("CanControl"),
    canPlay = player.flag("CanPlay"),
    canPause = player.flag("CanPause"),
    canGoNext = player.flag("CanGoNext"),
    canGoPrevious = player.flag("CanGoPrevious"),
    canSeek = player.flag("CanSeek"),
    canRaise = root.flag("CanRaise"),
)

internal fun trackFrom(metadata: Map<String, DBusValue>): Track = Track(
    id = metadata["mpris:trackid"]?.let { it.asObjectPath ?: it.asText },
    title = metadata.text("xesam:title"),
    artists = metadata["xesam:artist"]?.asItems?.mapNotNull { it.asText }
        ?: listOfNotNull(metadata.text("xesam:artist")),
    album = metadata.text("xesam:album"),
    artUrl = metadata.text("mpris:artUrl"),
    length = metadata["mpris:length"]?.asMicroseconds,
)

/**
 * A time in microseconds, which MPRIS sends as an `x` and which players send as whatever integer they
 * happened to use; a length in a `u` or a `t` is common enough to accept.
 */
internal val DBusValue.asMicroseconds: Duration?
    get() = when (val value = unwrapped) {
        is DBusValue.I64 -> value.value.microseconds
        is DBusValue.U64 -> value.value.toLong().microseconds
        is DBusValue.I32 -> value.value.microseconds
        is DBusValue.U32 -> value.value.toLong().microseconds
        else -> null
    }

internal const val PLAYER_PREFIX = "org.mpris.MediaPlayer2."
