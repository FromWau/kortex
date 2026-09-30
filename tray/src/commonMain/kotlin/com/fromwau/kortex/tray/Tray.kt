package com.fromwau.kortex.tray

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.errorOrNull
import com.fromwau.kern.result.getOrElse
import com.fromwau.kern.result.getOrNull
import com.fromwau.kern.result.map
import com.fromwau.kern.result.mapError
import com.fromwau.kortex.dbus.Bus
import com.fromwau.kortex.dbus.DBusConnection
import com.fromwau.kortex.dbus.DBusError
import com.fromwau.kortex.dbus.DBusValue
import com.fromwau.kortex.dbus.MatchRule
import com.fromwau.kortex.dbus.Message
import com.fromwau.kortex.dbus.asDictionary
import com.fromwau.kortex.dbus.asItems
import com.fromwau.kortex.dbus.asText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which way a scroll went, under the two words the specification allows. */
public enum class ScrollOrientation(internal val wireName: String) {
    Horizontal("horizontal"),
    Vertical("vertical"),
}

/**
 * The system tray, as data.
 *
 * Reads what applications have put in the tray and passes on what a user did to one. It draws nothing and
 * decides nothing about drawing: an icon arrives as a theme name or as ARGB bytes and stays that way.
 *
 * Nothing runs while nobody collects [items]. The match rules go up on the first collector and come down
 * with the last one, so an unwatched tray costs the bus no routing.
 */
public class Tray(private val connection: DBusConnection, private val scope: CoroutineScope) {
    /**
     * Every item in the tray, or why there are none.
     *
     * One flow rather than data beside a status, so nothing has to reconcile two of them. The first value
     * is [TrayError.NotConnected] because a flow always holds one and an empty list would read the same
     * as a tray nobody has put anything in.
     */
    public val items: StateFlow<Result<List<TrayItem>, TrayError>> = track()
        .stateIn(scope, SharingStarted.WhileSubscribed(), Err(TrayError.NotConnected))

    /** Tells the item it was clicked, at the screen position it was clicked at. */
    public suspend fun activate(address: ItemAddress, x: Int = 0, y: Int = 0): EmptyResult<TrayError> =
        command(address, "Activate", listOf(DBusValue.I32(x), DBusValue.I32(y)))

    /** Tells the item it was middle-clicked. */
    public suspend fun secondaryActivate(address: ItemAddress, x: Int = 0, y: Int = 0): EmptyResult<TrayError> =
        command(address, "SecondaryActivate", listOf(DBusValue.I32(x), DBusValue.I32(y)))

    /**
     * Asks the item to show its own context menu.
     *
     * For an item whose [TrayItem.menuPath] is set, a caller may prefer to draw that menu itself; this is
     * what an item without one offers instead, and what [TrayItem.isMenu] asks for.
     */
    public suspend fun contextMenu(address: ItemAddress, x: Int = 0, y: Int = 0): EmptyResult<TrayError> =
        command(address, "ContextMenu", listOf(DBusValue.I32(x), DBusValue.I32(y)))

    public suspend fun scroll(
        address: ItemAddress,
        delta: Int,
        orientation: ScrollOrientation,
    ): EmptyResult<TrayError> = command(
        address,
        "Scroll",
        listOf(DBusValue.I32(delta), DBusValue.Text(orientation.wireName)),
    )

    /**
     * The menu [item] exports, or null where it exports none.
     *
     * A menu of its own rather than a field on [TrayItem], because an application is allowed to build one
     * only when somebody asks: reading every item's menu up front would make every tray item do work no
     * caller had asked for.
     */
    public fun menu(item: TrayItem): Menu? =
        item.menuPath?.let { path -> Menu(connection, item.address.service, path, scope) }

    private suspend fun command(address: ItemAddress, member: String, args: List<DBusValue>): EmptyResult<TrayError> =
        onItemInterface(address) { iface ->
            connection.call(address.service, address.path, iface, member, args)
        }.map { }

