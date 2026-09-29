package com.fromwau.kortex.dbus

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.getOrElse
import com.fromwau.kern.result.map

/**
 * A D-Bus type, as a signature names it.
 *
 * Every value is padded to its type's [alignment] counted from the first byte of the message it sits in, so
 * nothing can be read or written without knowing its type first.
 */
public sealed interface DBusType {
    /** The boundary a value of this type starts on, counted from the message's first byte. */
    public val alignment: Int

    /** This type as it appears inside a signature. */
    public val signature: String

    /** A type carrying one number or one string; every other type is built out of these. */
    public enum class Basic(internal val code: Char, override val alignment: Int) : DBusType {
        Byte('y', 1),
        Bool('b', 4),
        Int16('n', 2),
        UInt16('q', 2),
        Int32('i', 4),
        UInt32('u', 4),
        Int64('x', 8),
        UInt64('t', 8),
        Float64('d', 8),
        Text('s', 4),
        ObjectPath('o', 4),
        Sig('g', 1),

        /**
         * An index into the file descriptors sent beside a message.
         *
         * The descriptors themselves travel as `SCM_RIGHTS` ancillary data, which the JVM's socket channels
         * do not expose, so kortex never negotiates `UNIX_FD` and reading one is
         * [DBusError.UnixFdUnsupported] rather than an index pointing at nothing.
         */
        UnixFd('h', 4),
        ;

        override val signature: String get() = code.toString()
    }

    /** Any number of values of one element type, prefixed by the length in bytes. */
    public data class Sequence(public val element: DBusType) : DBusType {
        override val alignment: Int get() = 4
        override val signature: String get() = "a${element.signature}"
    }

    /** A fixed run of values of differing types. */
    public data class Struct(public val fields: List<DBusType>) : DBusType {
        override val alignment: Int get() = 8
        override val signature: String
            get() = fields.joinToString(separator = "", prefix = "(", postfix = ")") { it.signature }
    }

    /** One key and one value; only ever the element of a [Sequence], which is how `a{sv}` is spelled. */
    public data class Pair(public val key: Basic, public val value: DBusType) : DBusType {
        override val alignment: Int get() = 8
        override val signature: String get() = "{${key.signature}${value.signature}}"
    }

    /** A value carrying its own type, so what it holds is not fixed by the signature around it. */
    public data object Variant : DBusType {
        override val alignment: Int get() = 1
        override val signature: String get() = "v"
    }

    public companion object {
        internal val byCode: Map<Char, Basic> = Basic.entries.associateBy(Basic::code)

        /**
         * Parses a signature into the complete types it names.
         *
         * A signature holds a run of types rather than one, because that is what a message body is: `"ss"`
         * parses to two and `""` to none.
         */
        public fun parse(signature: String): Result<List<DBusType>, DBusError> {
            val cursor = SignatureCursor(signature)
            val types = mutableListOf<DBusType>()
            while (!cursor.atEnd) {
                types += cursor.next().getOrElse { return Err(it) }
            }
            return Ok(types)
        }
    }
}

/** Every type in a run, back as the signature naming them. */
public val List<DBusType>.signature: String get() = joinToString(separator = "") { it.signature }

/** Walks a signature one complete type at a time, so a nested one is never split. */
private class SignatureCursor(private val text: String) {
    private var at = 0

    val atEnd: Boolean get() = at >= text.length

    fun next(): Result<DBusType, DBusError> =
        when (val code = take() ?: return malformed()) {
            'a' -> nextSequence()
            '(' -> nextStruct()
            'v' -> Ok(DBusType.Variant)
            // Legal only directly inside an array, where nextSequence reads it; anywhere else it is a stray brace.
            '{' -> malformed()
            else -> basic(code)
        }

    private fun nextSequence(): Result<DBusType, DBusError> {
        if (peek() != '{') return next().map(DBusType::Sequence)

        take()
        val key = basic(take() ?: return malformed()).getOrElse { return Err(it) }
        val value = next().getOrElse { return Err(it) }
        if (take() != '}') return malformed()
        return Ok(DBusType.Sequence(DBusType.Pair(key, value)))
    }

    private fun nextStruct(): Result<DBusType, DBusError> {
        val fields = mutableListOf<DBusType>()
        while (peek() != ')') {
            if (atEnd) return malformed()
            fields += next().getOrElse { return Err(it) }
        }
        take()
        // The specification gives no spelling for a struct with no fields, and "()" is not one.
        if (fields.isEmpty()) return malformed()
        return Ok(DBusType.Struct(fields))
    }

    private fun basic(code: Char): Result<DBusType.Basic, DBusError> =
        DBusType.byCode[code]?.let(::Ok) ?: malformed()

    private fun <T> malformed(): Result<T, DBusError> = Err(DBusError.MalformedSignature(text))

    private fun peek(): Char? = text.getOrNull(at)

    private fun take(): Char? = text.getOrNull(at)?.also { at++ }
}
