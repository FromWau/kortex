package com.fromwau.kortex.wayland

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay

/** What a window's content publishes: how often it was composed, what it holds, and that its effect runs. */
internal class Watch {
    val compositions = AtomicInteger()
    val effects = AtomicInteger()
    val held = AtomicInteger()
    val ticks = AtomicInteger()
}

/** Content publishing to [watch] what it holds behind `remember` and that its effect is still running. */
@Composable
internal fun Watched(watch: Watch) {
    val held = remember { watch.compositions.incrementAndGet() }
    LaunchedEffect(Unit) {
        watch.effects.incrementAndGet()
        while (true) {
            watch.held.set(held)
            watch.ticks.incrementAndGet()
            delay(TICK_MILLIS)
        }
    }
    Grey()
}

/** A grey fill, so the window that maps has something of its own in it. */
@Composable
internal fun Grey() {
    Box(Modifier.fillMaxSize().background(Color.Gray))
}

private const val TICK_MILLIS = 20L
