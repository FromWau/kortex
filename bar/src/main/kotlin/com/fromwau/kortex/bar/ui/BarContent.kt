package com.fromwau.kortex.bar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fromwau.kortex.tray.ItemAddress
import com.fromwau.kortex.notification.Urgency
import com.fromwau.kortex.bar.state.BarAction
import com.fromwau.kortex.bar.state.BarScheme
import com.fromwau.kortex.bar.state.BarState
import com.fromwau.kortex.bar.state.FocusedWindow
import com.fromwau.kortex.bar.state.Posted
import com.fromwau.kortex.bar.state.Reading
import com.fromwau.kortex.bar.state.SlotShown
import com.fromwau.kortex.bar.state.TimerFace
import com.fromwau.kortex.bar.state.TrayEntry
import com.fromwau.kortex.bar.state.WorkspaceSlot
import com.fromwau.kortex.bar.state.WorkspaceStrip
import com.fromwau.kortex.icons.rememberIconPainter
import com.fromwau.kortex.bar.system.MemoryUse
import com.fromwau.kortex.bar.system.NetworkRate
import com.fromwau.kortex.bar.system.Temperature
import java.time.LocalDateTime

/**
 * The whole bar: the focus timer, the workspaces and the focused window on the left, the tray, the
 * machine's readings and the clock on the right.
 *
 * Stateless, as every composable here is. It holds nothing, reads nothing and does no work of its own:
 * [state] is what it draws and [onAction] is where a click goes.
 */
@Composable
fun BarContent(
    state: BarState,
    onAction: (BarAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TimerWidget(
            face = state.timer,
            onClick = { onAction(BarAction.TimerClicked) },
            onReset = { onAction(BarAction.TimerReset) },
        )
        WorkspacesWidget(
            workspaces = state.workspaces,
            onClick = { id -> onAction(BarAction.WorkspaceClicked(id)) },
        )
        SubmapWidget(state.submap)
        // All the free room, so the right side sits at the edge and a long title is cut at the box rather than
        // pushing it off. Weighting the widget itself with fill = false leaves its unused share at the far end.
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterStart,
        ) {
            WindowWidget(state.focusedWindow)
        }

        SchemeWidget(
            scheme = state.scheme,
            onClick = { onAction(BarAction.SchemeCycled) },
        )
        TrayWidget(
            tray = state.tray,
            hovered = state.hoveredTray,
            onHover = { address -> onAction(BarAction.TrayHovered(address)) },
        )
        NotificationWidget(state.notifications)
        NetworkWidget(state.network)
        TemperatureWidget(state.temperature)
        GaugeWidget(label = "CPU", fraction = state.cpuLoad, readout = ::percent)
        MemoryWidget(state.memory)
        KeyboardWidget(state.keyboardLayout)
        ClockWidget(
            clock = state.clock,
            showDetail = state.showDetail,
            onClick = { onAction(BarAction.ClockClicked) },
        )
    }
}

/**
 * Which scheme the bar is drawing with. Clicking it moves to the next one.
 *
 * Named rather than shown as a swatch, because the two custom cases differ only in which half of one file
 * they read and a swatch of a theme that failed to load looks like a theme that loaded dark.
 */
@Composable
private fun SchemeWidget(scheme: BarScheme, onClick: () -> Unit) {
    Widget(onClick = onClick) {
        Label("THEME")
        Readout(scheme.named())
    }
}

/** What to call this scheme on the bar, where there is room for a word and not a sentence. */
private fun BarScheme.named(): String = when (this) {
    BarScheme.Amoled -> "amoled"
    BarScheme.Monochrome -> "mono"
    is BarScheme.Custom.Light -> "file, light"
    is BarScheme.Custom.Dark -> "file, dark"
}

