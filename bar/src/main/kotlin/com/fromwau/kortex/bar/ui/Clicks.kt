package com.fromwau.kortex.bar.ui

import androidx.compose.foundation.clickable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import kotlin.math.roundToInt

/**
 * A left click to [onPrimary] and a right click to [onSecondary], either of which may be absent.
 *
 * The secondary button needs the raw pointer events: `clickable` reports a click without saying which
 * button made it, and the demo app in kortex reads the button the same way.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.clicks(
    onPrimary: (() -> Unit)?,
    onSecondary: (() -> Unit)? = null,
): Modifier {
    val primary = onPrimary?.let { handler -> Modifier.clickable(onClick = handler) } ?: Modifier
    val secondary = onSecondary?.let { handler ->
        Modifier.pointerInput(handler) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.type != PointerEventType.Press) continue
                    if (event.button != PointerButton.Secondary) continue
                    // Consumed, so the bar's own right click, which opens its demo menu, leaves it alone.
                    event.changes.forEach { it.consume() }
                    handler()
                }
            }
        }
    } ?: Modifier

    return this then secondary then primary
}

/**
 * Every button pressed on this, with where it was pressed in logical pixels from the surface's left edge.
 *
 * On the press rather than the release, since a menu opened from it needs the press to take its grab. The
 * press is consumed, so the bar's own right click leaves it alone.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.presses(onPress: (button: PointerButton, x: Int) -> Unit): Modifier = composed {
    // Remembered, so the position measured at the last placement is still here after a recomposition that
    // moved nothing and so placed nothing again.
    val left = remember { mutableFloatStateOf(0f) }
    val press by rememberUpdatedState(onPress)
    onGloballyPositioned { coordinates -> left.floatValue = coordinates.positionInWindow().x }
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.type != PointerEventType.Press) continue
                    val button = event.button ?: continue
                    event.changes.forEach { it.consume() }
                    // This scope's density is the buffer scale the position was measured at, so dividing by
                    // it gives the logical pixels a popup is placed in.
                    press(button, ((left.floatValue + event.changes.first().position.x) / density).roundToInt())
                }
            }
        }
}
