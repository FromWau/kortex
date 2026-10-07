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
import com.fromwau.kortex.dbus.SessionBus
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
import kotlinx.coroutines.flow.transformWhile
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
 * It follows [bus] across restarts: each connection the bus comes up on is read from nothing, and while there
 * is none the tray says why. Nothing runs while nobody collects [items]. The match rules go up on the first
 * collector and come down with the last one, so an unwatched tray costs the bus no routing.
 */
public class Tray(private val bus: SessionBus, private val scope: CoroutineScope) {
    /**
     * Every item in the tray, or why there are none.
     *
     * One flow rather than data beside a status, so nothing has to reconcile two of them. The first value
     * is [TrayError.NotConnected] because a flow always holds one and an empty list would read the same
     * as a tray nobody has put anything in.
     */
    public val items: StateFlow<Result<List<TrayItem>, TrayError>> = bus
        .following(::unavailable) { connection -> TrayOnConnection(connection).track() }
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
        item.menuPath?.let { path -> Menu(bus, item.address.service, path, scope) }

    private suspend fun command(address: ItemAddress, member: String, args: List<DBusValue>): EmptyResult<TrayError> =
        bus.withConnection(::busDown) { connection -> TrayOnConnection(connection).command(address, member, args) }
}

/** The tray as one connection sees it, which the next connection replaces rather than continues. */
private class TrayOnConnection(private val connection: DBusConnection) {
    suspend fun command(address: ItemAddress, member: String, args: List<DBusValue>): EmptyResult<TrayError> =
        onItemInterface(address) { iface ->
            connection.call(address.service, address.path, iface, member, args)
        }.map { }

    /**
     * The tray, followed across the watcher coming, going and being replaced.
     *
     * Applications re-register with whichever watcher holds the name, since they follow it themselves, so
     * all a host has to do is notice the change and register again with the new one.
     */
    fun track(): Flow<Result<List<TrayItem>, TrayError>> = channelFlow {
        RULES.forEach { rule ->
            connection.addMatch(rule).getOrElse {
                send(Err(TrayError.BusFailed(it)))
                return@channelFlow
            }
        }

        // Subscribed before the first look for a watcher, so neither a watcher nor an item that arrives in
        // between is missed. Closed at the connection's end, after its last signal, so every loop below
        // drains what arrived and stops.
        val signals = Channel<Message.Signal>(Channel.UNLIMITED)
        val subscribed = CompletableDeferred<Unit>()
        launch {
            connection.allSignals
                .onSubscription { subscribed.complete(Unit) }
                .transformWhile { received ->
                    if (received is Ok) emit(received.value)
                    received is Ok
                }
                .collect { signal -> signals.send(signal) }
            signals.close()
        }
        subscribed.await()

        while (true) {
            // The bus reports the death itself and the next connection starts over, so this pass just ends.
            if (connection.closed.value != null) return@channelFlow

            val watcher = findWatcher().getOrNull()
            if (watcher == null) {
                send(Err(TrayError.NoWatcher))
                signals.awaitOwnerChange(WATCHERS.map { it.service }.toSet(), appearing = true)
                continue
            }

            connection.post(
                destination = watcher.service,
                path = WATCHER_PATH,
                iface = watcher.iface,
                member = "RegisterStatusNotifierHost",
                args = listOf(DBusValue.Text(connection.uniqueName)),
            )

            var known = readAll(watcher).getOrElse { failure ->
                send(Err(failure))
                // Read again once the watcher changes, rather than giving up on a tray that may recover.
                signals.awaitOwnerChange(setOf(watcher.service), appearing = false)
                continue
            }
            send(Ok(known.asItems()))

            for (signal in signals) {
                // Gone or replaced, either way the next pass finds out which and starts over with it.
                if (signal.ownerChangeOf(setOf(watcher.service)) != null) break
                known = known.after(signal, watcher) ?: continue
                send(Ok(known.asItems()))
            }
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
        signal.iface == Bus.INTERFACE && signal.member == Bus.NAME_OWNER_CHANGED -> afterNameOwnerChanged(signal)
        else -> null
    }

    private suspend fun Known.afterWatcherSignal(signal: Message.Signal): Known? {
        val entry = signal.body.firstOrNull()?.asText ?: return null
        val address = ItemAddress.parse(entry, signal.sender) ?: return null
        return when (signal.member) {
            ITEM_REGISTERED -> tracked(address)?.let { this + (address to it) }
            ITEM_UNREGISTERED -> takeIf { address in it }?.minus(address)
            else -> null
        }
    }

    private suspend fun Known.afterItemSignal(signal: Message.Signal): Known? {
        val address = addressOf(signal) ?: return null
        val held = getValue(address)
        // NewStatus is the one that carries its own value, so it is the one that needs no read back.
        if (signal.member == NEW_STATUS) {
            val status = signal.body.firstOrNull()?.asText ?: return null
            val updated = held.properties + ("Status" to DBusValue.Text(status))
            return this + (address to held.copy(properties = updated))
        }
        if (!signal.member.startsWith("New")) return null
        return readItem(address)?.let { this + (address to held.copy(properties = it)) }
    }

    private fun Known.afterPropertiesChanged(signal: Message.Signal): Known? {
        if (signal.body.firstOrNull()?.asText !in ITEM_INTERFACES) return null
        val address = addressOf(signal) ?: return null

        val changed = signal.body.getOrNull(1)?.asDictionary.orEmpty()
        val invalidated = signal.body.getOrNull(2)?.asItems?.mapNotNull { it.asText }.orEmpty()
        if (changed.isEmpty() && invalidated.isEmpty()) return null

        val held = getValue(address)
        return this + (address to held.copy(properties = held.properties + changed - invalidated.toSet()))
    }

    /** An application that exits takes its items with it, whether or not the watcher noticed. */
    private fun Known.afterNameOwnerChanged(signal: Message.Signal): Known? {
        val name = signal.body.firstOrNull()?.asText ?: return null
        val newOwner = signal.body.getOrNull(2)?.asText ?: return null
        if (newOwner.isNotEmpty()) return null

        // Either spelling: a connection that dies is announced under its unique name, and under every
        // well-known one it held, and an item may be keyed by one and owned by the other.
        val gone = entries.filter { it.key.service == name || it.value.owner == name }.map { it.key }
        return takeIf { gone.isNotEmpty() }?.minus(gone.toSet())
    }

    /**
     * Which item sent [signal], or null where no item did.
     *
     * Matched on the owner rather than on the key's service name, which is the watcher's spelling and may
     * be a well-known name no signal ever carries. This is also what keeps a forged item signal out: the
     * rules for the item interfaces can name no sender, since which connections hold items is not known
     * when they go up, so a peer's own `NewIcon` is routed here and discarded for owning no item.
     */
    private fun Known.addressOf(signal: Message.Signal): ItemAddress? =
        entries.firstOrNull { it.value.owner == signal.sender && it.key.path == signal.path }?.key

    /**
     * Waits for one of [names] to change owner: to gain one where [appearing], to change at all where not.
     *
     * Every other signal is dropped, which is right for both callers: neither holds items worth updating.
     */
    private suspend fun Channel<Message.Signal>.awaitOwnerChange(
        names: Set<String>,
        appearing: Boolean,
    ) {
        for (signal in this) {
            val newOwner = signal.ownerChangeOf(names) ?: continue
            if (!appearing || newOwner.isNotEmpty()) return
        }
    }

    /** The new owner of one of [names] where this signal announces one changing, empty for none, else null. */
    private fun Message.Signal.ownerChangeOf(names: Set<String>): String? {
        if (iface != Bus.INTERFACE || member != Bus.NAME_OWNER_CHANGED) return null
        if (body.firstOrNull()?.asText !in names) return null
        return body.getOrNull(2)?.asText
    }

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
        val known = linkedMapOf<ItemAddress, Tracked>()
        entries.forEach { entry ->
            val address = ItemAddress.parse(entry) ?: return@forEach
            tracked(address)?.let { known[address] = it }
        }
        return Ok(known)
    }

