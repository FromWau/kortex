package com.fromwau.kortex.mpris

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.getOrElse
import com.fromwau.kern.result.getOrNull
import com.fromwau.kern.result.map
import com.fromwau.kern.result.mapError
import com.fromwau.kortex.dbus.Bus
import com.fromwau.kortex.dbus.DBusConnection
import com.fromwau.kortex.dbus.DBusValue
import com.fromwau.kortex.dbus.MatchRule
import com.fromwau.kortex.dbus.Message
import com.fromwau.kortex.dbus.NameOwnerChange
import com.fromwau.kortex.dbus.SessionBus
import com.fromwau.kortex.dbus.asDictionary
import com.fromwau.kortex.dbus.asItems
import com.fromwau.kortex.dbus.asText
import com.fromwau.kortex.dbus.nameOwnerChange
import com.fromwau.kortex.dbus.watching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The media players on the session bus, as data, and the transport to drive them.
 *
 * ```kotlin
 * val mpris = Mpris(bus, scope)
 * mpris.players.collect { outcome ->
 *     val playing = outcome.getOrNull()?.firstOrNull { it.status == PlaybackStatus.Playing }
 *     show(playing?.track?.title)
 * }
 * ```
 *
 * Every application that registers an `org.mpris.MediaPlayer2.*` name is listed, except `playerctld`,
 * which mirrors whichever real player was used last and would show its track twice. Which player is "the"
 * player is the caller's choice. Nothing runs while nobody collects [players], and it follows [bus] across
 * restarts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
