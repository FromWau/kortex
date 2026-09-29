package com.fromwau.kortex.tray

import com.fromwau.kern.result.getOrElse
import com.fromwau.kortex.dbus.DBusConnection
import com.fromwau.kortex.dbus.DBusType
import com.fromwau.kortex.dbus.DBusValue
import com.fromwau.kortex.dbus.unwrapped
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

/**
 * Prints the `com.canonical.dbusmenu` layout behind every item that offers one.
 *
 * `GetLayout` answers `u(ia{sv}av)`, where each entry's children are variants holding more of the same
 * struct, so this is the one call that says whether the codec handles a type that nests into itself.
 */
fun main(): Unit = runBlocking {
    val connection = DBusConnection.session().getOrElse { error ->
        System.err.println("no session bus: $error")
        exitProcess(1)
    }

    connection.use {
        val watcher = "org.kde.StatusNotifierWatcher"
        val registered = connection
            .property(watcher, "/StatusNotifierWatcher", watcher, "RegisteredStatusNotifierItems")
            .getOrElse { error -> exitProcess(1).also { System.err.println("no watcher: $error") } }

        (registered as? DBusValue.Sequence)?.values.orEmpty().forEach { entry ->
            val address = ItemAddress.parse((entry.unwrapped as DBusValue.Text).value) ?: return@forEach
            val menu = connection
                .property(address.service, address.path, "org.kde.StatusNotifierItem", "Menu")
                .getOrElse { error -> return@forEach println("$address has no menu: $error") }
            val path = (menu as? DBusValue.ObjectPath)?.value ?: return@forEach

            println("$address -> $path")
            val layout = connection.call(
                destination = address.service,
                path = path,
                iface = "com.canonical.dbusmenu",
                member = "GetLayout",
                // Every depth, and every property, which is the widest answer the interface gives.
                args = listOf(
                    DBusValue.I32(0),
                    DBusValue.I32(-1),
                    DBusValue.Sequence(DBusType.Basic.Text, emptyList()),
                ),
            ).getOrElse { error -> return@forEach println("  GetLayout failed: $error") }

            println("  revision ${(layout[0] as DBusValue.U32).value}")
            describe(layout[1], indent = "  ")
        }
    }
}

private fun describe(node: DBusValue, indent: String) {
    val fields = (node.unwrapped as? DBusValue.Struct)?.fields ?: return println("$indent<not a menu entry>")
    val id = (fields[0] as? DBusValue.I32)?.value
    val properties = (fields[1] as? DBusValue.Sequence)
        ?.values
        ?.filterIsInstance<DBusValue.Pair>()
        ?.joinToString { entry -> "${(entry.key as DBusValue.Text).value}=${render(entry.value.unwrapped)}" }
    println("$indent[$id] $properties")
    (fields[2] as? DBusValue.Sequence)?.values?.forEach { child -> describe(child, "$indent  ") }
}

private fun render(value: DBusValue): String = when (value) {
    is DBusValue.Text -> value.value
    is DBusValue.Bool -> value.value.toString()
    is DBusValue.I32 -> value.value.toString()
    else -> value.toString()
}

