package com.fromwau.kortex.tray

import com.fromwau.kern.result.fold
import com.fromwau.kortex.dbus.SessionBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Prints the tray and everything that happens to it, until Enter.
 *
 * A test can say the tray decoded; only a person watching can say an icon changed when the application
 * changed it. Open and close something that sits in the tray while this runs.
 */
fun main(): Unit = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val watching = scope.launch {
        Tray(SessionBus(scope), scope).items.collect { state ->
            state.fold(
                { items ->
                    println("--- ${items.size} item(s) ---")
                    items.forEach(::describe)
                },
                { error -> println("--- $error ---") },
            )
        }
    }

    println("watching the tray; press Enter to stop")
    readlnOrNull()
    watching.cancel()
    scope.cancel()
}

private fun describe(item: TrayItem) {
    println("  ${item.address}")
    println("    id=${item.id.ifEmpty { "<none>" }}  title=${item.title.ifEmpty { "<none>" }}")
    println("    status=${item.status}  category=${item.category}  isMenu=${item.isMenu}")
    println("    icon=${describe(item.icon)}")
    if (!item.overlayIcon.isEmpty) println("    overlay=${describe(item.overlayIcon)}")
    if (!item.attentionIcon.isEmpty) println("    attention=${describe(item.attentionIcon)}")
    item.menuPath?.let { println("    menu=$it") }
    item.toolTip?.let { println("    tooltip=${it.title.ifEmpty { "<none>" }} / ${it.description}") }
}

private fun describe(icon: TrayIcon): String = buildList {
    icon.name?.let { add("name=$it") }
    icon.themePath?.let { add("themePath=$it") }
    icon.pixmaps.forEach { add("${it.width}x${it.height} (${it.argb.size} bytes)") }
}.joinToString(separator = ", ").ifEmpty { "<none>" }
