package com.fromwau.kortex.tray

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.getOrElse
import com.fromwau.kortex.dbus.Bus
import com.fromwau.kortex.dbus.CallRejected
import com.fromwau.kortex.dbus.DBusConnection
import com.fromwau.kortex.dbus.DBusType
import com.fromwau.kortex.dbus.DBusValue
import com.fromwau.kortex.dbus.asText
import kotlin.test.fail

/** Registers [entry] with whoever holds the watcher name, the call an application makes. */
internal suspend fun DBusConnection.register(entry: String) {
    call(
        destination = KDE_WATCHER.service,
        path = WATCHER_PATH,
        iface = KDE_WATCHER.iface,
        member = REGISTER_ITEM,
        args = listOf(DBusValue.Text(entry)),
    ).getOrElse { fail("the watcher refused a registration: $it") }
}

/**
 * Enough of an item for a host to accept it: a non-empty property map on KDE's interface.
 *
 * A host drops an address whose peer answers nothing, so without this the registry would carry the
 * item and the tray would still be empty, and the test would be measuring the wrong thing.
 */
internal fun DBusConnection.exportFakeItem() {
    export(ItemAddress.DEFAULT_PATH) { call ->
        when {
            call.iface == Bus.PROPERTIES && call.member == "GetAll" &&
                call.body.firstOrNull()?.asText == "org.kde.StatusNotifierItem" -> Ok(listOf(properties()))

            else -> Err(CallRejected.unknownMethod(call))
        }
    }
}

private fun properties(): DBusValue = DBusValue.Sequence(
    DBusType.Pair(DBusType.Basic.Text, DBusType.Variant),
    listOf(
        DBusValue.Pair(DBusValue.Text("Id"), DBusValue.Variant(DBusValue.Text("probe"))),
        DBusValue.Pair(DBusValue.Text("Title"), DBusValue.Variant(DBusValue.Text("Probe"))),
        DBusValue.Pair(DBusValue.Text("Status"), DBusValue.Variant(DBusValue.Text("Active"))),
        DBusValue.Pair(
            DBusValue.Text("Category"),
            DBusValue.Variant(DBusValue.Text("ApplicationStatus")),
        ),
    ),
)

/** A menu at [path] whose root holds one entry, labelled [label], which is all a test needs to tell two apart. */
internal fun DBusConnection.exportFakeMenu(path: String, label: String) {
    export(path) { call ->
        when {
            call.iface == DBUSMENU && call.member == "GetLayout" -> Ok(
                listOf(
                    DBusValue.U32(1u),
                    menuNode(
                        id = 0,
                        properties = mapOf("children-display" to DBusValue.Text("submenu")),
                        children = listOf(menuNode(1, mapOf("label" to DBusValue.Text(label)))),
                    ),
                ),
            )

            else -> Err(CallRejected.unknownMethod(call))
        }
    }
}

/** One `(ia{sv}av)`, built the way an application would send it. */
internal fun menuNode(
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

private const val DBUSMENU = "com.canonical.dbusmenu"
