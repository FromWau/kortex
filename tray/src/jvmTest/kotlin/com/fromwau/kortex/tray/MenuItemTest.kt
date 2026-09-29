package com.fromwau.kortex.tray

import com.fromwau.kern.result.getOrElse
import com.fromwau.kortex.dbus.DBusType
import com.fromwau.kortex.dbus.DBusValue
import com.fromwau.kortex.dbus.Message
import com.fromwau.kortex.dbus.decode
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** A menu entry out of what an application really answered, and out of every property it may set. */
class MenuItemTest {
    /**
     * A `GetLayout` reply captured off the running session bus, decoded into entries.
     *
     * `u(ia{sv}av)` nests into itself through the variants in that `av`, which is the only recursive type
     * either module handles. The bytes were taken straight off the socket by a separate client, not
     * re-encoded by kortex, and the expected values were read by a separate parser again, so nothing here
     * agrees with itself.
     */
    @Test
    fun `a captured GetLayout decodes to the entries the application exports`() {
        val root = assertNotNull(menuItemFrom(capturedLayout()), "the captured layout is not a menu")

        assertEquals(0, root.id)
        assertTrue(root.hasSubmenu, "the root said children-display=submenu")
        assertEquals(1, root.children.size)

        val only = root.children.single()
        assertEquals(49, only.id)
        assertEquals("Open app", only.label)
        assertTrue(only.enabled, "an entry that does not mention enabled is enabled")
        assertTrue(only.visible)
        assertFalseSeparator(only)
        assertNull(only.toggle)
        assertEquals(MenuDisposition.Normal, only.disposition)
        assertTrue(only.children.isEmpty())
    }

    /** Both default to true when absent, which is the opposite of how a missing flag usually reads. */
    @Test
    fun `an entry that mentions neither enabled nor visible is both`() {
        val item = assertNotNull(menuItemFrom(node(1, emptyMap())))

        assertTrue(item.enabled)
        assertTrue(item.visible)
    }

    @Test
    fun `an entry that says it is disabled or hidden is taken at its word`() {
        val item = assertNotNull(
            menuItemFrom(node(1, mapOf("enabled" to DBusValue.Bool(false), "visible" to DBusValue.Bool(false)))),
        )

        assertTrue(!item.enabled)
        assertTrue(!item.visible)
    }

    @Test
    fun `a separator is marked as one and carries no label`() {
        val item = assertNotNull(menuItemFrom(node(2, mapOf("type" to DBusValue.Text("separator")))))

        assertTrue(item.isSeparator)
        assertEquals("", item.label)
    }

    @Test
    fun `an entry of the default type is not a separator`() {
        assertFalseSeparator(assertNotNull(menuItemFrom(node(3, mapOf("type" to DBusValue.Text("standard"))))))
        assertFalseSeparator(assertNotNull(menuItemFrom(node(3, emptyMap()))))
    }

    @Test
    fun `a toggle carries what it draws and what it is set to`() {
        assertEquals(
            MenuToggle(MenuToggleKind.Checkmark, MenuToggleState.On),
            toggleOf("checkmark", state = 1),
        )
        assertEquals(
            MenuToggle(MenuToggleKind.Radio, MenuToggleState.Off),
            toggleOf("radio", state = 0),
        )
        assertEquals(
            MenuToggle(MenuToggleKind.Checkmark, MenuToggleState.Indeterminate),
            toggleOf("checkmark", state = -1),
            "the specification's own value for a toggle that will not say",
        )
    }

    @Test
    fun `an entry that does not toggle carries no toggle at all`() {
        assertNull(assertNotNull(menuItemFrom(node(4, emptyMap()))).toggle)
        assertNull(
            assertNotNull(menuItemFrom(node(4, mapOf("toggle-type" to DBusValue.Text(""))))).toggle,
            "an empty toggle-type is how an application says it does not toggle",
        )
    }

    /** An entry's icon is an encoded file, unlike a tray icon, and arrives in either of two shapes. */
    @Test
    fun `icon data arrives as bytes or as the same bytes base64 encoded`() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

        val asBytes = assertNotNull(menuItemFrom(node(5, mapOf("icon-data" to DBusValue.Bytes(png)))))
        val asText = assertNotNull(
            menuItemFrom(node(5, mapOf("icon-data" to DBusValue.Text(Base64.getEncoder().encodeToString(png))))),
        )

