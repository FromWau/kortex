package com.fromwau.kortex.bar.ui

import androidx.compose.foundation.clickable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput

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
                    handler()
                }
            }
        }
    } ?: Modifier

    return this then secondary then primary
}

/**
 * [onEnter] when the pointer moves onto this, and [onExit] when it leaves.
 *
 * Raw pointer events rather than `hoverable`, which reports hover through an interaction source a
 * composable then has to hold and read: this bar's hover belongs in the state holder, so what the
 * composable needs is the two edges and nothing kept locally.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.hover(onEnter: () -> Unit, onExit: () -> Unit): Modifier =
    this then Modifier.pointerInput(onEnter, onExit) {
        awaitPointerEventScope {
            while (true) {
                when (awaitPointerEvent().type) {
                    PointerEventType.Enter -> onEnter()
                    PointerEventType.Exit -> onExit()
                    else -> Unit
                }
            }
        }
    }
