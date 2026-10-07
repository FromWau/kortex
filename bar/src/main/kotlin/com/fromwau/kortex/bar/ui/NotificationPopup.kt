package com.fromwau.kortex.bar.ui

import com.fromwau.kortex.notification.CloseReason
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.painter.Painter
import com.fromwau.kortex.tray.TrayIcon
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fromwau.kortex.notification.Urgency
import com.fromwau.kortex.wayland.Edge
import com.fromwau.kortex.wayland.ExclusiveZone
import com.fromwau.kortex.wayland.Layer
import com.fromwau.kortex.wayland.LayerSurface
import com.fromwau.kortex.wayland.Length
import com.fromwau.kortex.wayland.Margins
import com.fromwau.kortex.wayland.Monitor
import com.fromwau.kortex.bar.state.BarAction
import com.fromwau.kortex.bar.state.Posted
import com.fromwau.kortex.icons.rememberIconPainter
import com.fromwau.kortex.bar.state.Reading
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds
import com.fromwau.kortex.bar.state.showsFor

/**
 * The notifications this shell is holding, as a surface of their own in the top right of [monitor].
 *
 * A surface rather than a strip inside the bar, which is what kortex supporting several surfaces in one
 * application buys: the popup is on the overlay layer, reserves nothing, and is only on screen while
 * there is something to show, because the call leaves composition when the last notification is closed.
 *
 * The height is computed rather than measured. A layer surface is exactly the size it asks for, so there
 * is no asking the content how tall it wants to be: every card is [CARD_HEIGHT] and a body too long for
 * that is clipped.
 */
@Composable
fun NotificationPopup(
    monitor: Monitor,
    notifications: Reading<List<Posted>>,
    onAction: (BarAction) -> Unit,
) {
    val shown = (notifications as? Reading.Value)?.value.orEmpty().takeLast(MAX_CARDS).asReversed()
    if (shown.isEmpty()) return

    LayerSurface(
        monitor = monitor,
        namespace = "kortex-bar-qa-notifications-${monitor.name}",
        layer = Layer.Overlay,
        anchor = setOf(Edge.Top, Edge.Right),
        width = Length.Of(CARD_WIDTH + EDGE_INSET * 2),
        height = Length.Of(CARD_HEIGHT * shown.size + CARD_GAP * (shown.size - 1) + EDGE_INSET * 2),
        margins = Margins(top = POPUP_MARGIN, right = POPUP_MARGIN),
        exclusiveZone = ExclusiveZone.Yield,
    ) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            NotificationStack(shown, onAction)
        }
    }
}

/** The cards themselves, newest at the top. Stateless, as every composable here is. */
@Composable
private fun NotificationStack(
    notifications: List<Posted>,
    onAction: (BarAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(EDGE_INSET),
        verticalArrangement = Arrangement.spacedBy(CARD_GAP),
    ) {
        for (posted in notifications) key(posted.id) {
            // Keyed on the revision too, so a notification replaced in place starts its time again.
            LaunchedEffect(posted.revision) {
                val stays = posted.showsFor(DEFAULT_EXPIRY) ?: return@LaunchedEffect
                delay(stays)
                onAction(BarAction.NotificationClosed(posted.id, CloseReason.Expired))
            }
            NotificationCard(
                posted = posted,
                onDismiss = { onAction(BarAction.NotificationClosed(posted.id, CloseReason.Dismissed)) },
            )
        }
    }
}

/** One notification. Clicking anywhere on it closes it and tells its application it was dismissed. */
@Composable
private fun NotificationCard(posted: Posted, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(CARD_HEIGHT)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clicks(onPrimary = onDismiss)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        UrgencyStripe(posted.urgency)

        Artwork(painter = posted.artwork(), label = posted.appName, side = CARD_ICON)

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = posted.appName.ifBlank { "unknown" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "click to dismiss",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                )
            }

            Text(
                text = posted.summary,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            if (posted.body.isNotBlank()) {
                Text(
                    text = posted.body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = BODY_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A coloured edge, which is the one thing urgency is worth spending room on in a card this small. */
@Composable
private fun UrgencyStripe(urgency: Urgency) {
    Box(
        Modifier
            .width(3.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(2.dp))
            .background(urgency.colour()),
    )
}

@Composable
private fun Urgency.colour(): Color = when (this) {
    Urgency.Low -> MaterialTheme.colorScheme.outline
    Urgency.Normal -> MaterialTheme.colorScheme.primary
    Urgency.Critical -> MaterialTheme.colorScheme.error
}

/** How many notifications the popup shows at once; older ones are held but not drawn. */
private const val MAX_CARDS = 4

/** How long a notification stays whose application left the time to the server. */
private val DEFAULT_EXPIRY = 2.seconds

private val CARD_WIDTH = 340.dp
private val CARD_HEIGHT = 78.dp
private val CARD_GAP = 8.dp
private val CARD_ICON = 32.dp
private val EDGE_INSET = 6.dp
private val POPUP_MARGIN = 12.dp
private const val BODY_LINES = 2

/**
 * The painter for whatever this notification offered, inline pixels first.
 *
 * An application that sent an image meant that image; a name is the fallback it offered for a host with
 * no way to draw one. Null where it gave neither, which the monogram covers.
 */
@Composable
private fun Posted.artwork(): Painter? =
    image?.let { sent -> rememberIconPainter(sent) }
        ?: iconName?.let { named -> rememberIconPainter(TrayIcon(name = named), CARD_ICON) }
