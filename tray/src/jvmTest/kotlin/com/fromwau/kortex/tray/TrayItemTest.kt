package com.fromwau.kortex.tray

import com.fromwau.kern.result.getOrElse
import com.fromwau.kortex.dbus.DBusType
import com.fromwau.kortex.dbus.DBusValue
import com.fromwau.kortex.dbus.Message
import com.fromwau.kortex.dbus.decode
import com.fromwau.kortex.dbus.unwrapped
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** An item built out of what a real one answered, and the addresses watchers really hand back. */
class TrayItemTest {
    /**
     * The same `GetAll` reply `:dbus` pins, decoded the rest of the way into an item.
     *
     * Captured off kdeconnect-indicator, so every expectation here is what `busctl` reports for that item
     * rather than something this code and its test agreed on between themselves.
     */
    @Test
    fun `a captured GetAll becomes the item the tray shows`() {
        val address = ItemAddress(":1.31", "/StatusNotifierItem")

        val item = trayItemFrom(address, capturedProperties())

        assertEquals(address, item.address)
        assertEquals("KDE Connect Indicator", item.id)
        assertEquals("KDE Connect Indicator", item.title)
        assertEquals(TrayCategory.Communications, item.category)
        assertEquals(TrayStatus.Passive, item.status)
        assertEquals(TrayIcon(name = "kdeconnectindicatordark"), item.icon)
        assertEquals("/MenuBar", item.menuPath)
        assertEquals(false, item.isMenu)
    }

    @Test
    fun `a tooltip with something to say survives, and its empty icon does not become one`() {
        val tooltip = assertIs<TrayToolTip>(trayItemFrom(ADDRESS, capturedProperties()).toolTip)

        assertEquals("KDE Connect", tooltip.title)
        assertEquals("0 devices connected", tooltip.description)
        assertEquals(TrayIcon(name = "kdeconnect"), tooltip.icon)
        assertTrue(tooltip.icon.pixmaps.isEmpty(), "the captured tooltip carries no pixels")
    }

    /** The captured item names its icons by theme, so all three pixmap properties are empty. */
    @Test
    fun `an icon that is only a theme name carries no pixels and is not empty`() {
        val item = trayItemFrom(ADDRESS, capturedProperties())

        assertTrue(item.icon.pixmaps.isEmpty())
        assertEquals(false, item.icon.isEmpty, "an icon with a name is not an empty icon")
        assertEquals(TrayIcon(), item.overlayIcon)
        assertEquals(TrayIcon(), item.attentionIcon)
        assertTrue(item.overlayIcon.isEmpty)
    }

    @Test
    fun `an item that answered nothing takes every default rather than failing`() {
        val item = trayItemFrom(ADDRESS, emptyMap())

        assertEquals("", item.id)
        assertEquals("", item.title)
        assertEquals(TrayCategory.ApplicationStatus, item.category, "the specification's own default")
        assertEquals(TrayStatus.Active, item.status)
        assertEquals(TrayIcon(), item.icon)
        assertNull(item.toolTip)
        assertNull(item.menuPath)
    }

    /** Pixels arrive as an `a(iiay)` and stay bytes, because turning them into a bitmap needs a toolkit. */
    @Test
    fun `a pixmap keeps its size and its bytes exactly`() {
        val pixels = byteArrayOf(0xFF.toByte(), 1, 2, 3, 0x80.toByte(), 4, 5, 6)
        val properties = mapOf(
            "IconPixmap" to DBusValue.Sequence(
                ICON_STRUCT,
                listOf(
                    DBusValue.Struct(listOf(DBusValue.I32(2), DBusValue.I32(1), DBusValue.Bytes(pixels))),
                ),
            ),
        )

        val icon = trayItemFrom(ADDRESS, properties).icon

        assertEquals(listOf(TrayImage(2, 1, pixels)), icon.pixmaps)
        assertNull(icon.name, "no name was given, and an empty string is not a name")
    }

    @Test
    fun `a watcher naming a connection and a path gives both`() {
        assertEquals(
            ItemAddress(":1.31", "/StatusNotifierItem"),
            ItemAddress.parse(":1.31/StatusNotifierItem"),
        )
        assertEquals(
            ItemAddress("org.kde.StatusNotifierItem-2362-1", "/StatusNotifierItem"),
            ItemAddress.parse("org.kde.StatusNotifierItem-2362-1/StatusNotifierItem"),
        )
    }

    /** The specification has the watcher store a bus name, and some watchers store exactly that. */
    @Test
    fun `a watcher naming only a connection gives the path every item exports itself at`() {
        assertEquals(ItemAddress(":1.31", "/StatusNotifierItem"), ItemAddress.parse(":1.31"))
    }

    /** An application that registered a path leaves the watcher holding one, and only the sender says who. */
    @Test
    fun `a watcher naming only a path needs the sender to say whose it is`() {
        assertEquals(
            ItemAddress(":1.44", "/org/ayatana/NotificationItem/app"),
            ItemAddress.parse("/org/ayatana/NotificationItem/app", sender = ":1.44"),
        )
        assertNull(
            ItemAddress.parse("/org/ayatana/NotificationItem/app"),
            "a path with nobody to attribute it to names no item",
        )
    }

    @Test
    fun `an entry that is not an address at all gives nothing`() {
        assertNull(ItemAddress.parse(""))
        assertNull(ItemAddress.parse("   "))
    }

    @Test
    fun `an address spells itself back the way a watcher hands it over`() {
        assertEquals(":1.31/StatusNotifierItem", ItemAddress(":1.31", "/StatusNotifierItem").toString())
    }

    @Test
    fun `a category or status spelled in a way this does not know takes a documented default`() {
        assertEquals(TrayCategory.Hardware, TrayCategory.fromWire("Hardware"))
        assertEquals(TrayCategory.ApplicationStatus, TrayCategory.fromWire("SomethingElse"))
        assertEquals(TrayCategory.ApplicationStatus, TrayCategory.fromWire(null))

        assertEquals(TrayStatus.NeedsAttention, TrayStatus.fromWire("NeedsAttention"))
        assertEquals(
            TrayStatus.Active,
            TrayStatus.fromWire("SomethingElse"),
            "showing an item whose status was not understood beats hiding it",
        )
    }

    private fun capturedProperties(): Map<String, DBusValue> {
        val hex = checkNotNull(javaClass.getResourceAsStream("/reply-getall.hex")) { "the capture is missing" }
            .use { it.readBytes() }
            .decodeToString()
            .trim()
        val raw = ByteArray(hex.length / 2) { at -> hex.substring(at * 2, at * 2 + 2).toInt(radix = 16).toByte() }
        val reply = Message.decode(raw).getOrElse { fail("the capture did not decode: $it") }
        return assertIs<DBusValue.Sequence>(reply.body.single())
            .values
            .map { assertIs<DBusValue.Pair>(it) }
            .associate { entry -> assertIs<DBusValue.Text>(entry.key).value to entry.value.unwrapped }
    }

    private companion object {
        val ADDRESS = ItemAddress(":1.31", "/StatusNotifierItem")

        val ICON_STRUCT = DBusType.Struct(
            listOf(DBusType.Basic.Int32, DBusType.Basic.Int32, DBusType.Sequence(DBusType.Basic.Byte)),
        )
    }
}