    /**
     * One item's properties, or null where it answered on neither interface.
     *
     * An item that has just gone away is the ordinary reason for null, and it is not an error: the
     * unregistration is on its way and the tray simply carries one fewer item until it lands.
     */
    /** [address] ready to be held, or null where either half of it could not be read. */
    private suspend fun tracked(address: ItemAddress): Tracked? {
        val owner = connection.nameOwner(address.service).getOrNull() ?: return null
        val properties = readItem(address) ?: return null
        return Tracked(owner, properties)
    }

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

    private companion object {
        const val NEW_STATUS = "NewStatus"
        const val PROPERTIES_CHANGED = "PropertiesChanged"

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
            // A sender wherever exactly one is legitimate, because the bus resolves a well-known name to
            // whoever owns it and routes only that connection's signals. Without it any peer on the bus can
            // emit an unregistration, or a name change, and have the tray act on it.
            WATCHERS.mapTo(this) { MatchRule(sender = it.service, iface = it.iface) }
            ITEM_INTERFACES.mapTo(this) { MatchRule(iface = it) }
            add(MatchRule(iface = Bus.PROPERTIES, member = PROPERTIES_CHANGED))
            add(MatchRule(sender = Bus.NAME, iface = Bus.INTERFACE, member = Bus.NAME_OWNER_CHANGED))
        }
    }
}

/** What the tray holds between signals. */
private typealias Known = Map<ItemAddress, Tracked>

/**
 * One item's properties exactly as the bus sent them, beside the unique name of the connection behind it.
 *
 * [owner] is the only thing an item's own signals can be matched against, since the bus stamps every
 * message with the sender's unique name while a watcher may list the item under a well-known one. The key
 * stays the watcher's spelling, because that is what its unregistration will name, by which time the
 * owner is no longer resolvable.
 */
private data class Tracked(val owner: String, val properties: Map<String, DBusValue>)

private fun Known.asItems(): List<TrayItem> = map { (address, tracked) -> trayItemFrom(address, tracked.properties) }

