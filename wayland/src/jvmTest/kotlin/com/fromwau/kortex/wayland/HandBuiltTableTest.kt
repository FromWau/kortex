package com.fromwau.kortex.wayland

import java.io.File
import java.lang.foreign.MemorySegment
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.w3c.dom.Element
import org.w3c.dom.NodeList

/**
 * Holds every `wl_interface` kortex builds by hand against the protocol XML it was transcribed from.
 *
 * libwayland exports tables for the core protocol only, so the thirteen kortex binds are written out by
 * hand and nothing had compared them to their source. [ProtocolVersionTest] cannot: `buildInterface` is
 * handed the same [WlVersion] constant that test asserts against, so the row compares the constant with
 * itself.
 *
 * What drift costs is not symmetric. A request at the wrong opcode is quiet until it is sent, and is then
 * sent as whatever the compositor reads at that opcode. An event is worse: `queue_event`
 * (src/wayland-client.c) indexes the event table by the opcode that arrived, so an interface bound past
 * what its table describes answers the first event it cannot find by killing the connection.
 *
 * Reads the XML back out of the packages that ship it rather than a copy kept here, because a copy would
 * drift the same way the tables can.
 */
class HandBuiltTableTest {
    @Test
    fun `every hand-built table carries the requests and events its protocol declares`() {
        PROTOCOLS.forEach { protocol ->
            protocol.tables.forEach { iface ->
                val name = nameOf(iface)
                val declared = protocol.declares(name)

                assertEquals(
                    declared.requests, LibWayland.interfaceRequests(iface),
                    "$name's request table is not the one ${protocol.xml} declares",
                )
                assertEquals(
                    declared.events, LibWayland.interfaceEvents(iface),
                    "$name's event table is not the one ${protocol.xml} declares",
                )
            }
        }
    }

    /**
     * The other half: a table can be a faithful copy and still be bound too high. The version is the one
     * field `buildInterface` is handed rather than one it derives, so the comparison above cannot see it.
     */
    @Test
    fun `no hand-built interface is asked for above the version its protocol declares`() {
        PROTOCOLS.forEach { protocol ->
            protocol.tables.forEach { iface ->
                val name = nameOf(iface)
                val declared = protocol.declares(name).version

                assertTrue(
                    protocol.asked <= declared,
                    "$name is bound at v${protocol.asked}, past the v$declared ${protocol.xml} declares",
                )
            }
        }
    }

    /** One protocol kortex writes `wl_interface` tables for by hand, and where its own XML can be read. */
    private data class HandBuilt(
        val source: ProtocolPackage,
        /** The XML's path under [source]'s own data directory. */
        val xml: String,
        /** The [WlVersion] constant every table here was built at, which is the version kortex binds at. */
        val asked: Int,
        val tables: List<MemorySegment>,
    ) {
        fun declares(interfaceName: String): Declared = declaredIn(File(source.dataDir, xml), interfaceName)
    }

    /** A package shipping protocol XML, named as `pkg-config` knows it. */
    private enum class ProtocolPackage(private val pkgConfig: String) {
        Wlr("wlr-protocols"),
        Wayland("wayland-protocols"),
        ;

        val dataDir: File by lazy {
            val process = ProcessBuilder("pkg-config", "--variable=pkgdatadir", pkgConfig)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            val exit = process.waitFor()
            check(exit == 0) { "pkg-config could not locate $pkgConfig, which ships the XML here: $output" }
            File(output)
        }
    }

    /** What one `<interface>` of a protocol XML declares, in the order it declares it. */
    private data class Declared(
        val version: Int,
        val requests: List<WlMessageHeader>,
        val events: List<WlMessageHeader>,
    )

