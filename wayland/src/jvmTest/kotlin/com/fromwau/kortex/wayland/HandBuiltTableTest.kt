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
 * libwayland exports tables for the core protocol only, so the thirteen below were written out by hand and
 * nothing since has compared them to their source. [ProtocolVersionTest] cannot: `buildInterface` is handed
 * the same [WlVersion] constant that test asserts against, so the row compares the constant with itself.
 *
 * What a drifted entry costs is not symmetric. A request at the wrong opcode is quiet until it is sent, and
 * then it is sent as whatever the compositor reads at that opcode, with `marshal` taking the since it skips
 * on out of the same wrong entry. An event is worse: `queue_event` (src/wayland-client.c) indexes the event
 * table by the opcode that arrived, and an interface bound past what its table describes answers the first
 * event it has no entry for by killing the connection.
 *
 * Reads the XML back out of the packages that ship it rather than a copy kept here, because a copy would
 * drift the same way the tables can.
 */
class HandBuiltTableTest {
    @Test
    fun `every hand-built table carries the requests and events its protocol declares`() {
        TABLES.forEach { table ->
            val name = table.name()
            val declared = table.declared(name)

            assertEquals(
                declared.requests, LibWayland.interfaceRequests(table.iface),
                "$name's request table is not the one ${table.xml} declares",
            )
            assertEquals(
                declared.events, LibWayland.interfaceEvents(table.iface),
                "$name's event table is not the one ${table.xml} declares",
            )
        }
    }

    /**
     * The other half: a table can be a faithful copy and still be bound too high. Binding above what the
     * protocol declares is the case the table comparison cannot see, since the version is the one field
     * `buildInterface` is handed rather than one it derives.
     */
    @Test
    fun `no hand-built interface is asked for above the version its protocol declares`() {
        TABLES.forEach { table ->
            val name = table.name()
            val declared = table.declared(name).version

            assertTrue(
                table.asked <= declared,
                "$name is bound at v${table.asked}, past the v$declared ${table.xml} declares",
            )
        }
    }

    /** One `wl_interface` kortex builds itself, and where the protocol that defines it can be read. */
    private data class HandBuilt(
        val iface: MemorySegment,
        val source: ProtocolPackage,
        /** The file's path under [source]'s own data directory. */
        val xml: String,
        /** The [WlVersion] constant the table was built at, which is the version kortex binds it at. */
        val asked: Int,
    ) {
        /** The name in the table itself, which is the one `wl_registry_bind` quotes. */
        fun name(): String =
            LibWayland.interfaceName(iface).reinterpret(Long.MAX_VALUE).getString(0)

        fun declared(name: String): Declared = declaredIn(File(source.dataDir, xml), name)
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
        val TABLES = listOf(
            HandBuilt(
                LayerShellProtocol.layerShellInterface,
                ProtocolPackage.Wlr, LAYER_SHELL_XML, WlVersion.LAYER_SHELL,
            ),
            HandBuilt(
                LayerShellProtocol.layerSurfaceInterface,
                ProtocolPackage.Wlr, LAYER_SHELL_XML, WlVersion.LAYER_SHELL,
            ),
            HandBuilt(
                VirtualPointerProtocol.virtualPointerManagerInterface,
                ProtocolPackage.Wlr, VIRTUAL_POINTER_XML, WlVersion.VIRTUAL_POINTER,
            ),
            HandBuilt(
                VirtualPointerProtocol.virtualPointerInterface,
                ProtocolPackage.Wlr, VIRTUAL_POINTER_XML, WlVersion.VIRTUAL_POINTER,
            ),
            HandBuilt(XdgShellProtocol.xdgWmBaseInterface, ProtocolPackage.Wayland, XDG_SHELL_XML, WlVersion.XDG_SHELL),
            HandBuilt(XdgShellProtocol.xdgSurfaceInterface, ProtocolPackage.Wayland, XDG_SHELL_XML, WlVersion.XDG_SHELL),
            HandBuilt(
                XdgShellProtocol.xdgToplevelInterface,
                ProtocolPackage.Wayland, XDG_SHELL_XML, WlVersion.XDG_SHELL,
            ),
            HandBuilt(XdgShellProtocol.xdgPopupInterface, ProtocolPackage.Wayland, XDG_SHELL_XML, WlVersion.XDG_SHELL),
            HandBuilt(
                XdgShellProtocol.xdgPositionerInterface,
                ProtocolPackage.Wayland, XDG_SHELL_XML, WlVersion.XDG_SHELL,
            ),
            HandBuilt(
                XdgOutputProtocol.xdgOutputManagerInterface,
                ProtocolPackage.Wayland, XDG_OUTPUT_XML, WlVersion.XDG_OUTPUT,
            ),
            HandBuilt(
                XdgOutputProtocol.xdgOutputInterface,
                ProtocolPackage.Wayland, XDG_OUTPUT_XML, WlVersion.XDG_OUTPUT,
            ),
            HandBuilt(
                XdgDecorationProtocol.decorationManagerInterface,
                ProtocolPackage.Wayland, XDG_DECORATION_XML, WlVersion.XDG_DECORATION,
            ),
            HandBuilt(
                XdgDecorationProtocol.toplevelDecorationInterface,
                ProtocolPackage.Wayland, XDG_DECORATION_XML, WlVersion.XDG_DECORATION,
            ),
        )

        const val LAYER_SHELL_XML = "unstable/wlr-layer-shell-unstable-v1.xml"
        const val VIRTUAL_POINTER_XML = "unstable/wlr-virtual-pointer-unstable-v1.xml"
        const val XDG_SHELL_XML = "stable/xdg-shell/xdg-shell.xml"
        const val XDG_OUTPUT_XML = "unstable/xdg-output/xdg-output-unstable-v1.xml"
        const val XDG_DECORATION_XML = "unstable/xdg-decoration/xdg-decoration-unstable-v1.xml"

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
