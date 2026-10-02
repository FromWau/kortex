package com.fromwau.kortex.tray

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.getOrElse
import com.fromwau.kortex.dbus.Bus
import com.fromwau.kortex.dbus.CallRejected
import com.fromwau.kortex.dbus.DBusConnection
import com.fromwau.kortex.dbus.DBusType
import com.fromwau.kortex.dbus.DBusValue
import com.fromwau.kortex.dbus.NameRequest
import com.fromwau.kortex.dbus.asText
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

/**
 * Answers one question before `:tray` grows a watcher: do applications already running re-register when a
 * watcher appears, or do they only ever register at startup?
 *
 * Answered, on a Hyprland session with ags stopped: **they re-register.** Steam came back the instant the
 * name was taken, with nothing restarted, so a kortex watcher adopts the tray that is already running.
 * KDE Connect did more than that and started a *second* indicator beside the one already connected, so
 * taking this name is not a passive act and whoever writes the real watcher should expect duplicates.
 *
 * Kept until `:tray` has a watcher of its own, then delete it: it takes the name, prints what arrives and
 * gives the name back, which is most of that watcher's method handling and the only way to exercise it
 * against real applications until there is something better.
 *
 * Run with `./gradlew :tray:probe -Pprobe=com.fromwau.kortex.tray.WatcherProbeKt`.
 */
fun main(): Unit = runBlocking {
    val connection = DBusConnection.session().getOrElse { error ->
        println("no session bus: $error")
        return@runBlocking
    }

    connection.use { bus ->
        val taken = bus.requestName(WATCHER_NAME).getOrElse { error ->
            println("could not ask for the name: $error")
            return@runBlocking
        }
        if (taken != NameRequest.Held && taken != NameRequest.AlreadyHeld) {
            println("something already owns $WATCHER_NAME ($taken), so this proves nothing. Stop it first.")
            return@runBlocking
        }
        println("holding $WATCHER_NAME as ${bus.uniqueName}")

        val items = ConcurrentHashMap.newKeySet<String>()
        // One handler for the path: a connection keeps one export per path, so the watcher's own methods
        // and the property reads an application makes before registering both have to come from here.
        bus.export(WATCHER_PATH) { call ->
            when {
                call.iface == WATCHER_NAME && call.member == "RegisterStatusNotifierItem" -> {
                    val argument = call.body.firstOrNull()?.asText.orEmpty()
                    val entry = if (argument.startsWith("/")) "${call.sender}$argument" else argument
                    items += entry
                    println("  REGISTERED  $entry   (sender ${call.sender})")
                    bus.emit(
                        WATCHER_PATH, WATCHER_NAME, "StatusNotifierItemRegistered",
                        listOf(DBusValue.Text(entry)),
                    )
                    Ok(emptyList())
                }

                call.iface == WATCHER_NAME && call.member == "RegisterStatusNotifierHost" -> {
                    println("  HOST        ${call.body.firstOrNull()?.asText} wants to draw them")
                    Ok(emptyList())
                }

                call.iface == Bus.PROPERTIES && call.member == "Get" ->
                    property(call.body.getOrNull(1)?.asText, items)?.let { Ok(listOf(it)) }
                        ?: Err(CallRejected.unknownMethod(call))

                call.iface == Bus.PROPERTIES && call.member == "GetAll" -> Ok(listOf(everything(items)))

                else -> Err(CallRejected.unknownMethod(call))
            }
        }

        println("listening ${WINDOW.inWholeSeconds}s; anything that re-registers prints below")
        repeat(WINDOW.inWholeSeconds.toInt()) { second ->
            delay(1.seconds)
            if (second == HALFWAY) println("  ...${items.size} so far")
        }

        println("done: ${items.size} item(s): $items")
        bus.releaseName(WATCHER_NAME)
    }
}

/** The two properties an application reads before deciding whether a tray icon is worth exporting. */
private fun property(name: String?, items: Set<String>): DBusValue? = when (name) {
    "RegisteredStatusNotifierItems" -> DBusValue.Variant(registered(items))
    "IsStatusNotifierHostRegistered" -> DBusValue.Variant(DBusValue.Bool(true))
    "ProtocolVersion" -> DBusValue.Variant(DBusValue.I32(0))
    else -> null
}

private fun everything(items: Set<String>): DBusValue = DBusValue.Sequence(
    DBusType.Pair(DBusType.Basic.Text, DBusType.Variant),
    listOf(
        DBusValue.Pair(DBusValue.Text("RegisteredStatusNotifierItems"), DBusValue.Variant(registered(items))),
        DBusValue.Pair(DBusValue.Text("IsStatusNotifierHostRegistered"), DBusValue.Variant(DBusValue.Bool(true))),
    ),
)

private fun registered(items: Set<String>): DBusValue =
    DBusValue.Sequence(DBusType.Basic.Text, items.map(DBusValue::Text))

private const val WATCHER_NAME = "org.kde.StatusNotifierWatcher"
private const val WATCHER_PATH = "/StatusNotifierWatcher"
private const val HALFWAY = 10
private val WINDOW = 20.seconds
