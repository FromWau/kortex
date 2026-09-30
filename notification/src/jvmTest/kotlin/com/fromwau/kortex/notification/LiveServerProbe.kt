package com.fromwau.kortex.notification

import com.fromwau.kern.result.getOrElse
import com.fromwau.kern.result.onError
import com.fromwau.kortex.dbus.DBusConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

/**
 * Makes kortex the notification server and prints what arrives, until an empty line.
 *
 * Needs the name, so stop whatever holds it first: `systemctl --user stop dunst.service`, and start it
 * again afterwards. Then send something, with `notify-send` or by using an application that notifies, and
 * watch it land. Typing an id closes that notification, and an id and an action key invokes the action,
 * which is what tells the application the user chose it.
 */
fun main(): Unit = runBlocking {
    val connection = DBusConnection.session().getOrElse { error ->
        System.err.println("no session bus: $error")
        exitProcess(1)
    }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    connection.use {
        val server = NotificationServer.start(connection, INFORMATION).getOrElse { error ->
            System.err.println(explain(error))
            exitProcess(1)
        }

        scope.launch {
            server.notifications.collect { posted ->
                println("--- ${posted.size} posted ---")
                posted.forEach(::describe)
            }
        }

        println("kortex is the notification server; <id> closes, <id> <action> invokes, empty line quits")
        while (true) {
            val line = readlnOrNull()?.trim().orEmpty()
            if (line.isEmpty()) break

            val id = line.substringBefore(' ').toUIntOrNull() ?: continue
            val action = line.substringAfter(' ', missingDelimiterValue = "")
            if (action.isEmpty()) {
                server.close(id, CloseReason.Dismissed).onError { println("  $it") }
            } else {
                server.invoke(id, action).onError { println("  $it") }
            }
        }

        server.stop()
        scope.cancel()
    }
    println("name released; start your daemon again: systemctl --user start dunst.service")
}

private fun explain(error: NotificationError): String = when (error) {
    is NotificationError.AlreadyServed ->
        "${error.process ?: error.owner} already holds ${NotificationServer.INTERFACE}" +
            (error.pid?.let { " (pid $it)" } ?: "") +
            "; stop it first, for dunst that is: systemctl --user stop dunst.service"

    else -> "could not become the notification server: $error"
}

private fun describe(notification: Notification) {
    println("  [${notification.id}] ${notification.summary}")
    println("      from=${notification.appName.ifEmpty { "<unnamed>" }}  urgency=${notification.urgency}")
    if (notification.body.isNotEmpty()) println("      body=${notification.body}")
    println("      expiry=${notification.expiry}")
    notification.category?.let { println("      category=$it") }
    notification.desktopEntry?.let { println("      desktopEntry=$it") }
    notification.appIcon.ifEmpty { null }?.let { println("      appIcon=$it") }
    notification.imagePath?.let { println("      imagePath=$it") }
    notification.image?.let { println("      image=${it.width}x${it.height} ${it.pixels.size} bytes") }
    notification.soundName?.let { println("      soundName=$it") }
    notification.soundFile?.let { println("      soundFile=$it") }
    if (notification.isTransient) println("      transient")
    if (notification.isResident) println("      resident")
    notification.actions.forEach { println("      action ${it.key} = ${it.label}") }
    val extra = notification.hints.keys - KNOWN
    if (extra.isNotEmpty()) println("      other hints: ${extra.sorted()}")
}

private val INFORMATION = ServerInformation(
    name = "kortex",
    vendor = "com.fromwau",
    version = "0.1.0",
    capabilities = listOf("actions", "body", "body-markup", "persistence"),
)

/** The hints that already have a field, so the probe can show what an application sent beyond them. */
private val KNOWN = setOf(
    "urgency", "category", "desktop-entry", "transient", "resident",
    "image-data", "icon_data", "image-path", "sound-file", "sound-name",
    "suppress-sound", "action-icons", "x", "y",
)
