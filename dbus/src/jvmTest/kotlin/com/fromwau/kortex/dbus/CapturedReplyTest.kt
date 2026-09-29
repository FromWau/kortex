package com.fromwau.kortex.dbus

import com.fromwau.kern.result.getOrElse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.fail

/**
 * Replies captured off a running session bus decode to the values `busctl` reports for them.
 *
 * This is the only test here that can catch a wrong alignment rule. A round trip through kortex's own
 * writer and reader passes whether the rules are right or not, because both halves would be wrong the same
 * way; these bytes were laid out by dbus-daemon and by the Qt client behind kdeconnect-indicator, so a
 * misplaced boundary shows up as a garbled string or a decode that fails outright.
 *
 * The three between them cover every shape the format has: an array of strings, a struct holding an array
 * of structs holding a byte array, and a dictionary of variants of mixed type.
 */
class CapturedReplyTest {
    @Test
    fun `a property reply holding an array of strings decodes to the registered items`() {
        val items = bodyOf("reply-registered-items.hex").single().unwrapped

        assertEquals(
            DBusValue.Sequence(
                DBusType.Basic.Text,
                listOf(DBusValue.Text(":1.31/StatusNotifierItem")),
            ),
            items,
        )
    }

    /**
     * `(sa(iiay)ss)`, the one a tray host cannot avoid.
     *
     * The inner `a(iiay)` is the icon shape, and it is empty here because kdeconnect-indicator names its
     * icon by theme rather than sending pixels. An empty array still has to consume its own length and land
     * the two strings after it on the right boundary, which is what the last two fields prove.
     */
    @Test
    fun `a tooltip reply decodes to its four fields with the icon array empty`() {
        val tooltip = assertIs<DBusValue.Struct>(bodyOf("reply-tooltip.hex").single().unwrapped)

        assertEquals(
            listOf(
                DBusValue.Text("kdeconnect"),
                DBusValue.Sequence(
                    DBusType.Struct(
                        listOf(
                            DBusType.Basic.Int32,
                            DBusType.Basic.Int32,
                            DBusType.Sequence(DBusType.Basic.Byte),
                        ),
                    ),
                    emptyList(),
                ),
                DBusValue.Text("KDE Connect"),
                DBusValue.Text("0 devices connected"),
            ),
            tooltip.fields,
        )
    }

    /** Every property of the live item at once, which is the call `:tray` will actually make. */
    @Test
    fun `a GetAll reply decodes to all sixteen properties`() {
        val properties = propertiesOf("reply-getall.hex")

        assertEquals(16, properties.size, "the capture holds sixteen properties")
        assertEquals(DBusValue.Text("kdeconnectindicatordark"), properties["IconName"])
        assertEquals(DBusValue.Text("KDE Connect Indicator"), properties["Id"])
        assertEquals(DBusValue.Text("Communications"), properties["Category"])
        assertEquals(DBusValue.Text("Passive"), properties["Status"])
        assertEquals(DBusValue.ObjectPath("/MenuBar"), properties["Menu"])
        assertEquals(DBusValue.Bool(false), properties["ItemIsMenu"])
        assertEquals(DBusValue.I32(0), properties["WindowId"])
        assertEquals(DBusValue.Text(""), properties["IconThemePath"])
    }

    /**
     * A pixmap property is an `a(iiay)` whatever it holds, so the empty ones still pin the element type.
     *
     * Named separately from the value assertions above because this is the type a host has to switch on to
     * know an icon arrived as pixels rather than as a theme name.
     */
    @Test
    fun `the three pixmap properties decode as arrays of the icon struct`() {
        val properties = propertiesOf("reply-getall.hex")
        val iconStruct = DBusType.Struct(
            listOf(DBusType.Basic.Int32, DBusType.Basic.Int32, DBusType.Sequence(DBusType.Basic.Byte)),
        )

        listOf("IconPixmap", "AttentionIconPixmap", "OverlayIconPixmap").forEach { name ->
            val pixmap = assertIs<DBusValue.Sequence>(properties[name], "$name is an array")
            assertEquals(iconStruct, pixmap.element, "$name is an array of the icon struct")
        }
    }

    /**
     * The header the bus filled in, which is how a reply is matched to the call it answers.
     *
     * Both expectations were read out of the captures by a separate decoder rather than by this one, so a
     * shared mistake cannot make them agree.
     */
    @Test
    fun `a captured reply carries the serial it answers and the name that sent it`() {
        val fromItem = assertIs<Message.Return>(decode("reply-getall.hex"))
        val fromWatcher = assertIs<Message.Return>(decode("reply-registered-items.hex"))

        assertEquals(6u, fromItem.replySerial)
        assertEquals(":1.31", fromItem.sender, "kdeconnect-indicator answered GetAll")
        assertEquals(2u, fromWatcher.replySerial)
        assertEquals(":1.25", fromWatcher.sender, "the watcher answered, and it is a different connection")
    }

    private fun propertiesOf(fixture: String): Map<String, DBusValue> =
        assertIs<DBusValue.Sequence>(bodyOf(fixture).single())
            .values
            .map { assertIs<DBusValue.Pair>(it) }
            .associate { entry -> assertIs<DBusValue.Text>(entry.key).value to entry.value.unwrapped }

    private fun bodyOf(fixture: String): List<DBusValue> = decode(fixture).body

    private fun decode(fixture: String): Message {
        val hex = checkNotNull(javaClass.getResourceAsStream("/$fixture")) { "$fixture is not on the classpath" }
            .use { it.readBytes() }
            .decodeToString()
            .trim()
        val raw = ByteArray(hex.length / 2) { index ->
            hex.substring(index * 2, index * 2 + 2).toInt(radix = 16).toByte()
        }
        return Message.decode(raw).getOrElse { error -> fail("$fixture did not decode: $error") }
    }
}
