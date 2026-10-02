package com.fromwau.kortex.bar.state

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kortex.notification.NotificationError
import com.fromwau.kortex.tray.ItemAddress
import com.fromwau.kortex.tray.TrayIcon
import com.fromwau.kortex.tray.TrayError
import com.fromwau.kortex.bar.BarError
import com.fromwau.kortex.bar.desktop.Desktop
import com.fromwau.kortex.notification.Urgency
import com.fromwau.kortex.bar.system.MemoryUse
import com.fromwau.kortex.bar.system.NetworkRate
import com.fromwau.kortex.bar.system.SystemMetrics
import com.fromwau.kortex.bar.system.Temperature
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class BarStateHolderTest {
    @Test
    fun `every widget is pending until its own source answers, not until the slowest one does`() = runTest {
        val metrics = FakeMetrics()
        val holder = holder(metrics, clock = MutableStateFlow(NOON))

        runCurrent()
        assertIs<Reading.Pending>(holder.state.value.cpuLoad)
        assertIs<Reading.Value<LocalDateTime>>(holder.state.value.clock)

        metrics.cpu.value = Ok(0.42f)
        runCurrent()
        assertEquals(Reading.Value(0.42f), holder.state.value.cpuLoad)
        assertIs<Reading.Pending>(holder.state.value.memory)
    }

    @Test
    fun `a source that fails shows why, and the widgets beside it keep their readings`() = runTest {
        val metrics = FakeMetrics()
        val holder = holder(metrics, clock = MutableStateFlow(NOON))

        metrics.cpu.value = Ok(0.5f)
        metrics.temperature.value = Err(BarError.NoSensor("/sys/class/hwmon"))
        runCurrent()

        assertEquals(Reading.Unavailable(BarError.NoSensor("/sys/class/hwmon")), holder.state.value.temperature)
        assertEquals(Reading.Value(0.5f), holder.state.value.cpuLoad)
    }

    @Test
    fun `clicking the clock turns the date and seconds on, and clicking again off`() = runTest {
        val holder = holder(FakeMetrics(), clock = MutableStateFlow(NOON))
        runCurrent()

        holder.onAction(BarAction.ClockClicked)
        runCurrent()
        assertEquals(true, holder.state.value.showDetail)

        holder.onAction(BarAction.ClockClicked)
        runCurrent()
        assertEquals(false, holder.state.value.showDetail)
    }

    @Test
    fun `the focus timer starts, counts down with the clock, pauses where it was and resumes from there`() =
        runTest {
            var wallClock = NOON
            val ticks = MutableStateFlow(NOON)
            val holder = holder(FakeMetrics(), clock = ticks, now = { wallClock })
            runCurrent()

            assertEquals(TimerFace.Idle, holder.state.value.timer)

            holder.onAction(BarAction.TimerClicked)
            runCurrent()
            assertEquals(TimerFace.Counting(SESSION.inWholeSeconds), holder.state.value.timer)

            wallClock = NOON.plusSeconds(60)
            ticks.value = wallClock
            runCurrent()
            assertEquals(TimerFace.Counting(SESSION.inWholeSeconds - 60), holder.state.value.timer)

            holder.onAction(BarAction.TimerClicked)
            runCurrent()
            assertEquals(TimerFace.Paused(SESSION.inWholeSeconds - 60), holder.state.value.timer)

            // A paused session does not move with the clock.
            wallClock = NOON.plusSeconds(300)
            ticks.value = wallClock
            runCurrent()
            assertEquals(TimerFace.Paused(SESSION.inWholeSeconds - 60), holder.state.value.timer)

            holder.onAction(BarAction.TimerClicked)
            runCurrent()
            assertEquals(TimerFace.Counting(SESSION.inWholeSeconds - 60), holder.state.value.timer)
        }

    @Test
    fun `a session that runs out says so, and the next click clears it`() = runTest {
        var wallClock = NOON
        val ticks = MutableStateFlow(NOON)
        val holder = holder(FakeMetrics(), clock = ticks, now = { wallClock })
        runCurrent()

        holder.onAction(BarAction.TimerClicked)
        wallClock = NOON.plusSeconds(SESSION.inWholeSeconds)
        ticks.value = wallClock
        runCurrent()
        assertEquals(TimerFace.Elapsed, holder.state.value.timer)

        holder.onAction(BarAction.TimerClicked)
        runCurrent()
        assertEquals(TimerFace.Idle, holder.state.value.timer)
    }

    @Test
    fun `a right click abandons a running session`() = runTest {
        val holder = holder(FakeMetrics(), clock = MutableStateFlow(NOON))
        runCurrent()

        holder.onAction(BarAction.TimerClicked)
        runCurrent()
        assertIs<TimerFace.Counting>(holder.state.value.timer)

        holder.onAction(BarAction.TimerReset)
        runCurrent()
        assertEquals(TimerFace.Idle, holder.state.value.timer)
    }

    @Test
    fun `the tray arrives as a reading like any other source, and says why where there is no watcher`() = runTest {
        val desktop = FakeDesktop()
        val holder = holder(FakeMetrics(), clock = MutableStateFlow(NOON), desktop = desktop)

        runCurrent()
        assertIs<Reading.Pending>(holder.state.value.tray)

        desktop.trayItems.value = Reading.Value(listOf(entry("steam")))
        runCurrent()
        assertEquals(listOf("steam"), (holder.state.value.tray as Reading.Value).value.map { it.id })

        desktop.trayItems.value = Reading.Unavailable(BarError.NoTray(TrayError.NoWatcher))
        runCurrent()
        assertEquals(Reading.Unavailable(BarError.NoTray(TrayError.NoWatcher)), holder.state.value.tray)
    }

    @Test
    fun `the pointer moving onto a tray item puts it in the state, and off every one takes it out`() = runTest {
        val holder = holder(FakeMetrics(), clock = MutableStateFlow(NOON))
        runCurrent()

        assertEquals(null, holder.state.value.hoveredTray)

        holder.onAction(BarAction.TrayHovered(ADDRESS))
        runCurrent()
        assertEquals(ADDRESS, holder.state.value.hoveredTray)

        holder.onAction(BarAction.TrayHovered(null))
        runCurrent()
        assertEquals(null, holder.state.value.hoveredTray)
    }

    @Test
    fun `a shell that is not the notification server shows which process is, rather than showing nothing`() =
        runTest {
            val desktop = FakeDesktop()
            val holder = holder(FakeMetrics(), clock = MutableStateFlow(NOON), desktop = desktop)

            desktop.posted.value = Reading.Unavailable(BarError.NotServing(TAKEN))
            runCurrent()

            assertEquals(Reading.Unavailable(BarError.NotServing(TAKEN)), holder.state.value.notifications)
        }

    @Test
    fun `dismissing a notification tells the desktop, with the id it was drawn with`() = runTest {
        val desktop = FakeDesktop()
        val holder = holder(FakeMetrics(), clock = MutableStateFlow(NOON), desktop = desktop)
        runCurrent()

        holder.onAction(BarAction.NotificationDismissed(42u))
        runCurrent()

        assertEquals(listOf(42u), desktop.dismissed)
    }

    @Test
    fun `a notification the server is holding reaches the state, oldest first as it arrived`() = runTest {
        val desktop = FakeDesktop()
        val holder = holder(FakeMetrics(), clock = MutableStateFlow(NOON), desktop = desktop)

        desktop.posted.value = Reading.Value(listOf(posted(1u), posted(2u)))
        runCurrent()

        assertEquals(listOf(1u, 2u), (holder.state.value.notifications as Reading.Value).value.map { it.id })
    }

    @Test
    fun `the workspaces and the focused window arrive as readings, and nothing focused is a value`() = runTest {
        val desktop = FakeDesktop()
        val holder = holder(FakeMetrics(), clock = MutableStateFlow(NOON), desktop = desktop)

        runCurrent()
        assertIs<Reading.Pending>(holder.state.value.workspaces)
        assertIs<Reading.Pending>(holder.state.value.focusedWindow)

        desktop.slots.value = Reading.Value(listOf(WorkspaceSlot(1, windows = 1, shown = SlotShown.Focused)))
        desktop.window.value = Reading.Value(null)
        runCurrent()

        assertEquals(listOf(1), (holder.state.value.workspaces as Reading.Value).value.map { it.id })
        assertEquals(Reading.Value(null), holder.state.value.focusedWindow)
        assertIs<Reading.Pending>(holder.state.value.tray, "the tray has not answered and is not held up by them")
    }

    private fun TestScope.holder(
        metrics: SystemMetrics,
        clock: Flow<LocalDateTime>,
        desktop: Desktop = FakeDesktop(),
        now: () -> LocalDateTime = { NOON },
    ): BarStateHolder = BarStateHolder(
        scope = backgroundScope,
        metrics = metrics,
        desktop = desktop,
        clock = clock,
        session = SESSION,
        now = now,
    )
}

