package com.fromwau.kortex.mpris

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertSuccess
import com.fromwau.kortex.dbus.Bus
import com.fromwau.kortex.dbus.CallRejected
import com.fromwau.kortex.dbus.DBusConnection
import com.fromwau.kortex.dbus.DBusType
import com.fromwau.kortex.dbus.DBusValue
import com.fromwau.kortex.dbus.NameRequest
import com.fromwau.kortex.dbus.asText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.test.assertEquals
import kotlin.time.Duration

/**
 * An MPRIS player on a connection of its own: it answers from properties the test sets, and records every
 * call made to it.
 */
internal class FakePlayer(
    val connection: DBusConnection,
    val name: String,
) {
    val calls = MutableStateFlow<List<Called>>(emptyList())

    @Volatile
    var root: Map<String, DBusValue> = mapOf(
        "Identity" to DBusValue.Text("Fake"),
        "DesktopEntry" to DBusValue.Text("fake"),
        "CanRaise" to DBusValue.Bool(true),
    )

    @Volatile
    var player: Map<String, DBusValue> = mapOf(
        "PlaybackStatus" to DBusValue.Text("Paused"),
        "Metadata" to dictionary(
            "mpris:trackid" to DBusValue.ObjectPath("/fake/track/1"),
            "xesam:title" to DBusValue.Text("First"),
            "xesam:artist" to DBusValue.Sequence(DBusType.Basic.Text, listOf(DBusValue.Text("Someone"))),
            "mpris:length" to DBusValue.I64(180_000_000),
        ),
        "Position" to DBusValue.I64(0),
        "Rate" to DBusValue.F64(1.0),
        "CanControl" to DBusValue.Bool(true),
        "CanPlay" to DBusValue.Bool(true),
        "CanPause" to DBusValue.Bool(true),
        "CanGoNext" to DBusValue.Bool(true),
        "CanSeek" to DBusValue.Bool(true),
    )

    data class Called(
        val iface: String?,
        val member: String,
        val args: List<DBusValue>,
    )

    fun export() {
        connection.export(PATH) { call ->
            when {
                call.iface == Bus.PROPERTIES && call.member == "GetAll" ->
                    Ok(listOf(dictionary(*bagOf(call.body).toList().toTypedArray())))

                call.iface == Bus.PROPERTIES && call.member == "Get" ->
                    bagOf(call.body)[call.body.getOrNull(1)?.asText]
                        ?.let { Ok(listOf(DBusValue.Variant(it))) }
                        ?: Err(CallRejected.unknownMethod(call))

                call.iface == Bus.PROPERTIES && call.member == "Set" || call.iface == PLAYER || call.iface == ROOT -> {
                    calls.update { it + Called(call.iface, call.member, call.body) }
                    Ok(emptyList())
                }

                else -> Err(CallRejected.unknownMethod(call))
            }
        }
    }

    /** Changes the player's properties and says so, the way a player does when its state moves. */
    suspend fun change(vararg changed: Pair<String, DBusValue>) {
        player = player + changed
        connection.emit(
            PATH,
            Bus.PROPERTIES,
            "PropertiesChanged",
            listOf(DBusValue.Text(PLAYER), dictionary(*changed), DBusValue.Sequence(DBusType.Basic.Text, emptyList())),
        ).assertSuccess()
    }

    /** Says [names] changed without saying to what, which leaves the reader to ask again. */
    suspend fun invalidate(vararg names: String) {
        connection.emit(
            PATH,
            Bus.PROPERTIES,
            "PropertiesChanged",
            listOf(
                DBusValue.Text(PLAYER),
                dictionary(),
                DBusValue.Sequence(DBusType.Basic.Text, names.map(DBusValue::Text)),
            ),
        ).assertSuccess()
    }

    suspend fun seeked(to: Duration) {
        connection.emit(PATH, PLAYER, "Seeked", listOf(DBusValue.I64(to.inWholeMicroseconds))).assertSuccess()
    }

    /** The properties of whichever interface a `Get` or `GetAll` names first. */
    private fun bagOf(body: List<DBusValue>): Map<String, DBusValue> =
        if (body.firstOrNull()?.asText == ROOT) root else player

    companion object {
        const val PATH = "/org/mpris/MediaPlayer2"
        const val ROOT = "org.mpris.MediaPlayer2"
        const val PLAYER = "org.mpris.MediaPlayer2.Player"

        /** A player exported on [connection] and holding [name], in that order so a reader never finds it half made. */
        suspend fun on(connection: DBusConnection, name: String): FakePlayer =
            FakePlayer(connection, name).also { fake ->
                fake.export()
                assertEquals(NameRequest.Held, connection.requestName(name).assertSuccess())
            }
    }
}

internal fun dictionary(vararg entries: Pair<String, DBusValue>): DBusValue.Sequence = DBusValue.Sequence(
    DBusType.Pair(DBusType.Basic.Text, DBusType.Variant),
    entries.map { (key, value) -> DBusValue.Pair(DBusValue.Text(key), DBusValue.Variant(value)) },
)
