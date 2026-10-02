package com.fromwau.kortex.bar.desktop

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.getOrNull
import com.fromwau.kern.result.mapError
import com.fromwau.kortex.dbus.DBusConnection
import com.fromwau.kortex.hyprland.Hyprland
import com.fromwau.kortex.notification.CloseReason
import com.fromwau.kortex.notification.NotificationServer
import com.fromwau.kortex.notification.ServerInformation
import com.fromwau.kortex.tray.Tray
import com.fromwau.kortex.tray.TrayRegistry
import com.fromwau.kortex.tray.TrayWatcher
import com.fromwau.kortex.tray.TrayError
import com.fromwau.kortex.tray.TrayItem
import com.fromwau.kortex.bar.BarError
import com.fromwau.kortex.bar.state.FocusedWindow
import com.fromwau.kortex.bar.state.Posted
import com.fromwau.kortex.bar.state.Reading
import com.fromwau.kortex.bar.state.TrayEntry
import com.fromwau.kortex.bar.state.WorkspaceSlot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/** What the desktop's own services report, as the bar's widgets want it. */
interface Desktop {
    /** Every item in the system tray, drawable, or why there are none. */
    val tray: Flow<Reading<List<TrayEntry>>>

    /**
     * Who holds the tray's registry, where this shell does not, and null where it does.
     *
     * A shell that is not the registry still draws the tray, by reading the list of whoever is, so this
     * is a thing to mention rather than a failure. It is also the only explanation available for a tray
     * that empties when another bar exits.
     */
    val trayRegistry: Flow<String?>

    /**
     * Every notification posted to this shell, or why it is not the one notifications are posted to.
     *
     * The failure is the ordinary case on a desktop that already runs a notification daemon, so it is a
     * reading the bar shows rather than something that stops it starting.
     */
    val notifications: Flow<Reading<List<Posted>>>

    /** The workspace strip, from 1 to one past the highest workspace in use. */
    val workspaces: Flow<Reading<List<WorkspaceSlot>>>

    /** The window with keyboard focus, null while nothing has it. */
    val focusedWindow: Flow<Reading<FocusedWindow?>>

    /**
     * Takes the notification with [id] away and tells its application it was dismissed.
     *
     * Nothing is handed back: closing one that has already gone is the one failure a bar can provoke, and
     * the list it was drawn from is the thing that was out of date, which the next value fixes.
     */
    suspend fun dismiss(id: UInt)
}

/**
 * The [Desktop] over one connection to the session bus.
 *
 * One of these belongs to a whole shell rather than to one bar: the bus connection, the tray's match
 * rules and the notification name are all the application's, and a second bar is another collector rather
 * than another connection. [scope] is where all three live, so cancelling it ends them.
 *
 * Nothing connects until something collects. Becoming the notification server is part of that, which
 * means the name is taken when the first collector arrives and not when this is built.
 */
class BusDesktop(
    private val scope: CoroutineScope,
    identity: ServerInformation,
) : Desktop {
    private val connection = scope.async(start = CoroutineStart.LAZY) { DBusConnection.session() }

    /**
     * Asks to be the tray's registry before building the host, so applications have somewhere to register.
     *
     * Order matters only this far: a shell that hosts without ever claiming reads an empty registry on a
     * desktop where nothing else serves one, which is a tray that looks fine and is permanently empty.
     */
    private val registry = scope.async(start = CoroutineStart.LAZY) {
        when (val bus = connection.await()) {
            is Err -> Err(BarError.NoBus(bus.error))
            is Ok -> TrayWatcher.claim(bus.value, scope).mapError(BarError::NoTray)
        }
    }

    private val trayOnBus = scope.async(start = CoroutineStart.LAZY) {
        registry.await()
        when (val bus = connection.await()) {
            is Err -> Err(BarError.NoBus(bus.error))
            is Ok -> Ok(Tray(bus.value, scope))
        }
    }

    private val server = scope.async(start = CoroutineStart.LAZY) {
        when (val bus = connection.await()) {
            is Err -> Err(BarError.NoBus(bus.error))
            is Ok -> NotificationServer.start(bus.value, identity).mapError(BarError::NotServing)
        }
    }

    override val trayRegistry: Flow<String?> = flow {
        val held = registry.await().getOrNull() as? TrayRegistry.HeldElsewhere
        emit(held?.let { it.process ?: it.owner })
    }.flowOn(Dispatchers.IO)

    override val tray: Flow<Reading<List<TrayEntry>>> = flow {
        when (val provider = trayOnBus.await()) {
            is Err -> emit(Reading.Unavailable(provider.error))
            is Ok -> emitAll(provider.value.items.map { outcome -> outcome.drawable() })
        }
    }.flowOn(Dispatchers.IO)

    override val notifications: Flow<Reading<List<Posted>>> = flow {
        when (val serving = server.await()) {
            is Err -> emit(Reading.Unavailable(serving.error))
            is Ok -> emitAll(
                serving.value.notifications.map { posted ->
                    Reading.Value(posted.map { notification -> notification.posted() })
                },
            )
        }
    }.flowOn(Dispatchers.IO)

    // Not on the bus: Hyprland has sockets of its own, and connects only once something collects.
    private val hyprland = Hyprland(scope)

    override val workspaces: Flow<Reading<List<WorkspaceSlot>>> = hyprland.workspaces.map { it.asStrip() }

    override val focusedWindow: Flow<Reading<FocusedWindow?>> = hyprland.activeWindow.map { it.asFocused() }

    override suspend fun dismiss(id: UInt) {
        server.await().getOrNull()?.close(id, CloseReason.Dismissed)
    }

    /**
     * One reading of the tray, with every item's icon resolved as far as [icons] can take it.
     *
     * `TrayError.NotConnected` becomes [Reading.Pending] rather than a failure, because that is what its
     * own KDoc says it means: nobody is watching yet or the first read has not come back. Every other
     * case is a reading the bar should say out loud.
     */
    private fun Result<List<TrayItem>, TrayError>.drawable(): Reading<List<TrayEntry>> =
        when (this) {
            is Ok -> Reading.Value(value.map { item -> item.asEntry() })
            is Err -> when (error) {
                TrayError.NotConnected -> Reading.Pending
                else -> Reading.Unavailable(BarError.NoTray(error))
            }
        }
}
