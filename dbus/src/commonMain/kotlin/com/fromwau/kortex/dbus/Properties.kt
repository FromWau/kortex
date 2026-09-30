package com.fromwau.kortex.dbus

/**
 * Reading a value out of a property bag, which is the most common thing done with D-Bus at all.
 *
 * Every one of these looks through a variant first. The same property reaches a caller boxed out of a
 * `PropertiesChanged` or a hint dictionary and bare out of a `GetAll`, and unwrapping at each call site
 * instead is how one of them ends up not doing it and reading a present property as a missing one.
 *
 * Null throughout means "not this type, or not there", which for a property bag is one thing: the sender
 * did not give you what you asked for, and a caller's answer to both is its default.
 */
public val DBusValue.asText: String? get() = (unwrapped as? DBusValue.Text)?.value

public val DBusValue.asObjectPath: String? get() = (unwrapped as? DBusValue.ObjectPath)?.value

public val DBusValue.asBoolean: Boolean? get() = (unwrapped as? DBusValue.Bool)?.value

public val DBusValue.asByte: Byte? get() = (unwrapped as? DBusValue.U8)?.value

public val DBusValue.asInt32: Int? get() = (unwrapped as? DBusValue.I32)?.value

public val DBusValue.asUInt32: UInt? get() = (unwrapped as? DBusValue.U32)?.value

/** An `ay`, which is how every icon and image on the bus arrives. */
public val DBusValue.asBytes: ByteArray? get() = (unwrapped as? DBusValue.Bytes)?.value

/** A struct's fields, in order. */
public val DBusValue.asFields: List<DBusValue>? get() = (unwrapped as? DBusValue.Struct)?.fields

/** An array's elements. */
public val DBusValue.asItems: List<DBusValue>? get() = (unwrapped as? DBusValue.Sequence)?.values

/**
 * An `a{sv}` as a map, with every value unwrapped.
 *
 * Null where the value is not an array of pairs at all. An entry whose key is not a string is dropped
 * rather than failing the lot, because the bag is worth having without it.
 */
public val DBusValue.asDictionary: Map<String, DBusValue>?
    get() = asItems?.filterIsInstance<DBusValue.Pair>()?.associate { entry ->
        entry.key.asText.orEmpty() to entry.value.unwrapped
    }

/** The string under [key], or null where the sender left it out or sent something else. */
public fun Map<String, DBusValue>.text(key: String): String? = this[key]?.asText

/**
 * The flag under [key], or [default] where the sender left it out.
 *
 * The default is a parameter because it is the thing that differs between interfaces: a tray item's
 * `ItemIsMenu` is false when absent, and a menu entry's `enabled` and `visible` are true.
 */
public fun Map<String, DBusValue>.flag(key: String, default: Boolean = false): Boolean =
    this[key]?.asBoolean ?: default
