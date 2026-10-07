package com.fromwau.kortex.tray

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.fold
import com.fromwau.kern.result.getOrElse
import com.fromwau.kortex.dbus.SessionBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

/**
 * Prints the menu behind every tray item that offers one, and everything that happens to it, until Enter.
 *
 * A test can say a menu decoded; only a person watching can say it changed when the application changed
 * it. Toggle something in an application's tray menu while this runs.
 */
fun main(): Unit = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val tray = Tray(SessionBus(scope), scope)
    val items = tray.items.first { it != Err(TrayError.NotConnected) }
        .getOrElse { error ->
            System.err.println("the tray could not be read: $error")
            exitProcess(1)
        }

    items.forEach { item ->
        val menu = tray.menu(item) ?: return@forEach println("${item.address} offers no menu")
        println("${item.address} -> ${item.menuPath}")
        scope.launch {
            menu.layout.collect { state ->
                state.fold({ root -> describe(root, "  ") }, { error -> println("  $error") })
            }
        }
    }

    println("watching; press Enter to stop")
    readlnOrNull()
    scope.cancel()
}

private fun describe(item: MenuItem, indent: String) {
    val marks = buildList {
        if (item.isSeparator) add("separator")
        if (!item.enabled) add("disabled")
        if (!item.visible) add("hidden")
        item.toggle?.let { add("${it.kind}=${it.state}") }
        if (!item.icon.isEmpty) add("icon=${item.icon.name ?: "${item.icon.data?.size} bytes"}")
        if (item.disposition != MenuDisposition.Normal) add(item.disposition.name)
        item.shortcuts.forEach { add(it.joinToString("+")) }
        if (item.hasSubmenu) add("submenu")
    }
    val trailing = if (marks.isEmpty()) "" else marks.joinToString(prefix = "  (", postfix = ")")
    println("$indent[${item.id}] ${item.label.ifEmpty { "<no label>" }}$trailing")
    item.children.forEach { child -> describe(child, "$indent  ") }
}