/** The date and time. Clicking it adds the date and the seconds, and clicking again takes them away. */
@Composable
private fun ClockWidget(
    clock: Reading<LocalDateTime>,
    showDetail: Boolean,
    onClick: () -> Unit,
) {
    Widget(onClick = onClick, container = MaterialTheme.colorScheme.primaryContainer) {
        when (clock) {
            Reading.Pending -> Readout("--:--")
            is Reading.Unavailable -> Unavailable(clock)
            is Reading.Value -> Readout(clock.value.formatted(showDetail), weight = FontWeight.Medium)
        }
    }
}

/** A labelled percentage with a bar behind it, which is what CPU and memory both are. */
@Composable
private fun GaugeWidget(
    label: String,
    fraction: Reading<Float>,
    readout: (Float) -> String,
) {
    Widget {
        Label(label)
        when (fraction) {
            Reading.Pending -> Readout("--%")
            is Reading.Unavailable -> Unavailable(fraction)
            is Reading.Value -> {
                Gauge(fraction.value)
                Readout(readout(fraction.value))
            }
        }
    }
}

/** Memory in use, as a gauge and as gibibytes out of the machine's total. */
@Composable
private fun MemoryWidget(memory: Reading<MemoryUse>) {
    Widget {
        Label("RAM")
        when (memory) {
            Reading.Pending -> Readout("--")
            is Reading.Unavailable -> Unavailable(memory)
            is Reading.Value -> {
                Gauge(memory.value.fraction)
                Readout("${gibibytes(memory.value.usedKib)}/${gibibytes(memory.value.totalKib)}G")
            }
        }
    }
}

/** How hot the CPU is, named by whatever the machine's driver calls that sensor. */
@Composable
private fun TemperatureWidget(temperature: Reading<Temperature>) {
    Widget {
        when (temperature) {
            Reading.Pending -> {
                Label("TEMP")
                Readout("--°")
            }

            is Reading.Unavailable -> {
                Label("TEMP")
                Unavailable(temperature)
            }

            is Reading.Value -> {
                Label(temperature.value.label.uppercase())
                Readout(
                    text = "${temperature.value.celsius.degrees}°",
                    color = heatColour(temperature.value.celsius.degrees),
                )
            }
        }
    }
}

/** Bytes per second in and out, which is the one widget that says whether the machine is busy or the link is. */
@Composable
private fun NetworkWidget(network: Reading<NetworkRate>) {
    Widget {
        when (network) {
            Reading.Pending -> Readout("↓ --  ↑ --")
            is Reading.Unavailable -> Unavailable(network)
            is Reading.Value -> Readout(
                "↓ ${network.value.downBytesPerSecond.perSecond()}  ↑ ${network.value.upBytesPerSecond.perSecond()}",
            )
        }
    }
}

/**
 * The system tray: one icon per item, and the hovered item's own text beside them.
 *
 * Nothing here is clickable. A host that activates a tray item opens that application's window or its
 * menu, which is not something a bar under test should do to somebody's session, so this one reads the
 * tray and does not touch it.
 */
@Composable
private fun TrayWidget(
    tray: Reading<List<TrayEntry>>,
    hovered: ItemAddress?,
    onHover: (ItemAddress?) -> Unit,
) {
    Widget {
        Label("TRAY")
        when (tray) {
            Reading.Pending -> Readout("--")
            is Reading.Unavailable -> Unavailable(tray)
            is Reading.Value -> when {
                tray.value.isEmpty() -> Readout("empty")
                else -> TrayIcons(items = tray.value, hovered = hovered, onHover = onHover)
            }
        }
    }
}

@Composable
private fun TrayIcons(
    items: List<TrayEntry>,
    hovered: ItemAddress?,
    onHover: (ItemAddress?) -> Unit,
) {
    for (item in items) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(item.attentionTint())
                .hover(onEnter = { onHover(item.address) }, onExit = { onHover(null) }),
        ) {
            Artwork(painter = rememberIconPainter(item.icon, TRAY_ICON), label = item.id, side = TRAY_ICON)
        }
    }

    // Capped, because an item's tooltip is its own text and a long one would push the clock off the bar.
    items.firstOrNull { item -> item.address == hovered }?.let { item ->
        Readout(text = item.hover, modifier = Modifier.widthIn(max = HOVER_WIDTH))
    }
}

