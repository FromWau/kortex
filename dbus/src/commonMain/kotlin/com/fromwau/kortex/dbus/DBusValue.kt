package com.fromwau.kortex.dbus

/** A value as it travels on the bus, paired with the [type] that says how it is laid out. */
public sealed interface DBusValue {
    public val type: DBusType

    /** A value carrying one number or one string, and the only thing a [Pair] may key on. */
    public sealed interface Basic : DBusValue {
        override val type: DBusType.Basic
    }

    public data class U8(public val value: Byte) : Basic {
        override val type: DBusType.Basic get() = DBusType.Basic.Byte
    }

    public data class Bool(public val value: Boolean) : Basic {
        override val type: DBusType.Basic get() = DBusType.Basic.Bool
    }

    public data class I16(public val value: Short) : Basic {
        override val type: DBusType.Basic get() = DBusType.Basic.Int16
    }

    public data class U16(public val value: UShort) : Basic {
        override val type: DBusType.Basic get() = DBusType.Basic.UInt16
    }

    public data class I32(public val value: Int) : Basic {
        override val type: DBusType.Basic get() = DBusType.Basic.Int32
    }

    public data class U32(public val value: UInt) : Basic {
        override val type: DBusType.Basic get() = DBusType.Basic.UInt32
    }

    public data class I64(public val value: Long) : Basic {
        override val type: DBusType.Basic get() = DBusType.Basic.Int64
    }

    public data class U64(public val value: ULong) : Basic {
        override val type: DBusType.Basic get() = DBusType.Basic.UInt64
    }

    public data class F64(public val value: Double) : Basic {
        override val type: DBusType.Basic get() = DBusType.Basic.Float64
    }

    public data class Text(public val value: String) : Basic {
        override val type: DBusType.Basic get() = DBusType.Basic.Text
    }

    public data class ObjectPath(public val value: String) : Basic {
        override val type: DBusType.Basic get() = DBusType.Basic.ObjectPath
    }

    public data class Sig(public val types: List<DBusType>) : Basic {
        override val type: DBusType.Basic get() = DBusType.Basic.Sig
    }

    /**
     * An `ay`, held as the bytes themselves.
     *
     * The only array with its own case, because a tray icon is one and boxing a megapixel of ARGB one
     * [U8] at a time is not a thing to do. A read of an `ay` always lands here.
     */
    public class Bytes(public val value: ByteArray) : DBusValue {
        override val type: DBusType get() = DBusType.Sequence(DBusType.Basic.Byte)

        override fun equals(other: Any?): Boolean = other is Bytes && value.contentEquals(other.value)

        override fun hashCode(): Int = value.contentHashCode()

        override fun toString(): String = "Bytes(${value.size})"
    }

    /** [element] is carried because an empty sequence still has to say what it is empty of. */
    public data class Sequence(
        public val element: DBusType,
        public val values: List<DBusValue>,
    ) : DBusValue {
        override val type: DBusType get() = DBusType.Sequence(element)
    }

    public data class Struct(public val fields: List<DBusValue>) : DBusValue {
        override val type: DBusType get() = DBusType.Struct(fields.map { it.type })
    }

    public data class Pair(public val key: Basic, public val value: DBusValue) : DBusValue {
        override val type: DBusType get() = DBusType.Pair(key.type, value.type)
    }

    public data class Variant(public val value: DBusValue) : DBusValue {
        override val type: DBusType get() = DBusType.Variant
    }
}

/** What a [DBusValue.Variant] wraps, or the value itself where it is not one. */
public val DBusValue.unwrapped: DBusValue get() = if (this is DBusValue.Variant) value.unwrapped else this
