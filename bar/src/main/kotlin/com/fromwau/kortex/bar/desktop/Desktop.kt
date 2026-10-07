package com.fromwau.kortex.bar.desktop

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.getOrNull
import com.fromwau.kortex.dbus.SessionBus
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
import com.fromwau.kortex.bar.state.WorkspaceStrip
import kotlinx.coroutines.CoroutineScope
import com.fromwau.kortex.bar.state.BatteryEntry
import com.fromwau.kortex.upower.Upower
import com.fromwau.kortex.powerprofiles.PowerProfile
import com.fromwau.kortex.powerprofiles.PowerProfiles
import com.fromwau.kortex.bar.state.ProfileEntry
import com.fromwau.kortex.dbus.SystemBus
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.ExperimentalCoroutinesApi
import com.fromwau.kortex.bar.state.NowPlaying
import com.fromwau.kortex.mpris.Mpris
import com.fromwau.kern.result.map
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
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

    /** The machine's battery and every peripheral's, empty on a machine with none. */
    val batteries: Flow<Reading<List<BatteryEntry>>>

    /** The machine's power profile, and null where nothing offers profiles. */
    val powerProfile: Flow<Reading<ProfileEntry?>>

    /** The player the bar shows, with its position moving while it plays, and null while none has a track. */
    val media: Flow<Reading<NowPlaying?>>

    /** The workspace strip for the monitor connected at [connector], as its own bar draws it. */
    fun workspacesOn(connector: String): Flow<Reading<WorkspaceStrip>>

    /** The window with keyboard focus, null while nothing has it. */
    val focusedWindow: Flow<Reading<FocusedWindow?>>

    /** The keybind submap in force, null in the default one. */
    val submap: Flow<Reading<String?>>

    /** The main keyboard's layout, short where Hyprland says which, null while no keyboard is main. */
    val keyboardLayout: Flow<Reading<String?>>

    /**
     * Takes the notification with [id] away and tells its application [reason]: dismissed for a click,
     * expired for one that stayed up as long as it should.
     *
     * Nothing is handed back: closing one that has already gone is the one failure a bar can provoke, and
     * the list it was drawn from is the thing that was out of date, which the next value fixes.
     */
    suspend fun close(
        id: UInt,
        reason: CloseReason,
    )

    /**
     * Switches to the workspace numbered [id].
     *
     * Nothing is handed back, for the reason [close] gives: the strip shows the switch once it has
     * happened, and a switch that failed leaves the strip as it was, which is the honest picture.
     */
    suspend fun focusWorkspace(id: Int)

    /**
     * Plays the player at the bus name [player] if it is paused, and pauses it if it is playing.
     *
     * Nothing is handed back, for the reason [close] gives: [media] shows whether it took.
     */
    suspend fun playPause(player: String)

    /**
     * Switches the machine to [profile].
     *
     * Nothing is handed back, for the reason [close] gives: [powerProfile] shows whether it took.
     */
    suspend fun chooseProfile(profile: PowerProfile)
}

/**
 * The [Desktop] over the session bus, followed across restarts.
 *
 * One of these belongs to a whole shell rather than to one bar: the bus connection, the tray's match
 * rules and the notification name are all the application's, and a second bar is another collector rather
 * than another connection. [scope] is where all three live, so cancelling it ends them.
 *
 * Nothing connects until something collects. Becoming the notification server is part of that, which
 * means the name is taken when the first collector arrives and not when this is built.
 */
class BusDesktop(
    scope: CoroutineScope,
    identity: ServerInformation,
    bus: SessionBus = SessionBus(scope),
    systemBus: SystemBus = SystemBus(scope),
) : Desktop {

    /** Serves the tray's registry, and takes it over whenever another shell that held it lets it go. */
    private val registry = TrayWatcher.serve(bus, scope)

    private val trayItems = Tray(bus, scope).items

    private val server = NotificationServer(bus, identity, scope)

    private val mpris = Mpris(bus, scope)

    private val upower = Upower(systemBus, scope)

    private val profiles = PowerProfiles(systemBus, scope)

    override val powerProfile: Flow<Reading<ProfileEntry?>> = profiles.state.map { outcome ->
        when (outcome) {
            is Ok -> Reading.Value(outcome.value.entry())
            is Err -> outcome.error.entry()
        }
    }

    override val batteries: Flow<Reading<List<BatteryEntry>>> = upower.power.map { outcome ->
        when (outcome) {
            is Ok -> Reading.Value(outcome.value.batteries())
            is Err -> outcome.error.batteries()
        }
    }

    override val trayRegistry: Flow<String?> = registry.map { held ->
        (held.getOrNull() as? TrayRegistry.HeldElsewhere)?.let { it.process ?: it.owner }
    }

    // The registry is collected too, so a bar drawing the tray serves one wherever nothing else does.
    override val tray: Flow<Reading<List<TrayEntry>>> =
        combine(registry, trayItems) { _, outcome -> outcome.drawable() }

    override val notifications: Flow<Reading<List<Posted>>> = server.notifications.map { outcome -> outcome.readable() }

    @OptIn(ExperimentalCoroutinesApi::class)
    override val media: Flow<Reading<NowPlaying?>> = mpris.players
        .map { outcome -> outcome.map { players -> players.chosen() } }
        .distinctUntilChanged()
        .flatMapLatest { outcome ->
            when (outcome) {
                is Err -> flowOf(outcome.error.reading())
                is Ok -> when (val player = outcome.value) {
                    null -> flowOf(Reading.Value(null))
                    else -> mpris.position(player).map { position -> Reading.Value(player.nowPlaying(position)) }
                }
            }
        }

    // Not on the bus: Hyprland has sockets of its own, and connects only once something collects.
    private val hyprland = Hyprland(scope)

    override fun workspacesOn(connector: String): Flow<Reading<WorkspaceStrip>> =
        hyprland.monitors.map { it.asStrip(connector) }

    override val focusedWindow: Flow<Reading<FocusedWindow?>> = hyprland.activeWindow.map { it.asFocused() }

    override val submap: Flow<Reading<String?>> = hyprland.submap.map { it.asSubmap() }

    override val keyboardLayout: Flow<Reading<String?>> = hyprland.keyboardLayout.map { it.asLayout() }

    override suspend fun playPause(player: String) {
        mpris.players.value.getOrNull()?.firstOrNull { it.busName == player }?.let { mpris.playPause(it) }
    }

    override suspend fun chooseProfile(profile: PowerProfile) {
        profiles.choose(profile)
    }

    override suspend fun focusWorkspace(id: Int) {
        hyprland.focusWorkspace(id)
    }

    override suspend fun close(
        id: UInt,
        reason: CloseReason,
    ) {
        server.close(id, reason)
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