/**
 * Whether this shell is the notification server, and how many notifications it is holding.
 *
 * The popup is the widget; this is the part of it that is visible when there is nothing to pop up, and
 * the only place the reason shows when another daemon already holds the name.
 */
@Composable
private fun NotificationWidget(notifications: Reading<List<Posted>>) {
    Widget {
        Label("NOTIF")
        when (notifications) {
            Reading.Pending -> Readout("--")
            is Reading.Unavailable -> Unavailable(notifications)
            is Reading.Value -> when {
                notifications.value.isEmpty() -> Readout("none")
                else -> Readout(
                    text = notifications.value.size.toString(),
                    color = when {
                        notifications.value.any { it.urgency == Urgency.Critical } ->
                            MaterialTheme.colorScheme.error

                        else -> MaterialTheme.colorScheme.secondary
                    },
                    weight = FontWeight.Medium,
                )
            }
        }
    }
}

/** The focus timer: one target that starts, pauses and resumes, and a right click that abandons the session. */
@Composable
private fun TimerWidget(
    face: TimerFace,
    onClick: () -> Unit,
    onReset: () -> Unit,
) {
    Widget(onClick = onClick, onSecondaryClick = onReset) {
        when (face) {
            TimerFace.Idle -> {
                Label("FOCUS")
                Readout("start")
            }

            is TimerFace.Counting -> {
                Label("FOCUS")
                Readout(
                    text = face.secondsLeft.asClock(),
                    color = MaterialTheme.colorScheme.tertiary,
                    weight = FontWeight.Medium,
                )
            }

            is TimerFace.Paused -> {
                Label("HELD")
                Readout(face.secondsLeft.asClock(), color = LocalContentColor.current.copy(alpha = LABEL_ALPHA))
            }

            TimerFace.Elapsed -> {
                Label("FOCUS")
                Readout("done", color = MaterialTheme.colorScheme.primary, weight = FontWeight.Bold)
            }
        }
    }
}

/**
 * This monitor's workspaces from 1 to one past the highest in use, the one being looked at filled, and the special
 * workspace open over them after a gap. Clicking a number goes there.
 */
@Composable
private fun WorkspacesWidget(
    workspaces: Reading<WorkspaceStrip>,
    onClick: (Int) -> Unit,
) {
    Widget {
        when (workspaces) {
            Reading.Pending -> Readout("--")
            is Reading.Unavailable -> Unavailable(workspaces)
            is Reading.Value -> {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    workspaces.value.slots.forEach { slot ->
                        WorkspacePill(slot = slot, onClick = { onClick(slot.id) })
                    }
                }
                workspaces.value.special?.let { name -> SpecialPill(name) }
            }
        }
    }
}

/** The special workspace open over this monitor, named, since a special one has no number to show. */
@Composable
private fun SpecialPill(name: String) {
    val fill = MaterialTheme.colorScheme.tertiary

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(fill)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Readout(text = name, color = contentColorFor(fill))
    }
}

/** The keybind submap in force, shown only outside the default one, where keys do something unusual. */
@Composable
private fun SubmapWidget(submap: Reading<String?>) {
    val name = (submap as? Reading.Value)?.value ?: return

    Widget(container = MaterialTheme.colorScheme.tertiaryContainer) {
        Label("MODE")
        Readout(name, weight = FontWeight.Medium)
    }
}

/** The layout the keyboard types in. */
@Composable
private fun KeyboardWidget(layout: Reading<String?>) {
    Widget {
        Label("KB")
        when (layout) {
            Reading.Pending -> Readout("--")
            is Reading.Unavailable -> Unavailable(layout)
            is Reading.Value -> Readout(layout.value ?: "none")
        }
    }
}