/** Every desktop reading under the test's control, and a record of what the bar asked it to do. */
private class FakeDesktop : Desktop {
    val trayItems = MutableStateFlow<Reading<List<TrayEntry>>?>(null)
    val posted = MutableStateFlow<Reading<List<Posted>>?>(null)
    val registry = MutableStateFlow<String?>(null)
    val slots = MutableStateFlow<Reading<List<WorkspaceSlot>>?>(null)
    val window = MutableStateFlow<Reading<FocusedWindow?>?>(null)
    val dismissed = mutableListOf<UInt>()

    override val tray: Flow<Reading<List<TrayEntry>>> get() = trayItems.answers()
    override val trayRegistry: Flow<String?> get() = registry
    override val notifications: Flow<Reading<List<Posted>>> get() = posted.answers()
    override val workspaces: Flow<Reading<List<WorkspaceSlot>>> get() = slots.answers()
    override val focusedWindow: Flow<Reading<FocusedWindow?>> get() = window.answers()

    override suspend fun dismiss(id: UInt) {
        dismissed += id
    }
}

private fun entry(id: String): TrayEntry = TrayEntry(
    address = ADDRESS,
    id = id,
    hover = id,
    icon = TrayIcon(name = id),
    needsAttention = false,
)

private fun posted(id: UInt): Posted = Posted(
    id = id,
    revision = 1u,
    appName = "an-app",
    summary = "a summary",
    body = "",
    urgency = Urgency.Normal,
    image = null,
    iconName = null,
)

