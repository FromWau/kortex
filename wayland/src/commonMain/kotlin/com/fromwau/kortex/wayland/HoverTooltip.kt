package com.fromwau.kortex.wayland

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.areAnyPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Shows [tooltip] below the pointer once it has rested over [content] for [delay], the way a browser shows a link's
 * title, in a popup sized by what [tooltip] draws.
 *
 * ```kotlin
 * HoverTooltip(
 *     tooltip = {
 *         Text(
 *             text = "CPU: 42% of 16 cores",
 *             modifier = Modifier.background(Color.DarkGray).padding(6.dp),
 *         )
 *     },
 * ) {
 *     Text("42%")
 * }
 * ```
 *
 * Moving the pointer before [delay] has passed starts the wait again, and once shown the tooltip stays where it
 * opened while the pointer moves over [content]. Leaving [content] takes it away, and so does pressing a button over
 * [content], after which it does not show again until the pointer has left [content] and come back.
 *
 * The tooltip opens far enough below the pointer to clear it, and above the pointer where there is no room below.
 * It draws nothing around [tooltip], neither background nor padding, so give [tooltip] the look it should have.
 * [tooltip] is composed on a surface of its own, which the composition locals around this call do not reach, a
 * `MaterialTheme` among them: read what it needs here and provide it again inside [tooltip].
 *
 * Call it in a surface's content, as [Popup] says.
 *
 * @param tooltip what the tooltip shows.
 * @param modifier applied to the area the pointer rests over, which wraps [content].
 * @param delay how long the pointer rests before the tooltip shows.
 * @param maxWidth the most room [tooltip] is measured in across, which is where its text wraps.
 * @param content what the pointer rests over.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
public fun HoverTooltip(
    tooltip: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    delay: Duration = 500.milliseconds,
    maxWidth: Dp = 480.dp,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current.density
    var area by remember { mutableStateOf<LayoutCoordinates?>(null) }
    // Where the pointer last came to rest over content, in the surface's own pixels; null while it is elsewhere.
    var resting by remember { mutableStateOf<Offset?>(null) }
    var shownAt by remember { mutableStateOf<Offset?>(null) }
    var pressed by remember { mutableStateOf(false) }

    // A pointer dragging something across content is not resting on it.
    fun restAt(event: PointerEvent) {
        if (pressed || shownAt != null || event.buttons.areAnyPressed) return
        resting = area?.localToRoot(event.changes.first().position)
    }

    Box(
        modifier = modifier
            .onGloballyPositioned { area = it }
            .onPointerEvent(PointerEventType.Enter) { event -> restAt(event) }
            .onPointerEvent(PointerEventType.Move) { event -> restAt(event) }
            .onPointerEvent(PointerEventType.Exit) {
                pressed = false
                resting = null
                shownAt = null
            }
            .onPointerEvent(PointerEventType.Press) {
                pressed = true
                resting = null
                shownAt = null
            },
    ) {
        content()
    }

    LaunchedEffect(resting) {
        val at = resting ?: return@LaunchedEffect
        kotlinx.coroutines.delay(delay)
        shownAt = at
    }

    shownAt?.let { at ->
        PopupCall(
            at = IntOffset((at.x / density).roundToInt(), (at.y / density).roundToInt()),
            size = PopupSize.FitContent(DpSize(maxWidth, Dp.Infinity)),
            grab = false,
            state = rememberSurfaceState(),
            clearance = cursorSize,
        ) {
            tooltip()
        }
    }
}