        assertContentEquals(png, asBytes.icon.data)
        assertContentEquals(png, asText.icon.data, "libdbusmenu's own helper writes it base64 into a string")
        assertEquals(asBytes.icon, asText.icon)
    }

    @Test
    fun `an icon that is neither a name nor data is empty`() {
        assertTrue(assertNotNull(menuItemFrom(node(6, emptyMap()))).icon.isEmpty)
        assertTrue(
            assertNotNull(menuItemFrom(node(6, mapOf("icon-data" to DBusValue.Text("not base64 at all !!"))))).icon
                .isEmpty,
            "text that does not decode is no icon rather than an empty one",
        )
    }

    @Test
    fun `a shortcut is a list of key combinations, modifiers first`() {
        val shortcut = DBusValue.Sequence(
            DBusType.Sequence(DBusType.Basic.Text),
            listOf(
                DBusValue.Sequence(
                    DBusType.Basic.Text,
                    listOf(DBusValue.Text("Control"), DBusValue.Text("S")),
                ),
            ),
        )

        val item = assertNotNull(menuItemFrom(node(7, mapOf("shortcut" to shortcut))))

        assertEquals(listOf(listOf("Control", "S")), item.shortcuts)
    }

    @Test
    fun `every disposition the specification names is recognised, and anything else reads as normal`() {
        assertEquals(MenuDisposition.Informative, dispositionOf("informative"))
        assertEquals(MenuDisposition.Warning, dispositionOf("warning"))
        assertEquals(MenuDisposition.Alert, dispositionOf("alert"))
        assertEquals(MenuDisposition.Normal, dispositionOf("normal"))
        assertEquals(MenuDisposition.Normal, dispositionOf("something an application invented"))
    }

    /** An application may promise a submenu before it has built one, which is what AboutToShow is for. */
    @Test
    fun `an entry can say it has a submenu while carrying none of it yet`() {
        val item = assertNotNull(
            menuItemFrom(node(8, mapOf("children-display" to DBusValue.Text("submenu")))),
        )

        assertTrue(item.hasSubmenu)
        assertTrue(item.children.isEmpty(), "the entries are not here yet, and the two are separate facts")
    }

    /**
     * Nesting stops rather than overflowing the stack.
     *
     * An application is free to send a menu deeper than the parser, and that failure cannot be handed back
     * as a value, so it is the one refusal here that a caller cannot lift.
     */
    @Test
    fun `a menu nested deeper than the parser stops instead of crashing`() {
        var deepest = node(9_999, emptyMap())
        repeat(200) { level -> deepest = node(level, emptyMap(), children = listOf(deepest)) }

        val root = assertNotNull(menuItemFrom(deepest), "a deep menu gave nothing at all")

        var depth = 1
        var walk = root
        while (walk.children.isNotEmpty()) {
            walk = walk.children.single()
            depth++
        }
        assertTrue(depth in 2..64, "parsing ran to depth $depth, which is neither bounded nor shallow")
    }

    @Test
    fun `something that is not a menu entry gives nothing rather than a broken one`() {
        assertNull(menuItemFrom(DBusValue.Text("not a menu")))
        assertNull(menuItemFrom(DBusValue.Struct(listOf(DBusValue.I32(1)))), "a struct of the wrong width")
    }

    private fun assertFalseSeparator(item: MenuItem) =
        assertTrue(!item.isSeparator, "an entry of the default type read as a separator")

    private fun toggleOf(kind: String, state: Int): MenuToggle? = assertNotNull(
        menuItemFrom(
            node(
                10,
                mapOf("toggle-type" to DBusValue.Text(kind), "toggle-state" to DBusValue.I32(state)),
            ),
        ),
    ).toggle

    private fun dispositionOf(raw: String): MenuDisposition =
        assertNotNull(menuItemFrom(node(11, mapOf("disposition" to DBusValue.Text(raw))))).disposition

    /** One `(ia{sv}av)`, built the way an application would send it. */
    private fun node(
        id: Int,
        properties: Map<String, DBusValue>,
        children: List<DBusValue> = emptyList(),
    ): DBusValue = DBusValue.Struct(
        listOf(
            DBusValue.I32(id),
            DBusValue.Sequence(
                DBusType.Pair(DBusType.Basic.Text, DBusType.Variant),
                properties.map { (key, value) -> DBusValue.Pair(DBusValue.Text(key), DBusValue.Variant(value)) },
            ),
            DBusValue.Sequence(DBusType.Variant, children.map(DBusValue::Variant)),
        ),
    )

    private fun capturedLayout(): DBusValue {
        val hex = checkNotNull(javaClass.getResourceAsStream("/reply-getlayout.hex")) { "the capture is missing" }
            .use { it.readBytes() }
            .decodeToString()
            .trim()
        val raw = ByteArray(hex.length / 2) { at -> hex.substring(at * 2, at * 2 + 2).toInt(radix = 16).toByte() }
        val reply = Message.decode(raw).getOrElse { fail("the capture did not decode: $it") }
        assertEquals(DBusValue.U32(58u), reply.body.first(), "the revision the capture was taken at")
        return reply.body[1]
    }
}