/** Every reading under the test's control, each starting as a value the bar would not show. */
private class FakeMetrics : SystemMetrics {
    val cpu = MutableStateFlow<Result<Float, BarError>?>(null)
    val ram = MutableStateFlow<Result<MemoryUse, BarError>?>(null)
    val net = MutableStateFlow<Result<NetworkRate, BarError>?>(null)
    val temperature = MutableStateFlow<Result<Temperature, BarError>?>(null)

    override val cpuLoad: Flow<Result<Float, BarError>> get() = cpu.answers()
    override val memory: Flow<Result<MemoryUse, BarError>> get() = ram.answers()
    override val network: Flow<Result<NetworkRate, BarError>> get() = net.answers()
    override val cpuTemperature: Flow<Result<Temperature, BarError>> get() = temperature.answers()
}

/** A source that has answered nothing yet never emits, which is what a widget's pending state means. */
private fun <T> StateFlow<T?>.answers(): Flow<T> = flow {
    collect { answer -> answer?.let { emit(it) } }
}

private val NOON: LocalDateTime = LocalDateTime.of(2026, 10, 1, 12, 0, 0)
private val SESSION = 25.minutes
private val ADDRESS = ItemAddress(service = ":1.97", path = "/StatusNotifierItem")
private val TAKEN = NotificationError.AlreadyServed(owner = ":1.1860", pid = 714007, process = "dunst")
