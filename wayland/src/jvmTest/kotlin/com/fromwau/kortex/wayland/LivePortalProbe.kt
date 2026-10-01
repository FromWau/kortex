package com.fromwau.kortex.wayland

import androidx.compose.foundation.background
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.getOrElse
import com.fromwau.kortex.dbus.DBusConnection
import com.fromwau.kortex.dbus.DBusType
import com.fromwau.kortex.dbus.DBusValue
import com.fromwau.kortex.dbus.asItems
import com.fromwau.kortex.dbus.asText
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicReference

/**
 * Turns a dragged transfer key into the paths it stands for, which is the seam `:wayland` will not cross.
 *
 * A key arrives on the Wayland wire and means nothing until `org.freedesktop.portal.FileTransfer` is asked
 * about it, and that is D-Bus. The toolkit does not make that call and should not; a caller does, so this
 * probe is the caller, with `:wayland` and `:dbus` both on its classpath.
 *
 * Asks twice on purpose. Once inside `onDrop`, while the drag is still the compositor's, and once after the
 * drag has ended. `busctl` answered `Access denied` for a key whose drag was over, and whether that is the
 * transfer going with the drag or the portal scoping retrieval to the recipient's own connection was never
 * established; two answers from the same key settle which.
 */
public fun main() {
    // Opened before the surface, so the call inside the drop is a round trip and not a handshake too.
    val connection = runBlocking { DBusConnection.session() }.getOrElse { error("live: no session bus: $it") }
    val display = WaylandDisplay.connect().getOrElse { error("live: no compositor answered: $it") }
    val dropped = AtomicReference<String?>(null)

    connection.use {
        display.use { wayland ->
            val shell = KortexShell
                .createApplication(wayland) {
                    TestSurface(
                        PORTAL_NAMESPACE,
                        anchor = setOf(Edge.Top, Edge.Left),
                        margins = Margins(top = MARGIN.dp, left = MARGIN.dp),
                        width = Length.Of(BOX.dp),
                        height = Length.Of(BOX.dp),
                    ) { PortalSquare(connection, dropped) }
                }
                .getOrElse { error("live: shell create failed: $it") }

            try {
                check(shell.pumpOrFail(PLACE_MILLIS) { shell.shownSurfaces.size == 1 }) {
                    "live: the square never reached the screen"
                }
                System.err.println("LIVE-PORTAL ready at ${Screen.geometry(PORTAL_NAMESPACE)}")
                System.err.println("LIVE: drag a file out of a file manager onto the square; ${WAIT / 1_000}s")

                shell.pumpOrFail(WAIT)
            } finally {
                shell.close()
            }

            // The drag is over and the surface gone. The same key again: an answer here means the key
            // outlives its drag, and a refusal means it does not.
            dropped.get()?.let { key ->
                System.err.println("LIVE-PORTAL after-the-drag=${retrieve(connection, key)}")
            }
        }
    }
}

/** `FileTransfer.RetrieveFiles(key, {})`, whose signature on the running portal is `sa{sv}` answering `as`. */
private fun retrieve(connection: DBusConnection, key: String): String = runBlocking {
    connection
        .call(
            destination = "org.freedesktop.portal.Documents",
            path = "/org/freedesktop/portal/documents",
            iface = "org.freedesktop.portal.FileTransfer",
            member = "RetrieveFiles",
            args = listOf(
                DBusValue.Text(key),
                DBusValue.Sequence(DBusType.Pair(DBusType.Basic.Text, DBusType.Variant), emptyList()),
            ),
        )
        .getOrElse { error -> return@runBlocking "refused: $error" }
        .let { body -> body.firstOrNull()?.asItems?.mapNotNull { it.asText }.orEmpty().toString() }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PortalSquare(connection: DBusConnection, dropped: AtomicReference<String?>) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF7C3AED))
            .dragAndDropTarget(
                shouldStartDragAndDrop = { true },
                target = object : DragAndDropTarget {
                    override fun onDrop(event: DragAndDropEvent): Boolean {
                        val offer = event.nativeEvent as? KortexDragOffer ?: return false
                        System.err.println("LIVE-PORTAL types=${offer.types}")
                        System.err.println("LIVE-PORTAL uris=${offer.readUris()}")

                        val key = offer.readPortalKey().getOrElse { error ->
                            System.err.println("LIVE-PORTAL no key: $error")
                            return true
                        }
                        dropped.set(key)
                        System.err.println("LIVE-PORTAL key=$key")
                        // Blocking, on the loop thread, inside the drop: the question is whether the key
                        // works while the drag is still live, so it cannot wait for a later pass.
                        System.err.println("LIVE-PORTAL during-the-drag=${retrieve(connection, key)}")
                        return true
                    }
                },
            ),
    )
}

private const val PORTAL_NAMESPACE = "kortex-live-portal"
private const val BOX = 260
private const val MARGIN = 200
private const val PLACE_MILLIS = 4_000L
private const val WAIT = 300_000L
