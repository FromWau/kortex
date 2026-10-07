package com.fromwau.kortex.tray

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.getOrElse
import com.fromwau.kern.result.getOrNull
import com.fromwau.kern.result.map
import com.fromwau.kern.result.mapError
import com.fromwau.kortex.dbus.Bus
import com.fromwau.kortex.dbus.CallRejected
import com.fromwau.kortex.dbus.DBusConnection
import com.fromwau.kortex.dbus.DBusType
import com.fromwau.kortex.dbus.DBusValue
import com.fromwau.kortex.dbus.MatchRule
import com.fromwau.kortex.dbus.Message
import com.fromwau.kortex.dbus.NameRequest
import com.fromwau.kortex.dbus.SessionBus
import com.fromwau.kortex.dbus.asText
import com.fromwau.kortex.dbus.asUInt32
import com.fromwau.kortex.dbus.nameOwnerChange
import kotlinx.coroutines.CoroutineScope
import com.fromwau.kern.result.flatMap
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * The tray's registry, for a shell that is the only bar on the desktop.
 *
 * An application with a tray icon cannot be found. It holds its own icon and menu at a connection name
 * the bus assigned it, something like `:1.1392`, which nothing can guess and which changes every time it
 * restarts. So it does not wait to be found: it calls a well-known name and says where it is. A watcher
 * is whoever holds that name and keeps the list, and without one an application has nowhere to say it,
 * so a desktop with no watcher has no tray at all.
 *
 * Only one connection may hold the name, so being the watcher is a role exactly one process on a desktop
 * can play. [claim] and [serve] say which of the two a shell ended up as, and neither is a failure: one
 * that did not get the name still draws the tray by reading the registry of whoever did.
 *
 * ```kotlin
 * val registry = TrayWatcher.serve(bus, scope)
 * val tray = Tray(bus, scope)
 * ```
 *
 * Starting this is not a passive act. Applications already running notice the name appear and register
 * themselves again, with nothing restarted, so a watcher started after them adopts the tray that is
 * already there rather than only the applications that come later.
 *
 * It never takes the name from a process that holds it: taking it out from under another bar is the worse
 * failure, and a desktop running two bars is the owner's business rather than this module's. [serve] does
 * take it once that process has let it go, so a tray outlives the bar that was serving it.
 */
/**
 * Who holds the tray's registry, once a shell has asked for it.
 *
 * Both of these are outcomes a shell can live with, which is why neither is an error: only one process on
 * a desktop can be the registry, and a shell that is not it still draws the tray by reading the one that
 * is. A bus that failed is the only thing [TrayWatcher.claim] reports as a failure.
 */
public sealed interface TrayRegistry {
    /** This connection holds it, and [watcher] is the registry itself. */
    public data class HeldHere(public val watcher: TrayWatcher) : TrayRegistry

    /**
     * Another process holds it, so [Tray] reads from theirs and this shell serves nothing.
     *
     * [process] is what turns "the name is taken" into "ags is already the watcher", which is two more
     * round trips and the difference between something a person can act on and a riddle.
     */
    public data class HeldElsewhere(
        public val owner: String,
        public val pid: Int?,
        public val process: String?,
    ) : TrayRegistry
}

public class TrayWatcher private constructor(private val connection: DBusConnection) {
    private val registry = MutableStateFlow<Set<ItemAddress>>(emptySet())

    @Volatile
    private var stopped = false

    /**
     * Every item that has registered here, which is the list a host reads over the bus.
     *
     * The same answer `RegisteredStatusNotifierItems` gives, without the round trip, for a shell that is
     * both the watcher and the one drawing.
     */
    public val registered: StateFlow<Set<ItemAddress>> get() = registry.asStateFlow()

    /** Gives the name back and stops answering, so another bar can take the role. */
    public suspend fun stop(): EmptyResult<TrayError> {
        stopped = true
        // The name goes first, so a host never reaches the name with nothing exported behind it.
        val released = connection.releaseName(KDE_WATCHER.service).mapError(TrayError::BusFailed)
        connection.unexport(WATCHER_PATH)
        return released
    }

    private suspend fun handle(call: Message.Call): Result<List<DBusValue>, CallRejected> = when {
        call.iface == KDE_WATCHER.iface && call.member == REGISTER_ITEM -> registerItem(call)

        call.iface == KDE_WATCHER.iface && call.member == REGISTER_HOST -> {
            connection.emit(WATCHER_PATH, KDE_WATCHER.iface, HOST_REGISTERED)
            Ok(emptyList())
        }

        call.iface == Bus.PROPERTIES && call.member == "Get" ->
            property(call.body.getOrNull(1)?.asText)
                ?.let { value -> Ok(listOf(value)) }
                ?: Err(CallRejected.unknownMethod(call))

        call.iface == Bus.PROPERTIES && call.member == "GetAll" -> Ok(listOf(everything()))

        else -> Err(CallRejected.unknownMethod(call))
    }

