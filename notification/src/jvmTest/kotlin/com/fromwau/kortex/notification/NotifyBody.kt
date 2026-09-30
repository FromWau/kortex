package com.fromwau.kortex.notification

import com.fromwau.kortex.dbus.DBusType
import com.fromwau.kortex.dbus.DBusValue

/** One `Notify` call's arguments, in the order and types the interface takes them. */
internal fun notifyBody(
    appName: String = "a test",
    replaces: UInt = 0u,
    appIcon: String = "",
    summary: String = "a summary",
    body: String = "",
    actions: List<String> = emptyList(),
    hints: Map<String, DBusValue> = emptyMap(),
    expireMillis: Int = -1,
): List<DBusValue> = listOf(
    DBusValue.Text(appName),
    DBusValue.U32(replaces),
    DBusValue.Text(appIcon),
    DBusValue.Text(summary),
    DBusValue.Text(body),
    DBusValue.Sequence(DBusType.Basic.Text, actions.map(DBusValue::Text)),
    DBusValue.Sequence(
        DBusType.Pair(DBusType.Basic.Text, DBusType.Variant),
        hints.map { (key, value) -> DBusValue.Pair(DBusValue.Text(key), DBusValue.Variant(value)) },
    ),
    DBusValue.I32(expireMillis),
)
