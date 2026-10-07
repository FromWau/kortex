package com.fromwau.kortex.mpris

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.assertSuccess
import com.fromwau.kern.result.errorOrNull
import com.fromwau.kern.result.getOrNull
import com.fromwau.kortex.dbus.Backoff
import com.fromwau.kortex.dbus.BusState
import com.fromwau.kortex.dbus.DBusConnection
import com.fromwau.kortex.dbus.DBusValue
import com.fromwau.kortex.dbus.PrivateBus
import com.fromwau.kortex.dbus.SessionBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The players on a `dbus-daemon` of the test's own, each one a [FakePlayer] the test exports. */
class MprisTest {
    private val bus = PrivateBus()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val opened = mutableListOf<DBusConnection>()
    private val session = SessionBus.at(bus.socket, scope, FAST)
    private val mpris = Mpris(session, scope)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        opened.forEach { it.close() }
        bus.close()
    }

    @Test
    fun `a player on the bus is listed with its identity, track and status`() = runBlocking<Unit> {
        player(NAME)

        val listed = mpris.players.alsoWatched().awaitPlayer(NAME) { true }

        assertEquals("Fake", listed.identity)
        assertEquals("fake", listed.desktopEntry)
        assertEquals(PlaybackStatus.Paused, listed.status)
        assertEquals(Track("/fake/track/1", "First", listOf("Someone"), null, null, 180.seconds), listed.track)
        assertEquals(true, listed.canRaise)
    }

    @Test
    fun `playerctld is left out, since it mirrors a player that is already listed`() = runBlocking<Unit> {
        player("org.mpris.MediaPlayer2.playerctld")
        player(NAME)

        mpris.players.alsoWatched().awaitPlayer(NAME) { true }

        assertEquals(listOf(NAME), mpris.players.value.getOrNull()?.map { it.busName })
    }

    @Test
    fun `a player that arrives is added, and one that leaves the bus is removed`() = runBlocking<Unit> {
        val players = mpris.players.alsoWatched()
        players.awaitValue("an empty list") { it == Ok(emptyList<Player>()) }

        val fake = player(NAME)
        players.awaitPlayer(NAME) { true }
        fake.connection.close()

        players.awaitValue("the player gone") { it == Ok(emptyList<Player>()) }
    }

    @Test
    fun `a change the player announces reaches its snapshot, and a new track reads the position again`() =
        runBlocking<Unit> {
            val fake = player(NAME)
            val players = mpris.players.alsoWatched()
            players.awaitPlayer(NAME) { true }

            fake.player = fake.player + ("Position" to DBusValue.I64(42_000_000))
            fake.change(
                "PlaybackStatus" to DBusValue.Text("Playing"),
                "Metadata" to dictionary("xesam:title" to DBusValue.Text("Second")),
            )

            val changed = players.awaitPlayer(NAME) { it.track.title == "Second" }
            assertEquals(PlaybackStatus.Playing, changed.status)
            assertEquals(42.seconds, changed.position?.at)
        }

    @Test
    fun `a property the player only says is invalid is read again`() = runBlocking<Unit> {
        val fake = player(NAME)
        val players = mpris.players.alsoWatched()
        players.awaitPlayer(NAME) { true }

        fake.player = fake.player + ("Metadata" to dictionary("xesam:title" to DBusValue.Text("Asked for")))
        fake.invalidate("Metadata")

        players.awaitPlayer(NAME) { it.track.title == "Asked for" }
    }

    @Test
    fun `a seek moves the position to where the player says`() = runBlocking<Unit> {
        val fake = player(NAME)
        val players = mpris.players.alsoWatched()
        players.awaitPlayer(NAME) { true }

        fake.seeked(90.seconds)

        players.awaitPlayer(NAME) { it.position?.at == 90.seconds }
    }

    @Test
    fun `the position ticks forward while the player plays, and holds while it does not`() = runBlocking<Unit> {
        val fake = player(NAME)
        val players = mpris.players.alsoWatched()
        val paused = players.awaitPlayer(NAME) { true }
        assertEquals(0.seconds, mpris.position(paused, tick = 50.milliseconds).first())

        fake.player = fake.player + ("Position" to DBusValue.I64(42_000_000))
        fake.change("PlaybackStatus" to DBusValue.Text("Playing"))
        val playing = players.awaitPlayer(NAME) { it.status == PlaybackStatus.Playing }

        val ticks = withTimeoutOrNull(SETTLE) { mpris.position(playing, tick = 50.milliseconds).take(4).toList() }
            ?: fail("the position did not tick")
        assertTrue(ticks.all { it != null && it >= 42.seconds }, "ticks: $ticks")
        assertTrue(ticks.zipWithNext().all { (a, b) -> b!! > a!! }, "the position did not move: $ticks")
    }

    @Test
    fun `every command reaches the player with the arguments the interface defines`() = runBlocking<Unit> {
        val fake = player(NAME)
        val listed = mpris.players.alsoWatched().awaitPlayer(NAME) { true }

        mpris.playPause(listed).assertSuccess()
        mpris.next(listed).assertSuccess()
        mpris.seek(listed, (-5).seconds).assertSuccess()
        mpris.setPosition(listed, 30.seconds).assertSuccess()
        mpris.setVolume(listed, 0.5).assertSuccess()
        mpris.setLoop(listed, LoopStatus.Playlist).assertSuccess()
        mpris.raise(listed).assertSuccess()

        val player = FakePlayer.PLAYER
        assertEquals(
            listOf(
                FakePlayer.Called(player, "PlayPause", emptyList()),
                FakePlayer.Called(player, "Next", emptyList()),
                FakePlayer.Called(player, "Seek", listOf(DBusValue.I64(-5_000_000))),
                FakePlayer.Called(
                    player,
                    "SetPosition",
                    listOf(DBusValue.ObjectPath("/fake/track/1"), DBusValue.I64(30_000_000)),
                ),
                FakePlayer.Called(PROPERTIES, "Set", setting("Volume", DBusValue.F64(0.5))),
                FakePlayer.Called(PROPERTIES, "Set", setting("LoopStatus", DBusValue.Text("Playlist"))),
                FakePlayer.Called(FakePlayer.ROOT, "Raise", emptyList()),
            ),
            fake.calls.value,
        )
    }

    @Test
    fun `moving to a position needs the track's id, and a player that names none is told so`() =
        runBlocking<Unit> {
            val fake = player(NAME)
            fake.player = fake.player + ("Metadata" to dictionary("xesam:title" to DBusValue.Text("No id")))
            val listed = mpris.players.alsoWatched().awaitPlayer(NAME) { true }

            assertEquals(Err(MprisError.NoTrack), mpris.setPosition(listed, 1.seconds))
        }

    @Test
    fun `the players are read again once the bus is back`() = runBlocking<Unit> {
        player(NAME)
        val players = mpris.players.alsoWatched()
        players.awaitPlayer(NAME) { true }

        bus.kill()
        players.awaitValue("the bus going down") { it.errorOrNull() is MprisError.BusDown }
        bus.restart()
        player(NAME)

        players.awaitPlayer(NAME) { true }
    }

    @Test
    fun `a command while the bus is down says so`() = runBlocking<Unit> {
        player(NAME)
        val listed = mpris.players.alsoWatched().awaitPlayer(NAME) { true }
        bus.kill()
        session.state.awaitValue("the bus down") { it is BusState.Down }

        assertIs<MprisError.BusDown>(mpris.playPause(listed).errorOrNull())
    }

    private suspend fun player(name: String): FakePlayer = FakePlayer.on(connect(), name)

    private suspend fun connect(): DBusConnection =
        DBusConnection.open(bus.socket).assertSuccess().also { opened += it }

    private fun setting(property: String, value: DBusValue): List<DBusValue> =
        listOf(DBusValue.Text(FakePlayer.PLAYER), DBusValue.Text(property), DBusValue.Variant(value))

    private fun <T> StateFlow<T>.alsoWatched(): StateFlow<T> = also { flow -> scope.launch { flow.collect {} } }

    private suspend fun StateFlow<Result<List<Player>, MprisError>>.awaitPlayer(
        name: String,
        until: (Player) -> Boolean,
    ): Player = awaitValue("$name") { outcome -> outcome.getOrNull().orEmpty().any { it.busName == name && until(it) } }
        .getOrNull()!!
        .first { it.busName == name }

    private suspend fun <T> StateFlow<T>.awaitValue(
        what: String,
        until: (T) -> Boolean,
    ): T = withTimeoutOrNull(SETTLE) { first(until) } ?: fail("never saw $what within $SETTLE: last was $value")

    private companion object {
        const val NAME = "org.mpris.MediaPlayer2.fake"
        const val PROPERTIES = "org.freedesktop.DBus.Properties"
        val FAST = Backoff(first = 20.milliseconds, cap = 200.milliseconds)
        val SETTLE = 10.seconds
    }
}