    /**
     * Takes one registration, whichever of the three shapes it arrives in.
     *
     * The specification has this take a bus name, and applications disagree: some send a name, some a
     * name and a path, and some only a path. [ItemAddress.parse] is given the sender because that is the
     * only thing that resolves the last one.
     */
    private suspend fun registerItem(call: Message.Call): Result<List<DBusValue>, CallRejected> {
        val argument = call.body.firstOrNull()?.asText
        val address = argument
            ?.let { entry -> ItemAddress.parse(entry, call.sender) }
            ?: return Err(CallRejected(CallRejected.INVALID_ARGS, "$REGISTER_ITEM wants a service or a path"))

        val before = registry.getAndUpdate { items -> items + address }
        // An application that registers twice is one item, not two, and a host told twice about the same
        // address would read its properties again for nothing.
        if (address !in before) announce(ITEM_REGISTERED, address)

        return Ok(emptyList())
    }

    /**
     * Drops every item an application held when its connection goes away.
     *
     * An application that exits does not unregister first, and the registry would otherwise keep handing
     * a host an address nobody answers at, which the host can only discover by failing to read it.
     */
    private suspend fun forget(service: String) {
        val before = registry.getAndUpdate { items -> items.filterNot { it.service == service }.toSet() }

        before.filter { it.service == service }.forEach { address -> announce(ITEM_UNREGISTERED, address) }
    }

    private suspend fun announce(signal: String, address: ItemAddress) {
        connection.emit(WATCHER_PATH, KDE_WATCHER.iface, signal, listOf(DBusValue.Text(address.toString())))
    }

    /**
     * Watches the bus for connections going away, which is the only notice an item's departure gets.
     *
     * A name given up with no new owner is a connection that has gone, and every item it registered goes
     * with it.
     */
    private suspend fun watchForDepartures() {
        // Ends with the connection: the registry it keeps is gone with it.
        connection.allSignals.transformWhile { received ->
            if (received is Ok) emit(received.value)
            received is Ok
        }.collect { signal ->
            val change = signal.nameOwnerChange ?: return@collect
            if (change.newOwner == null) forget(change.name)
        }
    }

    /**
     * The three properties an application reads before deciding a tray icon is worth exporting.
     *
     * [HOST_IS_REGISTERED] answers true from the moment the watcher is up, rather than tracking whether a
     * host has registered yet. The two wrong answers are not equally wrong: a true answer with nobody
     * drawing costs an application one export nobody looks at, while a false answer makes it skip the
     * tray for the rest of its life, and there is a real window between taking the name and a shell's own
     * host registering in which the honest answer would be false.
     */
    private fun property(name: String?): DBusValue? = when (name) {
        REGISTERED_ITEMS -> DBusValue.Variant(entries())
        HOST_IS_REGISTERED -> DBusValue.Variant(DBusValue.Bool(true))
        PROTOCOL_VERSION -> DBusValue.Variant(DBusValue.I32(0))
        else -> null
    }

    private fun everything(): DBusValue = DBusValue.Sequence(
        DBusType.Pair(DBusType.Basic.Text, DBusType.Variant),
        listOf(
            DBusValue.Pair(DBusValue.Text(REGISTERED_ITEMS), DBusValue.Variant(entries())),
            DBusValue.Pair(DBusValue.Text(HOST_IS_REGISTERED), DBusValue.Variant(DBusValue.Bool(true))),
            DBusValue.Pair(DBusValue.Text(PROTOCOL_VERSION), DBusValue.Variant(DBusValue.I32(0))),
        ),
    )

    /** The registry as the wire carries it, which is how [ItemAddress] spells itself. */
    private fun entries(): DBusValue = DBusValue.Sequence(
        DBusType.Basic.Text,
        registry.value.map { address -> DBusValue.Text(address.toString()) },
    )