    private fun track(): Flow<Result<List<TrayItem>, TrayError>> = channelFlow {
        val watcher = findWatcher().getOrElse {
            send(Err(it))
            return@channelFlow
        }

        RULES.forEach { rule ->
            connection.addMatch(rule).getOrElse {
                send(Err(TrayError.BusFailed(it)))
                return@channelFlow
            }
        }

        // Subscribed before the first read, so an item that registers between the two is not missed.
        val signals = Channel<Message.Signal>(Channel.UNLIMITED)
        val subscribed = CompletableDeferred<Unit>()
        launch {
            connection.allSignals
                .onSubscription { subscribed.complete(Unit) }
                .collect { signal -> signals.send(signal) }
        }
        subscribed.await()

        connection.post(
            destination = watcher.service,
            path = WATCHER_PATH,
            iface = watcher.iface,
            member = "RegisterStatusNotifierHost",
            args = listOf(DBusValue.Text(connection.uniqueName)),
        )

        var known = readAll(watcher).getOrElse {
            send(Err(it))
            return@channelFlow
        }
        send(Ok(known.asItems()))

        for (signal in signals) {
            known = known.after(signal, watcher) ?: continue
            send(Ok(known.asItems()))
        }
    }.onCompletion {
        // NonCancellable: this runs as the last collector goes away, which is usually a cancellation, and
        // a rule left behind would have the bus routing signals to nobody for the rest of the session.
        withContext(NonCancellable) { RULES.forEach { rule -> connection.removeMatch(rule) } }
    }

    /**
     * How a signal changes what is known, or null where it changes nothing.
     *
     * Properties are kept as they arrived rather than as decoded items, because `PropertiesChanged` hands
     * over the changed ones and merging them costs no round trip. The `New*` signals carry nothing, so
     * those do cost one, which is the specification's design and not a choice available here.
     */
    private suspend fun Known.after(signal: Message.Signal, watcher: Watcher): Known? = when {
        signal.iface == watcher.iface -> afterWatcherSignal(signal)
        signal.iface in ITEM_INTERFACES -> afterItemSignal(signal)
        signal.iface == Bus.PROPERTIES && signal.member == PROPERTIES_CHANGED -> afterPropertiesChanged(signal)
        signal.iface == Bus.INTERFACE && signal.member == NAME_OWNER_CHANGED -> afterNameOwnerChanged(signal)
        else -> null
    }

    private suspend fun Known.afterWatcherSignal(signal: Message.Signal): Known? {
        val entry = signal.body.firstOrNull()?.asText ?: return null
        val address = ItemAddress.parse(entry, signal.sender) ?: return null
        return when (signal.member) {
            ITEM_REGISTERED -> readItem(address)?.let { this + (address to it) }
            ITEM_UNREGISTERED -> takeIf { address in it }?.minus(address)
            else -> null
        }
    }

    private suspend fun Known.afterItemSignal(signal: Message.Signal): Known? {
        val address = addressOf(signal) ?: return null
        // NewStatus is the one that carries its own value, so it is the one that needs no read back.
        if (signal.member == NEW_STATUS) {
            val status = signal.body.firstOrNull()?.asText ?: return null
            return this + (address to getValue(address) + ("Status" to DBusValue.Text(status)))
        }
        if (!signal.member.startsWith("New")) return null
        return readItem(address)?.let { this + (address to it) }
    }

    private fun Known.afterPropertiesChanged(signal: Message.Signal): Known? {
        if (signal.body.firstOrNull()?.asText !in ITEM_INTERFACES) return null
        val address = addressOf(signal) ?: return null

        val changed = signal.body.getOrNull(1)?.asDictionary.orEmpty()
        val invalidated = signal.body.getOrNull(2)?.asItems?.mapNotNull { it.asText }.orEmpty()
        if (changed.isEmpty() && invalidated.isEmpty()) return null

        return this + (address to (getValue(address) + changed - invalidated.toSet()))
    }

    /** An application that exits takes its items with it, whether or not the watcher noticed. */
    private fun Known.afterNameOwnerChanged(signal: Message.Signal): Known? {
        val name = signal.body.firstOrNull()?.asText ?: return null
        val newOwner = signal.body.getOrNull(2)?.asText ?: return null
        if (newOwner.isNotEmpty()) return null

        val gone = keys.filter { it.service == name }
        return takeIf { gone.isNotEmpty() }?.minus(gone.toSet())
    }

    private fun Known.addressOf(signal: Message.Signal): ItemAddress? =
        keys.firstOrNull { it.service == signal.sender && it.path == signal.path }

    private suspend fun findWatcher(): Result<Watcher, TrayError> {
        WATCHERS.forEach { candidate ->
            if (connection.nameOwner(candidate.service).getOrNull() != null) return Ok(candidate)
        }
        return Err(TrayError.NoWatcher)
    }

