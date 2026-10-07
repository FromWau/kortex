package com.fromwau.kortex.bar.state

import com.fromwau.kortex.tray.ItemAddress
import com.fromwau.kortex.bar.desktop.Desktop
import com.fromwau.kortex.bar.system.MemoryUse
import com.fromwau.kortex.bar.system.NetworkRate
import com.fromwau.kortex.bar.system.SystemMetrics
import com.fromwau.kortex.bar.system.Temperature
import java.time.Duration
import java.time.LocalDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes

/**
 * One bar's state: every source combined into one [BarState], and every click answered by [onAction].
 *
 * One of these belongs to one bar. [scope] is where every source is collected, so it should not be the
 * thread that draws; cancelling it stops every source and the state stops updating. [desktop] outlives it,
 * because the bus connection behind it belongs to the whole shell rather than to one bar.
 *
 * [now] is where the focus timer reads the clock when a click starts or resumes it. The ticking readout
 * comes from [clock] instead, so the timer counts down with the same tick the clock shows and a test can
 * drive both.
 */
class BarStateHolder(
    private val scope: CoroutineScope,
    metrics: SystemMetrics,
    private val desktop: Desktop,
    /** The connector of the monitor this bar is on, whose workspaces its strip shows. */
    monitor: String,
    clock: Flow<LocalDateTime>,
    private val session: kotlin.time.Duration = DEFAULT_SESSION,
    private val now: () -> LocalDateTime = LocalDateTime::now,
) {
    private val own = MutableStateFlow(OwnState())

    /** The open tray menu with its entries, read from its application for as long as it stays open. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val trayMenu: Flow<OpenTrayMenu?> = own
        .map { mine -> mine.trayMenu }
        .distinctUntilChanged()
        .flatMapLatest { at ->
            when (at) {
                null -> flowOf(null)
                else -> desktop.trayMenu(at.address).pendingFirst().map { entries ->
                    OpenTrayMenu(at.address, at.x, entries)
                }
            }
        }

    val state: StateFlow<BarState> = combine(
        clock.map<LocalDateTime, Reading<LocalDateTime>> { time -> Reading.Value(time) }
            .onStart { emit(Reading.Pending) },
        own,
        combine(
            metrics.cpuLoad.readings(),
            metrics.memory.readings(),
            metrics.network.readings(),
            metrics.cpuTemperature.readings(),
            combine(
                desktop.batteries.pendingFirst(),
                desktop.powerProfile.pendingFirst(),
            ) { batteries, profile -> Power(batteries, profile) },
        ) { cpu, memory, network, temperature, power -> Machine(cpu, memory, network, temperature, power) },
        combine(
            combine(
                desktop.tray.pendingFirst(),
                trayMenu,
            ) { tray, menu -> TrayReadings(tray, menu) },
            desktop.notifications.pendingFirst(),
            desktop.workspacesOn(monitor).pendingFirst(),
            desktop.focusedWindow.pendingFirst(),
            desktop.media.pendingFirst(),
        ) { tray, notifications, workspaces, window, media ->
            Services(tray, notifications, workspaces, window, media)
        },
        combine(
            desktop.submap.pendingFirst(),
            desktop.keyboardLayout.pendingFirst(),
        ) { submap, layout -> Keyboard(submap, layout) },
    ) { time, mine, machine, services, keyboard ->
        BarState(
            clock = time,
            showDetail = mine.showDetail,
            cpuLoad = machine.cpu,
            memory = machine.memory,
            network = machine.network,
            temperature = machine.temperature,
            batteries = machine.power.batteries,
            powerProfile = machine.power.profile,
            timer = mine.timer.face(at = (time as? Reading.Value)?.value),
            tray = services.tray.items,
            trayMenu = services.tray.menu,
            notifications = services.notifications,
            workspaces = services.workspaces,
            media = services.media,
            focusedWindow = services.window,
            submap = keyboard.submap,
            keyboardLayout = keyboard.layout,
            scheme = mine.scheme,
            trayRegistry = mine.trayRegistry,
        )
    }.stateIn(scope, SharingStarted.Eagerly, BarState.Pending)

    init {
        // Into the bar's own state rather than another arm of the combines, as a fact about the shell.
        scope.launch {
            desktop.trayRegistry.collect { holder -> own.update { mine -> mine.copy(trayRegistry = holder) } }
        }
    }

    fun onAction(action: BarAction) {
        when (action) {
            BarAction.ClockClicked -> own.update { mine -> mine.copy(showDetail = !mine.showDetail) }
            BarAction.TimerClicked -> own.update { mine -> mine.copy(timer = mine.timer.clicked()) }
            BarAction.TimerReset -> own.update { mine -> mine.copy(timer = Session.NotStarted) }
            is BarAction.TrayClicked -> trayClicked(action)
            is BarAction.TrayMenuEntryPicked -> {
                val open = own.value.trayMenu ?: return
                own.update { mine -> mine.copy(trayMenu = null) }
                scope.launch { desktop.tellTrayItem(open.address, TrayCommand.MenuEntryClicked(action.id)) }
            }

            BarAction.TrayMenuDismissed -> own.update { mine -> mine.copy(trayMenu = null) }
            is BarAction.NotificationClosed -> scope.launch { desktop.close(action.id, action.reason) }
            is BarAction.WorkspaceClicked -> scope.launch { desktop.focusWorkspace(action.id) }
            is BarAction.MediaClicked -> scope.launch { desktop.playPause(action.player) }
            is BarAction.ProfileClicked -> scope.launch { desktop.chooseProfile(action.next) }
            BarAction.SchemeCycled -> own.update { mine -> mine.copy(scheme = mine.scheme.next()) }
        }
    }

    /**
     * What a click on a tray item means: a menu the bar draws where there is one to draw, and otherwise the
     * application told, which then shows its window or a menu of its own.
     */
    private fun trayClicked(click: BarAction.TrayClicked) {
        val item = (state.value.tray as? Reading.Value)
            ?.value
            ?.firstOrNull { it.address == click.address }
            ?: return
        val wantsMenu = click.button == TrayButton.Right || (click.button == TrayButton.Left && item.isMenu)

        when {
            wantsMenu && item.hasMenu -> own.update { mine -> mine.copy(trayMenu = MenuAt(item.address, click.x)) }

            else -> {
                val command = when {
                    wantsMenu -> TrayCommand.ShowOwnMenu
                    click.button == TrayButton.Middle -> TrayCommand.SecondaryActivate
                    else -> TrayCommand.Activate
                }
                scope.launch { desktop.tellTrayItem(item.address, command) }
            }
        }
    }

    private fun Session.clicked(): Session = when (this) {
        Session.NotStarted -> Session.Until(now().plusSeconds(session.inWholeSeconds))
        is Session.Until -> when (val left = secondsLeft(now())) {
            // Clicking the moment it elapses is the click that clears it, not one that pauses nothing.
            0L -> Session.NotStarted
            else -> Session.Held(left)
        }

        is Session.Held -> Session.Until(now().plusSeconds(secondsLeft))
    }

    /**
     * What the bar shows for this session, counted against [at].
     *
     * A running session has no readout until the clock has ticked once, because the countdown is drawn
     * from the tick rather than from a second clock of its own.
     */
    private fun Session.face(at: LocalDateTime?): TimerFace = when (this) {
        Session.NotStarted -> TimerFace.Idle
        is Session.Held -> TimerFace.Paused(secondsLeft)
        is Session.Until -> when (at) {
            null -> TimerFace.Counting(session.inWholeSeconds)
            else -> when (val left = secondsLeft(at)) {
                0L -> TimerFace.Elapsed
                else -> TimerFace.Counting(left)
            }
        }
    }

    /** The focus timer, as the holder keeps it: an end, a held remainder, or nothing. */
    private sealed interface Session {
        data object NotStarted : Session

        /** Running, and over at [endsAt]. */
        data class Until(val endsAt: LocalDateTime) : Session {
            fun secondsLeft(at: LocalDateTime): Long = Duration.between(at, endsAt).seconds.coerceAtLeast(0L)
        }

        /** Paused with [secondsLeft] to go. */
        data class Held(val secondsLeft: Long) : Session
    }

    /** The bar's own state, which no source outside it reports. */
    private data class OwnState(
        val showDetail: Boolean = false,
        val timer: Session = Session.NotStarted,
        val scheme: BarScheme = BarScheme.Starting,
        val trayRegistry: String? = null,
        val trayMenu: MenuAt? = null,
    )

    /** Which tray item's menu is open, and where, before its entries have been read. */
    private data class MenuAt(
        val address: ItemAddress,
        val x: Int,
    )

    /** The tray and its open menu, combined for the same reason as [Machine]. */
    private data class TrayReadings(
        val items: Reading<List<TrayEntry>>,
        val menu: OpenTrayMenu?,
    )

    /** The machine readings, combined so the whole bar fits one typed `combine`. */
    private data class Machine(
        val cpu: Reading<Float>,
        val memory: Reading<MemoryUse>,
        val network: Reading<NetworkRate>,
        val temperature: Reading<Temperature>,
        val power: Power,
    )

    /** The machine's power, combined for the same reason as [Machine]. */
    private data class Power(
        val batteries: Reading<List<BatteryEntry>>,
        val profile: Reading<ProfileEntry?>,
    )

    /** The desktop's own services, combined for the same reason as [Machine]. */
    private data class Services(
        val tray: TrayReadings,
        val notifications: Reading<List<Posted>>,
        val workspaces: Reading<WorkspaceStrip>,
        val window: Reading<FocusedWindow?>,
        val media: Reading<NowPlaying?>,
    )

    /** What the keyboard is doing, combined for the same reason as [Machine]. */
    private data class Keyboard(
        val submap: Reading<String?>,
        val layout: Reading<String?>,
    )

    companion object {
        private val DEFAULT_SESSION = 25.minutes
    }
}