    public companion object {
        /**
         * Asks to be the desktop's tray registry, and says who holds it either way.
         *
         * Both answers are a [TrayRegistry] rather than one of them being an error, because a shell that
         * did not get the name still draws the tray: it reads the list from whoever did. Only a bus that
         * failed is a failure here.
         *
         * It does not queue. `RequestName` goes out asking not to be, because a shell that waits in line
         * for a name it will get when another bar exits is a shell whose tray starts working at a moment
         * nobody chose.
         *
         * ```kotlin
         * when (val registry = TrayWatcher.claim(connection, scope).getOrElse { return }) {
         *     is TrayRegistry.HeldHere -> Unit
         *     is TrayRegistry.HeldElsewhere -> hint("tray registry: ${registry.process ?: registry.owner}")
         * }
         * ```
         *
         * [scope] is where the one subscription this keeps runs, watching for applications that go away
         * without unregistering. Cancelling it stops the registry noticing them; it does not give the
         * name back, which [stop] does.
         */
        public suspend fun claim(
            connection: DBusConnection,
            scope: CoroutineScope,
        ): Result<TrayRegistry, TrayError> {
            // Exported before the name is taken, so a host never reaches the name with nothing behind it.
            val watcher = TrayWatcher(connection)
            connection.export(WATCHER_PATH, introspection = INTROSPECTION) { call -> watcher.handle(call) }

            val requested = connection
                .requestName(KDE_WATCHER.service)
                .mapError(TrayError::BusFailed)
                .getOrElse { failure ->
                    connection.unexport(WATCHER_PATH)
                    return Err(failure)
                }
            when (requested) {
                NameRequest.Held, NameRequest.AlreadyHeld -> Unit

                NameRequest.Taken, NameRequest.Unknown -> {
                    connection.unexport(WATCHER_PATH)
                    return Ok(whoHasIt(connection))
                }
            }

            connection
                .addMatch(MatchRule(sender = Bus.NAME, iface = Bus.INTERFACE, member = Bus.NAME_OWNER_CHANGED))
                .mapError(TrayError::BusFailed)
                .getOrElse { return Err(it) }

            scope.launch { watcher.watchForDepartures() }

            return Ok(TrayRegistry.HeldHere(watcher))
        }

        /**
         * Serves the registry on every connection [bus] comes up on: at once where the name is free, and
         * otherwise as soon as its holder lets it go.
         *
         * While another process holds the name the value is that process. When that process lets the name
         * go, by exiting or by giving it up, this claims it and turns [TrayRegistry.HeldHere]. Another shell
         * that gets there first is reported as the new holder and waited out in turn. Once this holds the
         * name it stops watching for it, so [stop] gives it back for good on that connection; a connection
         * after a bus restart claims it afresh, since the name went with the old one.
         *
         * ```kotlin
         * TrayWatcher.serve(bus, scope).collect { registry ->
         *     val elsewhere = registry.getOrNull() as? TrayRegistry.HeldElsewhere
         *     hint(elsewhere?.let { "tray registry: ${it.process ?: it.owner}" })
         * }
         * ```
         *
         * Nothing is claimed until something collects the flow. Once claimed, the registry lives as long as
         * its connection and [scope], however often collectors come and go, so the items registered with it
         * stay: an application registers again only when the name changes hands, and here it never did.
         */
        public fun serve(
            bus: SessionBus,
            scope: CoroutineScope,
        ): StateFlow<Result<TrayRegistry, TrayError>> {
            val kept = MutableStateFlow<TrayWatcher?>(null)
            return bus
                .following(::unavailable) { connection -> servingOn(connection, scope, kept) }
                .stateIn(scope, SharingStarted.WhileSubscribed(), Err(TrayError.NotConnected))
        }

        private fun servingOn(
            connection: DBusConnection,
            scope: CoroutineScope,
            kept: MutableStateFlow<TrayWatcher?>,
        ): Flow<Result<TrayRegistry, TrayError>> = channelFlow {
            // Claiming again would answer AlreadyHeld and export a new, empty registry over the one in use.
            val held = kept.value?.takeIf { it.connection == connection && !it.stopped }
            if (held != null) {
                send(Ok(TrayRegistry.HeldHere(held)))
                awaitCancellation()
            }

            val claimed = connection.chancesToClaim(KDE_WATCHER.service)
                .map { chance -> chance.mapError(TrayError::BusFailed).flatMap { claim(connection, scope) } }
                .onEach { send(it) }
                .firstOrNull { it.getOrNull() is TrayRegistry.HeldHere }
                ?: return@channelFlow
            kept.value = (claimed.getOrNull() as TrayRegistry.HeldHere).watcher
            awaitCancellation()
        }

        /** Turns "the name is taken" into "ags is already the watcher". */
        private suspend fun whoHasIt(connection: DBusConnection): TrayRegistry.HeldElsewhere {
            val owner = connection.nameOwner(KDE_WATCHER.service).getOrNull().orEmpty()
            val pid = connection
                .call(
                    destination = Bus.NAME,
                    path = Bus.PATH,
                    iface = Bus.INTERFACE,
                    member = "GetConnectionUnixProcessID",
                    args = listOf(DBusValue.Text(KDE_WATCHER.service)),
                )
                .getOrNull()
                ?.firstOrNull()
                ?.asUInt32
                ?.toInt()

            return TrayRegistry.HeldElsewhere(owner, pid, pid?.let(::processName))
        }

        /** What `/proc/<pid>/comm` calls it, which is the whole point of having looked up the pid. */
        private fun processName(pid: Int): String? = try {
            Files.readString(Path.of("/proc/$pid/comm")).trim().ifEmpty { null }
        } catch (_: IOException) {
            null
        }

        private val INTROSPECTION = """
            <node>
              <interface name="${KDE_WATCHER.iface}">
                <method name="$REGISTER_ITEM">
                  <arg direction="in" type="s" name="service"/>
                </method>
                <method name="$REGISTER_HOST">
                  <arg direction="in" type="s" name="service"/>
                </method>
                <property name="$REGISTERED_ITEMS" type="as" access="read"/>
                <property name="$HOST_IS_REGISTERED" type="b" access="read"/>
                <property name="$PROTOCOL_VERSION" type="i" access="read"/>
                <signal name="$ITEM_REGISTERED">
                  <arg type="s" name="service"/>
                </signal>
                <signal name="$ITEM_UNREGISTERED">
                  <arg type="s" name="service"/>
                </signal>
                <signal name="$HOST_REGISTERED"/>
              </interface>
            </node>
        """.trimIndent()
    }
}
