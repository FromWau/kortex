package com.fromwau.kortex.tray

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.getOrElse
import com.fromwau.kern.result.map
import com.fromwau.kern.result.mapError
import com.fromwau.kortex.dbus.BusState
import com.fromwau.kortex.dbus.DBusConnection
import com.fromwau.kortex.dbus.DBusType
import com.fromwau.kortex.dbus.DBusValue
import com.fromwau.kortex.dbus.MatchRule
import com.fromwau.kortex.dbus.SessionBus
import com.fromwau.kortex.dbus.asBoolean
import com.fromwau.kortex.dbus.asInt32
import com.fromwau.kortex.dbus.watching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlin.time.Clock

/**
 * The menu an application exports beside its tray item, as data.
 *
 * One entry tree rather than a widget: a caller draws it, decides what a disposition looks like and what a
 * mnemonic does, and tells the application what happened through [send].
 *
 * Nothing runs while nobody collects [layout]. The rule goes up on the first collector and comes down with
 * the last, and an application that builds its menu lazily is not asked to build one at all until then. It
 * follows the bus across restarts, and says why while there is no connection to read on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
public class Menu internal constructor(
    private val bus: SessionBus,
    private val service: String,
    private val path: String,
    scope: CoroutineScope,
) {
    /** Everything this menu's object sends; both flows below put it up and take it down for themselves. */
    private val rule = MatchRule(sender = service, iface = INTERFACE, path = path)

    /** The root entry and everything under it; a caller usually draws [MenuItem.children] of the root. */
    public val layout: StateFlow<Result<MenuItem, TrayError>> = bus
        .following(::unavailable) { connection -> track(connection) }
        .stateIn(scope, SharingStarted.WhileSubscribed(), Err(TrayError.NotConnected))

    /**
     * Entries the application has asked to be activated itself, by id.
     *
     * An application sends this when something other than the pointer should open an entry, a keyboard
     * shortcut it handles being the usual reason. A caller that ignores it loses only that.
     */
    public val activationRequests: Flow<Int> = bus.state.flatMapLatest { state ->
        when (state) {
            is BusState.Up -> activationRequestsOn(state.connection)
            else -> emptyFlow()
        }
    }

    private fun activationRequestsOn(connection: DBusConnection): Flow<Int> = flow {
        // Its own rule rather than leaning on the one [layout] puts up, or this would deliver nothing at
        // all unless somebody happened to be collecting that too. The bus counts duplicate rules, so the
        // two adding the same one and each removing it once is what it expects.
        connection.addMatch(rule).getOrElse { return@flow }
        emitAll(
            connection.signals(rule)
                .mapNotNull { received -> (received as? Ok)?.value }
                .filter { it.member == ACTIVATION_REQUESTED }
                .mapNotNull { signal -> signal.body.firstOrNull()?.asInt32 },
        )
    }.onCompletion { withContext(NonCancellable) { connection.removeMatch(rule) } }

    /**
     * Tells the application what the user did to the entry with [id].
     *
     * Sent without waiting, because the interface marks `Event` as expecting no reply.
     *
     * @param timestamp when it happened. The default is now, which is what the interface asks for when the
     *   caller has no event time of its own to give.
     */
    public suspend fun send(
        id: Int,
        event: MenuEvent,
        timestamp: UInt = Clock.System.now().epochSeconds.toUInt(),
    ): EmptyResult<TrayError> = bus.withConnection(::busDown) { connection ->
        connection.post(
            destination = service,
            path = path,
            iface = INTERFACE,
            member = "Event",
            args = listOf(
                DBusValue.I32(id),
                DBusValue.Text(event.wireName),
                // The interface defines no data for any of the four events, and every host sends this.
                DBusValue.Variant(DBusValue.Text("")),
                DBusValue.U32(timestamp),
            ),
        ).mapError(TrayError::BusFailed)
    }

    /**
     * Tells the application a submenu is about to be shown, so it can fill one it built lazily.
     *
     * @return true where the application changed the menu and the caller should wait for [layout] to carry
     *   the new entries before drawing.
     */
    public suspend fun aboutToShow(id: Int): Result<Boolean, TrayError> = bus.withConnection(::busDown) { connection ->
        connection
            .call(service, path, INTERFACE, "AboutToShow", listOf(DBusValue.I32(id)))
            .mapError(TrayError::BusFailed)
            .map { body -> body.firstOrNull()?.asBoolean == true }
    }

    private fun track(connection: DBusConnection): Flow<Result<MenuItem, TrayError>> =
        connection.watching(listOf(rule), ruleFailed = { send(Err(TrayError.BusFailed(it))) }) { signals ->
            // Qualified because this class's own send is what a caller uses to report a click.
            this@watching.send(readLayout(connection))
            for (signal in signals) {
                if (rule.matches(signal) && signal.member in CHANGED) this@watching.send(readLayout(connection))
            }
        }

    /**
     * The whole tree, every time anything about it changes.
     *
     * `ItemsPropertiesUpdated` carries the entries that changed, and merging them would mean finding nodes
     * by id and rebuilding every ancestor. A menu is a handful of entries on one object, so one more round
     * trip costs less than the bookkeeping and cannot disagree with itself.
     */
    private suspend fun readLayout(connection: DBusConnection): Result<MenuItem, TrayError> {
        val body = connection
            .call(
                destination = service,
                path = path,
                iface = INTERFACE,
                member = "GetLayout",
                // From the root, every level, every property: a bar draws the whole menu at once.
                args = listOf(
                    DBusValue.I32(ROOT),
                    DBusValue.I32(EVERY_LEVEL),
                    DBusValue.Sequence(TEXT, emptyList()),
                ),
            )
            .getOrElse { return Err(TrayError.BusFailed(it)) }

        // The revision beside it matters only to a client that merges changes into a tree it already has.
        val root = body.getOrNull(1)?.let(::menuItemFrom) ?: return Err(TrayError.MenuUnreadable)
        return Ok(root)
    }

    private companion object {
        const val INTERFACE = "com.canonical.dbusmenu"
        const val LAYOUT_UPDATED = "LayoutUpdated"
        const val PROPERTIES_UPDATED = "ItemsPropertiesUpdated"
        const val ACTIVATION_REQUESTED = "ItemActivationRequested"

        /** The two that mean the tree a caller is drawing is no longer what the application has. */
        val CHANGED = setOf(LAYOUT_UPDATED, PROPERTIES_UPDATED)

        /** The entry every other one hangs off; it is never drawn itself. */
        const val ROOT = 0

        /** What the interface takes for "do not stop descending". */
        const val EVERY_LEVEL = -1

        val TEXT = DBusType.Basic.Text
    }
}