public class Mpris(
    private val bus: SessionBus,
    scope: CoroutineScope,
) {
    /** Every player, ordered by bus name, or why there are none to give. */
    public val players: StateFlow<Result<List<Player>, MprisError>> = bus
        .following(::unavailable) { connection -> track(connection) }
        .stateIn(scope, SharingStarted.WhileSubscribed(), Err(MprisError.NotConnected))

    /**
     * Where [player] is in its track, once per [tick] while it plays and once whenever it stops moving.
     *
     * Carried forward from the last reading rather than asked for each time, and read again whenever the
     * player seeks, changes track, starts, stops or changes rate. Null while the player reports no
     * position or is gone.
     */
    public fun position(player: Player, tick: Duration = 1.seconds): Flow<Duration?> = players
        .map { outcome -> outcome.getOrNull()?.firstOrNull { it.busName == player.busName } }
        .distinctUntilChanged()
        .flatMapLatest { current ->
            when {
                current == null -> flowOf(null)
                current.status != PlaybackStatus.Playing -> flowOf(current.positionNow())
                else -> flow {
                    while (true) {
                        emit(current.positionNow())
                        delay(tick)
                    }
                }
            }
        }

    public suspend fun play(player: Player): EmptyResult<MprisError> = command(player, "Play")

    public suspend fun pause(player: Player): EmptyResult<MprisError> = command(player, "Pause")

    public suspend fun playPause(player: Player): EmptyResult<MprisError> = command(player, "PlayPause")

    public suspend fun stop(player: Player): EmptyResult<MprisError> = command(player, "Stop")

    public suspend fun next(player: Player): EmptyResult<MprisError> = command(player, "Next")

    public suspend fun previous(player: Player): EmptyResult<MprisError> = command(player, "Previous")

    /** Moves playback by [offset], back where it is negative. */
    public suspend fun seek(player: Player, offset: Duration): EmptyResult<MprisError> =
        command(player, "Seek", listOf(DBusValue.I64(offset.inWholeMicroseconds)))

    /**
     * Moves playback to [position] in the track the player has loaded.
     *
     * The specification has a player ignore this unless it names the current track, so it is sent with
     * [Player.track]'s id.
     *
     * @return [MprisError.NoTrack] where the player reports no track id, which [seek] does not need.
     */
    public suspend fun setPosition(player: Player, position: Duration): EmptyResult<MprisError> {
        val track = player.track.id ?: return Err(MprisError.NoTrack)
        return command(
            player,
            "SetPosition",
            listOf(DBusValue.ObjectPath(track), DBusValue.I64(position.inWholeMicroseconds)),
        )
    }

    /** Sets the volume, where 1.0 is the player's full volume. */
    public suspend fun setVolume(player: Player, volume: Double): EmptyResult<MprisError> =
        set(player, "Volume", DBusValue.F64(volume))

    public suspend fun setShuffle(player: Player, shuffle: Boolean): EmptyResult<MprisError> =
        set(player, "Shuffle", DBusValue.Bool(shuffle))

    public suspend fun setLoop(player: Player, loop: LoopStatus): EmptyResult<MprisError> =
        set(player, "LoopStatus", DBusValue.Text(loop.wireName))

    /** Brings the player's own window to the front. */
    public suspend fun raise(player: Player): EmptyResult<MprisError> = bus.withConnection(::busDown) { connection ->
        connection.call(player.busName, PATH, ROOT, "Raise").mapError(MprisError::BusFailed).map { }
    }

    private suspend fun command(
        player: Player,
        member: String,
        args: List<DBusValue> = emptyList(),
    ): EmptyResult<MprisError> = bus.withConnection(::busDown) { connection ->
        connection.call(player.busName, PATH, PLAYER, member, args).mapError(MprisError::BusFailed).map { }
    }

    private suspend fun set(player: Player, property: String, value: DBusValue): EmptyResult<MprisError> =
        bus.withConnection(::busDown) { connection ->
            connection.setProperty(player.busName, PATH, PLAYER, property, value).mapError(MprisError::BusFailed)
        }

    /** The players on one connection: read once, then kept up from signals. */
    private fun track(connection: DBusConnection): Flow<Result<List<Player>, MprisError>> =
        connection.watching(RULES, ruleFailed = { send(Err(MprisError.BusFailed(it))) }) { signals ->
            val names = connection
                .call(Bus.NAME, Bus.PATH, Bus.INTERFACE, "ListNames")
                .getOrElse {
                    send(Err(MprisError.BusFailed(it)))
                    return@watching
                }
                .firstOrNull()
                ?.asItems
                ?.mapNotNull { it.asText }
                .orEmpty()

            val known = mutableMapOf<String, Known>()
            names.filter(::isPlayer).forEach { name -> read(connection, name)?.let { known[name] = it } }
            send(Ok(known.snapshot()))

            for (signal in signals) {
                if (apply(connection, known, signal)) send(Ok(known.snapshot()))
            }
        }

    /** Folds one signal into [known], and says whether anything a caller sees changed. */
    private suspend fun apply(
        connection: DBusConnection,
        known: MutableMap<String, Known>,
        signal: Message.Signal,
    ): Boolean {
        signal.nameOwnerChange?.let { change -> return handedOver(connection, known, change) }
        return when {
            signal.path != PATH -> false

            signal.iface == Bus.PROPERTIES && signal.member == PROPERTIES_CHANGED -> {
                val name = known.nameOwnedBy(signal.sender) ?: return false
                val current = known.getValue(name)
                val iface = signal.body.getOrNull(0)?.asText
                val changed = signal.body.getOrNull(1)?.asDictionary.orEmpty()
                val invalidated = signal.body.getOrNull(2)?.asItems.orEmpty().isNotEmpty()

                // An invalidated property says it changed without saying to what, so the bag is read again.
                suspend fun updated(iface: String, bag: Map<String, DBusValue>) =
                    if (invalidated) reread(connection, name, iface, bag) else bag + changed

                known[name] = when (iface) {
                    ROOT -> current.copy(root = updated(ROOT, current.root))

                    PLAYER -> {
                        val player = updated(PLAYER, current.player)
                        // Position sends no change of its own, so whatever makes it jump is when to read it again.
                        val jumped = changed.keys.any { it in MOVES_POSITION }
                        current.copy(
                            player = player,
                            position = if (jumped) readPosition(connection, name) else current.position,
                        )
                    }

                    else -> current
                }
                true
            }

            signal.iface == PLAYER && signal.member == SEEKED -> {
                val name = known.nameOwnedBy(signal.sender) ?: return false
                val at = signal.body.firstOrNull()?.asMicroseconds ?: return false
                known[name] = known.getValue(name).copy(position = PlayPosition(at, TimeSource.Monotonic.markNow()))
                true
            }

            else -> false
        }
    }

    /** A player arriving, leaving or changing hands, and whether a caller sees any of it. */
    private suspend fun handedOver(
        connection: DBusConnection,
        known: MutableMap<String, Known>,
        change: NameOwnerChange,
    ): Boolean = when {
        !isPlayer(change.name) -> false
        change.newOwner == null -> known.remove(change.name) != null
        else -> {
            read(connection, change.name)?.let { known[change.name] = it }
            true
        }
    }

    /** Everything about one player, or null where it left the bus before it could be read. */
    private suspend fun read(connection: DBusConnection, name: String): Known? {
        val owner = connection.nameOwner(name).getOrNull() ?: return null
        val root = connection.properties(name, PATH, ROOT).getOrNull() ?: return null
        val player = connection.properties(name, PATH, PLAYER).getOrNull() ?: return null
        return Known(owner, root, player, positionFrom(player))
    }

    private suspend fun reread(
        connection: DBusConnection,
        name: String,
        iface: String,
        fallback: Map<String, DBusValue>,
    ): Map<String, DBusValue> = connection.properties(name, PATH, iface).getOrNull() ?: fallback

    private suspend fun readPosition(connection: DBusConnection, name: String): PlayPosition? =
        connection.property(name, PATH, PLAYER, POSITION).getOrNull()?.asMicroseconds?.let(::measuredNow)

    private fun positionFrom(player: Map<String, DBusValue>): PlayPosition? =
        player[POSITION]?.asMicroseconds?.let(::measuredNow)

    private fun measuredNow(at: Duration) = PlayPosition(at, TimeSource.Monotonic.markNow())

    /** A player as read off the bus, kept raw so a change to one property merges into the rest. */
    private data class Known(
        val owner: String,
        val root: Map<String, DBusValue>,
        val player: Map<String, DBusValue>,
        val position: PlayPosition?,
    )

    private fun Map<String, Known>.snapshot(): List<Player> =
        entries.sortedBy { it.key }.map { (name, known) -> playerFrom(name, known.root, known.player, known.position) }

    /** Signals arrive from a unique name, and players are known by their well-known one. */
    private fun Map<String, Known>.nameOwnedBy(sender: String?): String? =
        entries.firstOrNull { it.value.owner == sender }?.key

    private companion object {
        const val PATH = "/org/mpris/MediaPlayer2"
        const val ROOT = "org.mpris.MediaPlayer2"
        const val PLAYER = "org.mpris.MediaPlayer2.Player"
        const val POSITION = "Position"
        const val PROPERTIES_CHANGED = "PropertiesChanged"
        const val SEEKED = "Seeked"

        /** Mirrors whichever real player was used last, so listing it would show that player twice. */
        const val PLAYERCTLD = "org.mpris.MediaPlayer2.playerctld"

        /** The properties whose change means the position jumped rather than moved on. */
        val MOVES_POSITION = setOf("PlaybackStatus", "Rate", "Metadata")

        val RULES = listOf(
            MatchRule(sender = Bus.NAME, iface = Bus.INTERFACE, member = Bus.NAME_OWNER_CHANGED),
            MatchRule(iface = Bus.PROPERTIES, member = PROPERTIES_CHANGED, path = PATH),
            MatchRule(iface = PLAYER, member = SEEKED, path = PATH),
        )

        fun isPlayer(name: String): Boolean = name.startsWith(PLAYER_PREFIX) && name != PLAYERCTLD
    }
}