/** One workspace's number, filled where a monitor shows it or a window on it wants attention, faded where empty. */
@Composable
private fun WorkspacePill(
    slot: WorkspaceSlot,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    // The workspace being looked at keeps its own fill: an urgent window on it is cleared once it takes focus.
    val fill = when {
        slot.shown == SlotShown.Focused -> colors.primary
        slot.urgent -> colors.errorContainer
        slot.shown == SlotShown.Visible -> colors.tertiaryContainer
        else -> Color.Transparent
    }
    val ink = when (fill) {
        Color.Transparent -> LocalContentColor.current
        else -> contentColorFor(fill)
    }

    Box(
        modifier = Modifier
            .widthIn(min = PILL_WIDTH)
            .clip(RoundedCornerShape(6.dp))
            .background(fill)
            .clicks(onClick, null)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Readout(
            text = slot.id.toString(),
            color = if (slot.windows == 0) ink.copy(alpha = EMPTY_ALPHA) else ink,
            weight = if (slot.shown == SlotShown.Focused) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

/** The focused window: its application, short, and its title, cut where the bar runs out of room. */
@Composable
private fun WindowWidget(window: Reading<FocusedWindow?>) {
    Widget {
        when (window) {
            Reading.Pending -> Readout("--")
            is Reading.Unavailable -> Unavailable(window)
            is Reading.Value -> when (val focused = window.value) {
                null -> {
                    Label("WINDOW")
                    Readout("none")
                }

                else -> {
                    Label(focused.appId.substringAfterLast('.').uppercase())
                    Readout(focused.title)
                }
            }
        }
    }
}

/** The tint behind an item asking to be noticed, and nothing behind one that is not. */
@Composable
private fun TrayEntry.attentionTint(): Color = when {
    needsAttention -> MaterialTheme.colorScheme.errorContainer
    else -> Color.Transparent
}

/** A horizontal bar whose filled part is [fraction] of its width. */
@Composable
private fun Gauge(fraction: Float) {
    Box(
        Modifier
            .width(GAUGE_WIDTH)
            .height(GAUGE_HEIGHT)
            .clip(RoundedCornerShape(GAUGE_HEIGHT / 2))
            .background(LocalContentColor.current.copy(alpha = TRACK_ALPHA)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .background(loadColour(fraction)),
        )
    }
}

/** Why a widget has nothing to show, in as few characters as the bar has room for. */
@Composable
private fun Unavailable(reading: Reading.Unavailable) {
    Readout(
        text = reading.error.shortly(),
        color = MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun Label(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        // Dimmed against its own chip rather than a grey of its own, so a label stays subordinate to the
        // readout beside it under every palette.
        color = LocalContentColor.current.copy(alpha = LABEL_ALPHA),
    )
}

@Composable
private fun Readout(
    text: String,
    color: Color = LocalContentColor.current,
    weight: FontWeight = FontWeight.Normal,
    modifier: Modifier = Modifier,
) {
    Text(
        modifier = modifier,
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        fontFamily = FontFamily.Monospace,
        fontWeight = weight,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** One widget's row, with the padding and the click handling every widget shares. */
@Composable
private fun Widget(
    onClick: (() -> Unit)? = null,
    onSecondaryClick: (() -> Unit)? = null,
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    content: @Composable () -> Unit,
) {
    // A background and a content colour rather than material3's Surface, which substitutes surfaceTint
    // over anything equal to colorScheme.surface once an ancestor contributes tonal elevation. A bar wants
    // the colour it asked for.
    CompositionLocalProvider(LocalContentColor provides contentColorFor(container)) {
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .padding(vertical = 4.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(container)
                .clicks(onClick, onSecondaryClick)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            content()
        }
    }
}

private val GAUGE_WIDTH = 42.dp
private val GAUGE_HEIGHT = 6.dp
private const val LABEL_ALPHA = 0.7f
private const val TRACK_ALPHA = 0.2f

private val TRAY_ICON = 18.dp
private val PILL_WIDTH = 22.dp
private const val EMPTY_ALPHA = 0.45f
private val HOVER_WIDTH = 220.dp