    private suspend fun readAll(watcher: Watcher): Result<Known, TrayError> {
        val registered = connection
            .property(watcher.service, WATCHER_PATH, watcher.iface, "RegisteredStatusNotifierItems")
            .mapError(TrayError::BusFailed)
            .getOrElse { return Err(it) }

        val entries = registered.asItems?.mapNotNull { it.asText }.orEmpty()
        val known = linkedMapOf<ItemAddress, Map<String, DBusValue>>()
        entries.forEach { entry ->
            val address = ItemAddress.parse(entry) ?: return@forEach
            readItem(address)?.let { known[address] = it }
        }
        return Ok(known)
    }

    /**
     * One item's properties, or null where it answered on neither interface.
     *
     * An item that has just gone away is the ordinary reason for null, and it is not an error: the
     * unregistration is on its way and the tray simply carries one fewer item until it lands.
     */
    private suspend fun readItem(address: ItemAddress): Map<String, DBusValue>? =
        onItemInterface(address) { iface -> connection.properties(address.service, address.path, iface) }
            .getOrElse { return null }
            .takeIf { it.isNotEmpty() }

    /**
     * Runs [attempt] against whichever interface the item answers on.
     *
     * The specification says `org.freedesktop.StatusNotifierItem` and almost nothing implements it: KDE's
     * name is what won, and an item that speaks one rejects the other outright. Trying in turn costs a
     * round trip only for the rarer kind.
     */
    private suspend fun <T> onItemInterface(
        address: ItemAddress,
        attempt: suspend (String) -> Result<T, DBusError>,
    ): Result<T, TrayError> {
        var last: DBusError? = null
        ITEM_INTERFACES.forEach { iface ->
            val outcome = attempt(iface)
            val failure = outcome.errorOrNull() ?: return outcome.mapError(TrayError::BusFailed)
            last = failure
        }
        return Err(TrayError.BusFailed(last ?: DBusError.Disconnected))
    }

    /** A watcher, under a name some desktop actually uses. */
    internal data class Watcher(val service: String, val iface: String)

    private companion object {
        const val WATCHER_PATH = "/StatusNotifierWatcher"
        const val ITEM_REGISTERED = "StatusNotifierItemRegistered"
        const val ITEM_UNREGISTERED = "StatusNotifierItemUnregistered"
        const val NEW_STATUS = "NewStatus"
        const val PROPERTIES_CHANGED = "PropertiesChanged"
        const val NAME_OWNER_CHANGED = "NameOwnerChanged"

        /**
         * KDE's names first, because they are the ones desktops actually use.
         *
         * On a Hyprland session with ags running, `org.freedesktop.StatusNotifierWatcher` does not exist
         * at all and `org.kde.StatusNotifierWatcher` does.
         */
        val WATCHERS = listOf(
            Watcher("org.kde.StatusNotifierWatcher", "org.kde.StatusNotifierWatcher"),
            Watcher("org.freedesktop.StatusNotifierWatcher", "org.freedesktop.StatusNotifierWatcher"),
        )

        val ITEM_INTERFACES = listOf("org.kde.StatusNotifierItem", "org.freedesktop.StatusNotifierItem")

        /**
         * Every rule the tray needs, added together and removed together.
         *
         * `NameOwnerChanged` is unfiltered, which brings in every name change on the bus rather than only
         * the handful the tray tracks. Narrowing it needs `arg0` matching and one rule per item, added and
         * removed as items come and go; a session changes names rarely enough that the noise costs less
         * than that bookkeeping would.
         */
        val RULES = buildList {
            WATCHERS.mapTo(this) { MatchRule(iface = it.iface) }
            ITEM_INTERFACES.mapTo(this) { MatchRule(iface = it) }
            add(MatchRule(iface = Bus.PROPERTIES, member = PROPERTIES_CHANGED))
            add(MatchRule(iface = Bus.INTERFACE, member = NAME_OWNER_CHANGED))
        }
    }
}

/** What the tray holds between signals: each item's properties exactly as the bus sent them. */
private typealias Known = Map<ItemAddress, Map<String, DBusValue>>

private fun Known.asItems(): List<TrayItem> = map { (address, properties) -> trayItemFrom(address, properties) }

