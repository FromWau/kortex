package com.fromwau.kortex.bar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.fromwau.kortex.bar.state.BarAction
import com.fromwau.kortex.bar.state.BarState
import com.fromwau.kortex.bar.state.Posted
import com.fromwau.kortex.bar.state.Reading
import com.fromwau.kortex.bar.state.TimerFace
import com.fromwau.kortex.bar.state.TrayEntry
import com.fromwau.kortex.icons.rememberIconPainter
import com.fromwau.kortex.bar.system.MemoryUse
import com.fromwau.kortex.bar.system.NetworkRate
import com.fromwau.kortex.bar.system.Temperature
import java.time.LocalDateTime

/**
 * The whole bar: the focus timer on the left, the tray, the machine's readings and the clock on the right.
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
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TimerWidget(
            face = state.timer,
            onClick = { onAction(BarAction.TimerClicked) },
            onReset = { onAction(BarAction.TimerReset) },
        )

        Spacer(Modifier.weight(1f))

        TrayWidget(
            tray = state.tray,
            hovered = state.hoveredTray,
            onHover = { address -> onAction(BarAction.TrayHovered(address)) },
        )
        Separator()
        NotificationWidget(state.notifications)
        Separator()
        NetworkWidget(state.network)
        Separator()
        TemperatureWidget(state.temperature)
        Separator()
        GaugeWidget(label = "CPU", fraction = state.cpuLoad, readout = ::percent)
        Separator()
        MemoryWidget(state.memory)
        Separator()
        ClockWidget(
            clock = state.clock,
            showDetail = state.showDetail,
            onClick = { onAction(BarAction.ClockClicked) },
        )
    }
}

/** The date and time. Clicking it adds the date and the seconds, and clicking again takes them away. */
@Composable
private fun ClockWidget(
    clock: Reading<LocalDateTime>,
    showDetail: Boolean,
    onClick: () -> Unit,
) {
    Widget(onClick = onClick) {
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
                    color = temperature.value.celsius.degrees.heatColor(),
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
                else -> Readout(notifications.value.size.toString(), weight = FontWeight.Medium)
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
                Readout(face.secondsLeft.asClock(), weight = FontWeight.Medium)
            }

            is TimerFace.Paused -> {
                Label("HELD")
                Readout(face.secondsLeft.asClock(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            TimerFace.Elapsed -> {
                Label("FOCUS")
                Readout("done", color = MaterialTheme.colorScheme.primary, weight = FontWeight.Bold)
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
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .background(fraction.loadColor()),
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
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Readout(
    text: String,
    color: Color = MaterialTheme.colorScheme.onSurface,
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
    content: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(6.dp))
            .clicks(onClick, onSecondaryClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        content()
    }
}

@Composable
private fun Separator() {
    Box(
        Modifier
            .width(1.dp)
            .height(SEPARATOR_HEIGHT)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

private val GAUGE_WIDTH = 42.dp
private val GAUGE_HEIGHT = 6.dp
private val SEPARATOR_HEIGHT = 16.dp
private val TRAY_ICON = 18.dp
private val HOVER_WIDTH = 220.dp
