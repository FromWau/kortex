package com.fromwau.kortex.dbus

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/** Where the bus is, out of the environment and out of the address grammar. */
class BusAddressTest {
    @Test
    fun `a unix path address gives that path`() {
        assertEquals(Ok(BusAddress("/run/user/1000/bus")), BusAddress.parse("unix:path=/run/user/1000/bus"))
    }

    @Test
    fun `a guid beside the path is ignored rather than taken for part of it`() {
        assertEquals(
            Ok(BusAddress("/run/user/1000/bus")),
            BusAddress.parse("unix:path=/run/user/1000/bus,guid=c69f2db0b466a8465e37730f6ee09457"),
        )
    }

    /** The grammar percent-encodes anything outside a small set, and a path with a space is why. */
    @Test
    fun `a percent-encoded path is decoded`() {
        assertEquals(Ok(BusAddress("/tmp/a bus")), BusAddress.parse("unix:path=/tmp/a%20bus"))
    }

    /**
     * The first address a JVM can reach wins, which is what a client does with a list.
     *
     * An abstract socket is in the list precisely because it is the one a Linux desktop still offers and
     * the JVM cannot open, having no way to write the leading NUL the abstract namespace needs.
     */
    @Test
    fun `a list falls past an address the JVM cannot open to one it can`() {
        assertEquals(
            Ok(BusAddress("/run/user/1000/bus")),
            BusAddress.parse("unix:abstract=/tmp/dbus-AbCdEf;unix:path=/run/user/1000/bus"),
        )
    }

    @Test
    fun `an address with nothing reachable in it is refused`() {
        val abstractOnly = "unix:abstract=/tmp/dbus-AbCdEf"

        assertEquals(Err(DBusError.UnreachableBusAddress(abstractOnly)), BusAddress.parse(abstractOnly))
        assertEquals(
            Err(DBusError.UnreachableBusAddress("tcp:host=localhost,port=4242")),
            BusAddress.parse("tcp:host=localhost,port=4242"),
        )
    }

    @Test
    fun `the session variable is preferred over the runtime directory`() {
        assertEquals(
            Ok(BusAddress("/from/the/variable")),
            BusAddress.fromEnvironment(
                mapOf(
                    "DBUS_SESSION_BUS_ADDRESS" to "unix:path=/from/the/variable",
                    "XDG_RUNTIME_DIR" to "/run/user/1000",
                )::get,
            ),
        )
    }

    @Test
    fun `the runtime directory is the fallback the specification names`() {
        assertEquals(
            Ok(BusAddress("/run/user/1000/bus")),
            BusAddress.fromEnvironment(mapOf("XDG_RUNTIME_DIR" to "/run/user/1000")::get),
        )
    }

    @Test
    fun `neither variable set means there is nowhere to look`() {
        assertEquals(Err(DBusError.NoSessionBus), BusAddress.fromEnvironment(emptyMap<String, String>()::get))
    }

    /**
     * `EXTERNAL` authentication is the uid and nothing else, so a wrong one reaches no bus at all.
     *
     * Checked against the owner of a file this process just made rather than against `/proc/self`, which
     * is where the reading under test comes from and would agree with itself whatever it did.
     */
    @Test
    fun `this process's uid is the one that owns what it creates`() {
        val scratch = Files.createTempFile("kortex-uid", "")

        try {
            assertEquals(Ok(Files.getAttribute(scratch, "unix:uid") as Int), currentUid())
        } finally {
            Files.deleteIfExists(scratch)
        }
    }
}