    private companion object {
        val PROTOCOLS = listOf(
            HandBuilt(
                ProtocolPackage.Wlr, "unstable/wlr-layer-shell-unstable-v1.xml", WlVersion.LAYER_SHELL,
                tables = listOf(
                    LayerShellProtocol.layerShellInterface,
                    LayerShellProtocol.layerSurfaceInterface,
                ),
            ),
            HandBuilt(
                ProtocolPackage.Wlr, "unstable/wlr-virtual-pointer-unstable-v1.xml", WlVersion.VIRTUAL_POINTER,
                tables = listOf(
                    VirtualPointerProtocol.virtualPointerManagerInterface,
                    VirtualPointerProtocol.virtualPointerInterface,
                ),
            ),
            HandBuilt(
                ProtocolPackage.Wayland, "stable/xdg-shell/xdg-shell.xml", WlVersion.XDG_SHELL,
                tables = listOf(
                    XdgShellProtocol.xdgWmBaseInterface,
                    XdgShellProtocol.xdgSurfaceInterface,
                    XdgShellProtocol.xdgToplevelInterface,
                    XdgShellProtocol.xdgPopupInterface,
                    XdgShellProtocol.xdgPositionerInterface,
                ),
            ),
            HandBuilt(
                ProtocolPackage.Wayland, "unstable/xdg-output/xdg-output-unstable-v1.xml", WlVersion.XDG_OUTPUT,
                tables = listOf(
                    XdgOutputProtocol.xdgOutputManagerInterface,
                    XdgOutputProtocol.xdgOutputInterface,
                ),
            ),
            HandBuilt(
                ProtocolPackage.Wayland,
                "unstable/xdg-decoration/xdg-decoration-unstable-v1.xml",
                WlVersion.XDG_DECORATION,
                tables = listOf(
                    XdgDecorationProtocol.decorationManagerInterface,
                    XdgDecorationProtocol.toplevelDecorationInterface,
                ),
            ),
        )

        /** One character per argument type, as `wayland-scanner`'s own `get_type_char` writes them. */
        val SIGNATURE_CHARS = mapOf(
            "int" to "i",
            "uint" to "u",
            "fixed" to "f",
            "string" to "s",
            "object" to "o",
            "new_id" to "n",
            "array" to "a",
            "fd" to "h",
        )

        /** The version a message with no `since` of its own arrives in, which the signature leaves off. */
        const val FIRST_VERSION = "1"

        /** The name in the table itself, which is the one `wl_registry_bind` quotes. */
        fun nameOf(iface: MemorySegment): String =
            LibWayland.interfaceName(iface).reinterpret(Long.MAX_VALUE).getString(0)

        fun declaredIn(file: File, interfaceName: String): Declared {
            assertTrue(file.isFile, "$file is missing, so nothing independent describes $interfaceName")
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val iface = assertNotNull(
                document.getElementsByTagName("interface").elements()
                    .firstOrNull { it.getAttribute("name") == interfaceName },
                "${file.name} declares no interface named $interfaceName",
            )

            return Declared(
                version = iface.getAttribute("version").toInt(),
                requests = iface.messages("request"),
                events = iface.messages("event"),
            )
        }

        fun Element.messages(tag: String): List<WlMessageHeader> =
            getElementsByTagName(tag)
                .elements()
                .map { message -> WlMessageHeader(message.getAttribute("name"), message.signature()) }

        /**
         * The signature `wayland-scanner` writes into a `wl_message`: the version the message arrives in
         * when that is past the first, then one character per argument, each behind a `?` where the
         * protocol allows a null.
         */
        fun Element.signature(): String {
            val since = getAttribute("since").takeUnless { it.isEmpty() || it == FIRST_VERSION }.orEmpty()
            return getElementsByTagName("arg")
                .elements()
                .joinToString(separator = "", prefix = since) { arg ->
                    val type = arg.getAttribute("type")
                    val char = assertNotNull(SIGNATURE_CHARS[type], "no signature character for an arg of type $type")
                    if (arg.getAttribute("allow-null") == "true") "?$char" else char
                }
        }

        fun NodeList.elements(): List<Element> = (0 until length).mapNotNull { item(it) as? Element }
    }
}
