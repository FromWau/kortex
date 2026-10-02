package com.fromwau.kortex.bar.desktop

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.getOrElse
import com.fromwau.kern.result.getOrNull
import com.fromwau.kortex.dbus.DBusConnection
import com.fromwau.kortex.notification.NotificationError
import com.fromwau.kortex.notification.NotificationServer
import com.fromwau.kortex.notification.ServerInformation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.runBlocking

/**
 * What a shell learns when the notification name is already somebody else's.
 *
 * This is the only half of `:notification` that can be exercised on a desktop that is being used, and it
 * is exercised the only way that is safe: the name's owner is read first, and [NotificationServer.start]
 * is called **only** when somebody already holds it, so this test cannot become the notification server
 * by accident on a session where no daemon is running. If it somehow does, it gives the name straight
 * back and asserts nothing.
 *
 * Taking the name for real is the user's decision, because it means stopping their notification daemon.
 */
class NotificationNameTest {
    @Test
    fun `a name somebody else holds is refused, and the refusal names the process rather than the bus name`() =
        runBlocking {
            val bus = DBusConnection.session().getOrElse { error ->
                fail("this test reads the live session bus, and there is none: $error")
            }

            bus.use { connection ->
                val owner = connection.nameOwner(NotificationServer.INTERFACE).getOrNull()
                if (owner == null) {
                    println("nothing holds ${NotificationServer.INTERFACE}; not asking for it, by instruction")
                    return@runBlocking
                }

                when (val outcome = NotificationServer.start(connection, IDENTITY)) {
                    is Ok -> {
                        outcome.value.stop()
                        println("the name was free after all; released it again and asserted nothing")
                    }

                    is Err -> {
                        val taken = assertIs<NotificationError.AlreadyServed>(outcome.error)

                        println("refused: owner=${taken.owner} pid=${taken.pid} process=${taken.process}")

                        assertEquals(owner, taken.owner, "the refusal names whoever the bus says owns it")
                        assertTrue(taken.owner.startsWith(':'), "an owner is a unique name, not a well-known one")
                        assertTrue(
                            taken.pid == null || (taken.process?.isNotBlank() == true),
                            "a pid that was resolvable should have carried a process name with it",
                        )
                    }
                }
            }
        }
}

private val IDENTITY = ServerInformation(name = "kortex-bar-qa-test", vendor = "fromwau", version = "0.1.0")
