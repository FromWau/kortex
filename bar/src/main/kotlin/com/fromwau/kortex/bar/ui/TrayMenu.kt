package com.fromwau.kortex.bar.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.fromwau.kortex.bar.state.BarAction
import com.fromwau.kortex.bar.state.OpenTrayMenu
import com.fromwau.kortex.bar.state.Reading
import com.fromwau.kortex.bar.state.TrayMenuEntry
import com.fromwau.kortex.wayland.ContextMenu
import com.fromwau.kortex.wayland.SurfaceEnd
import com.fromwau.kortex.wayland.SurfaceStatus
import com.fromwau.kortex.wayland.rememberSurfaceState
import kotlin.math.roundToInt
import com.fromwau.kern.result.Ok

/**
 * The open tray menu, below the bar at the point it was opened from.
 *
 * Nothing shows until the application's entries have been read, and a menu with no entries, or one that
 * could not be read, goes away again at once: there is nothing in either to pick.
 *
 * @param below how far down the bar the menu opens, which is the bar's thickness.
 * @param colors the bar's colours, passed in because a menu is a surface of its own and inherits nothing.
 */
@Composable
internal fun TrayMenu(
    menu: OpenTrayMenu,
    below: Dp,
    colors: ColorScheme,
    onAction: (BarAction) -> Unit,
) {
    when (val entries = menu.entries) {
        Reading.Pending -> Unit

        is Reading.Unavailable -> LaunchedEffect(menu.address) { onAction(BarAction.TrayMenuDismissed) }

        is Reading.Value -> when {
            entries.value.isEmpty() -> LaunchedEffect(menu.address) { onAction(BarAction.TrayMenuDismissed) }

            else -> MenuPanel(
                entries = entries.value,
                at = IntOffset(menu.x, below.value.roundToInt()),
                colors = colors,
                onPick = { id -> onAction(BarAction.TrayMenuEntryPicked(id)) },
                onDismissed = { onAction(BarAction.TrayMenuDismissed) },
            )
        }
    }
}

/**
 * One level of the menu, sized to its entries. A click on an entry with a submenu opens that submenu beside
 * it, on a menu of its own.
 */
@Composable
private fun MenuPanel(
    entries: List<TrayMenuEntry>,
    at: IntOffset,
    colors: ColorScheme,
    onPick: (Int) -> Unit,
    onDismissed: () -> Unit,
) {
    val size = rememberMenuSize(entries)
    val state = rememberSurfaceState()
    var submenu by remember(entries) { mutableStateOf<OpenSubmenu?>(null) }

    when (val status = state.status) {
        is SurfaceStatus.Ended -> LaunchedEffect(status) {
            // The desktop dismissing the menu, by a click away or a key that leaves it.
            if (status.result == Ok(SurfaceEnd.ClosedByCompositor)) onDismissed()
        }

        else -> ContextMenu(at = at, menuSize = size, state = state) {
            MaterialTheme(colorScheme = colors) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(vertical = MENU_PADDING),
                ) {
                    var top = MENU_PADDING
                    entries.forEach { entry ->
                        val rowTop = top
                        top += if (entry.isSeparator) SEPARATOR_HEIGHT else ROW_HEIGHT
                        when {
                            entry.isSeparator -> Separator()

                            else -> MenuRow(
                                entry = entry,
                                showsChecks = entries.any { it.checked != null },
                                onClick = {
                                    when {
                                        entry.children.isNotEmpty() -> submenu = OpenSubmenu(
                                            entry.children,
                                            IntOffset(size.width, rowTop.value.roundToInt()),
                                        )

                                        else -> onPick(entry.id)
                                    }
                                },
                            )
                        }
                    }
                }

                submenu?.let { open ->
                    MenuPanel(
                        entries = open.entries,
                        at = open.at,
                        colors = colors,
                        onPick = onPick,
                        onDismissed = { submenu = null },
                    )
                }
            }
        }
    }
}

@Composable
private fun MenuRow(
    entry: TrayMenuEntry,
    showsChecks: Boolean,
    onClick: () -> Unit,
) {
    val content = MaterialTheme.colorScheme.onSurface.let { if (entry.enabled) it else it.copy(alpha = DISABLED_ALPHA) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .let { if (entry.enabled) it.clickable(onClick = onClick) else it }
            .padding(horizontal = ROW_PADDING),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MARK_GAP),
    ) {
        if (showsChecks) Check(checked = entry.checked, color = content)

        Text(
            text = entry.label,
            style = LABEL_STYLE,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        if (entry.children.isNotEmpty()) SubmenuArrow(color = content)
    }
}

/** A filled dot for a checked entry, a ring for an unchecked one, and room for neither where it is neither. */
@Composable
private fun Check(
    checked: Boolean?,
    color: androidx.compose.ui.graphics.Color,
) {
    Box(
        modifier = Modifier
            .size(MARK_SIZE)
            .let { box ->
                when (checked) {
                    null -> box
                    true -> box.background(color, CircleShape)
                    false -> box.border(1.dp, color, CircleShape)
                }
            },
    )
}

@Composable
private fun SubmenuArrow(color: androidx.compose.ui.graphics.Color) {
    Canvas(Modifier.size(MARK_SIZE)) {
        val arrow = Path().apply {
            moveTo(size.width * 0.3f, size.height * 0.2f)
            lineTo(size.width * 0.75f, size.height * 0.5f)
            lineTo(size.width * 0.3f, size.height * 0.8f)
            close()
        }
        drawPath(arrow, color)
    }
}

@Composable
private fun Separator() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(SEPARATOR_HEIGHT)
            .padding(vertical = (SEPARATOR_HEIGHT - 1.dp) / 2),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
    }
}

/**
 * The menu's size in logical pixels, from its labels measured the way [MenuRow] draws them.
 *
 * A menu surface takes its size as it opens, before anything on it is measured, so the labels are measured
 * here first, on the surface that opens it.
 */
@Composable
private fun rememberMenuSize(entries: List<TrayMenuEntry>): IntSize {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(entries, density) {
        val widest = entries
            .filterNot { it.isSeparator }
            .maxOfOrNull { entry -> measurer.measure(entry.label, LABEL_STYLE, maxLines = 1).size.width }
            ?: 0
        val label = with(density) { widest.toDp() }
        val checks = if (entries.any { it.checked != null }) MARK_SIZE + MARK_GAP else 0.dp
        val arrows = if (entries.any { it.children.isNotEmpty() }) MARK_GAP + MARK_SIZE else 0.dp
        val width = (label + checks + arrows + ROW_PADDING * 2).coerceIn(MIN_WIDTH, MAX_WIDTH)
        val height = entries.fold(MENU_PADDING * 2) { sum, entry ->
            sum + if (entry.isSeparator) SEPARATOR_HEIGHT else ROW_HEIGHT
        }
        IntSize(width.value.roundToInt(), height.value.roundToInt())
    }
}

/** A submenu that is open, with its entries and where on its parent it opens. */
private data class OpenSubmenu(
    val entries: List<TrayMenuEntry>,
    val at: IntOffset,
)

private val LABEL_STYLE: TextStyle = Typography().bodyMedium
private val ROW_HEIGHT = 28.dp
private val SEPARATOR_HEIGHT = 9.dp
private val MENU_PADDING = 4.dp
private val ROW_PADDING = 12.dp
private val MARK_SIZE = 10.dp
private val MARK_GAP = 8.dp
private val MIN_WIDTH = 120.dp
private val MAX_WIDTH = 360.dp
private const val DISABLED_ALPHA = 0.38f
