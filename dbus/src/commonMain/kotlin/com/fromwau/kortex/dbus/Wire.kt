package com.fromwau.kortex.dbus

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.getOrElse
import com.fromwau.kern.result.map
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The longest signature the wire can spell, its length being written as a single byte. */
private const val MAX_SIGNATURE_LENGTH = 255

/** What a struct, and so a dict entry, starts on. */
private const val STRUCT_ALIGNMENT = 8

/**
 * Lays values out as a message body.
 *
 * The specification states every alignment against the message's first byte, so the body is written into
 * the same buffer as the header ahead of it and positions are those offsets directly. Assembling it alone
 * and concatenating would happen to agree, because a body always begins on a multiple of 8, but only by
 * relying on that rather than on the rule.
 */
internal class WireWriter(order: ByteOrder, capacity: Int = 256) {
    private var bytes: ByteBuffer = ByteBuffer.allocate(capacity).order(order)

    val position: Int get() = bytes.position()

    fun toByteArray(): ByteArray = ByteArray(bytes.position()).also { bytes.duplicate().flip().get(it) }

    fun pad(alignment: Int) {
        while (bytes.position() % alignment != 0) putByte(0)
    }

    fun putByte(value: Byte) {
        ensure(Byte.SIZE_BYTES)
        bytes.put(value)
    }

    fun putInt32(value: Int) {
        pad(Int.SIZE_BYTES)
        ensure(Int.SIZE_BYTES)
        bytes.putInt(value)
    }

    /** Overwrites an [putInt32] already written, for a length only known once its contents are out. */
    fun patchInt32(at: Int, value: Int) {
        bytes.putInt(at, value)
    }

    fun putRaw(raw: ByteArray) {
        ensure(raw.size)
        bytes.put(raw)
    }

    fun write(value: DBusValue): EmptyResult<DBusError> {
        when (value) {
            is DBusValue.U8 -> putByte(value.value)
            is DBusValue.Bool -> putInt32(if (value.value) 1 else 0)
            is DBusValue.I16 -> putShort(value.value)
            is DBusValue.U16 -> putShort(value.value.toShort())
            is DBusValue.I32 -> putInt32(value.value)
            is DBusValue.U32 -> putInt32(value.value.toInt())
            is DBusValue.I64 -> putLong(value.value)
            is DBusValue.U64 -> putLong(value.value.toLong())
            is DBusValue.F64 -> putLong(value.value.toRawBits())
            is DBusValue.Text -> putText(value.value)
            is DBusValue.ObjectPath -> putText(value.value)
            is DBusValue.Sig -> return putSignature(value.types.signature)
            is DBusValue.Bytes -> putLengthPrefixed(value.value)
            is DBusValue.Sequence -> return putSequence(value)
            is DBusValue.Struct -> return putFields(value.fields)
            is DBusValue.Pair -> return putFields(listOf(value.key, value.value))
            is DBusValue.Variant -> return putVariant(value.value)
        }
        return Ok(Unit)
    }

    private fun putShort(value: Short) {
        pad(Short.SIZE_BYTES)
        ensure(Short.SIZE_BYTES)
        bytes.putShort(value)
    }

    private fun putLong(value: Long) {
        pad(Long.SIZE_BYTES)
        ensure(Long.SIZE_BYTES)
        bytes.putLong(value)
    }

    private fun putText(value: String) {
        val encoded = value.encodeToByteArray()
        putInt32(encoded.size)
        putRaw(encoded)
        // The length excludes the terminator, which is on the wire regardless.
        putByte(0)
    }

    private fun putSignature(signature: String): EmptyResult<DBusError> {
        val encoded = signature.encodeToByteArray()
        if (encoded.size > MAX_SIGNATURE_LENGTH) return Err(DBusError.SignatureTooLong(signature))
        putByte(encoded.size.toByte())
        putRaw(encoded)
        putByte(0)
        return Ok(Unit)
    }

    private fun putLengthPrefixed(raw: ByteArray) {
        putInt32(raw.size)
        putRaw(raw)
    }

    private fun putSequence(value: DBusValue.Sequence): EmptyResult<DBusError> {
        putInt32(0)
        val lengthAt = position - Int.SIZE_BYTES
        // The count covers the elements alone, so the padding before the first one is laid down after the
        // slot is reserved and before the measurement starts.
        pad(value.element.alignment)
        val contentsFrom = position
        value.values.forEach { element -> write(element).getOrElse { return Err(it) } }
        patchInt32(lengthAt, position - contentsFrom)
        return Ok(Unit)
    }

    private fun putFields(fields: List<DBusValue>): EmptyResult<DBusError> {
        pad(STRUCT_ALIGNMENT)
        fields.forEach { field -> write(field).getOrElse { return Err(it) } }
        return Ok(Unit)
    }

    private fun putVariant(value: DBusValue): EmptyResult<DBusError> {
        putSignature(value.type.signature).getOrElse { return Err(it) }
        return write(value)
    }

    private fun ensure(more: Int) {
        if (bytes.remaining() >= more) return

        var capacity = bytes.capacity()
        while (capacity - bytes.position() < more) capacity *= 2
        bytes = ByteBuffer.allocate(capacity)
            .order(bytes.order())
            .put(bytes.duplicate().flip())
    }
}

/**
 * Reads values back out of a whole message.
 *
 * [bytes] holds the message from its first byte, so a position is the offset the specification's alignment
 * rules are written against, rather than one into a slice that has to be reasoned about separately.
 */
internal class WireReader(private val bytes: ByteBuffer) {
    val offset: Int get() = bytes.position()

    fun seek(to: Int) {
        bytes.position(to)
    }

    /**
     * Reads one value of [type].
     *
     * Every way the bytes can run out lands on [DBusError.TruncatedMessage], whether by a buffer that ends
     * early or by a length field naming more than is there.
     */
    fun read(type: DBusType): Result<DBusValue, DBusError> = try {
        readValue(type)
    } catch (_: BufferUnderflowException) {
        // Cheaper than a remaining() check ahead of every primitive, and the only way the buffer fails.
        Err(DBusError.TruncatedMessage)
    }

    fun readSignature(): Result<List<DBusType>, DBusError> = try {
        val length = bytes.get().toInt() and 0xff
        take(length)
            .getOrElse { return Err(it) }
            .let { encoded ->
                bytes.get()
                DBusType.parse(encoded.decodeToString())
            }
    } catch (_: BufferUnderflowException) {
        Err(DBusError.TruncatedMessage)
    }

    private fun align(alignment: Int) {
        // An underflow here is a message whose padding runs off the end, which read() turns into a value.
        while (bytes.position() % alignment != 0) bytes.get()
    }

    private fun readValue(type: DBusType): Result<DBusValue, DBusError> {
        align(type.alignment)
        return when (type) {
            is DBusType.Basic -> readBasic(type)
            is DBusType.Sequence -> readSequence(type)
            is DBusType.Struct -> readFields(type.fields).map(DBusValue::Struct)
            is DBusType.Pair -> readPair(type)
            DBusType.Variant -> readVariant()
        }
    }

    private fun readBasic(type: DBusType.Basic): Result<DBusValue.Basic, DBusError> = when (type) {
        DBusType.Basic.Byte -> Ok(DBusValue.U8(bytes.get()))
        DBusType.Basic.Bool -> Ok(DBusValue.Bool(bytes.getInt() != 0))
        DBusType.Basic.Int16 -> Ok(DBusValue.I16(bytes.getShort()))
        DBusType.Basic.UInt16 -> Ok(DBusValue.U16(bytes.getShort().toUShort()))
        DBusType.Basic.Int32 -> Ok(DBusValue.I32(bytes.getInt()))
        DBusType.Basic.UInt32 -> Ok(DBusValue.U32(bytes.getInt().toUInt()))
        DBusType.Basic.Int64 -> Ok(DBusValue.I64(bytes.getLong()))
        DBusType.Basic.UInt64 -> Ok(DBusValue.U64(bytes.getLong().toULong()))
        DBusType.Basic.Float64 -> Ok(DBusValue.F64(Double.fromBits(bytes.getLong())))
        DBusType.Basic.Text -> readText().map(DBusValue::Text)
        DBusType.Basic.ObjectPath -> readText().map(DBusValue::ObjectPath)
        DBusType.Basic.Sig -> readSignature().map(DBusValue::Sig)
        DBusType.Basic.UnixFd -> Err(DBusError.UnixFdUnsupported)
    }

    private fun readText(): Result<String, DBusError> =
        take(bytes.getInt())
            .map { encoded ->
                bytes.get()
                encoded.decodeToString()
            }

    private fun readSequence(type: DBusType.Sequence): Result<DBusValue, DBusError> {
        val length = bytes.getInt()
        align(type.element.alignment)
        if (type.element == DBusType.Basic.Byte) return take(length).map(DBusValue::Bytes)
        if (length < 0 || length > bytes.remaining()) return Err(DBusError.TruncatedMessage)

        val end = bytes.position() + length
        val values = mutableListOf<DBusValue>()
        while (bytes.position() < end) {
            values += readValue(type.element).getOrElse { return Err(it) }
        }
        return Ok(DBusValue.Sequence(type.element, values))
    }

    private fun readPair(type: DBusType.Pair): Result<DBusValue, DBusError> {
        val key = readBasic(type.key).getOrElse { return Err(it) }
        val value = readValue(type.value).getOrElse { return Err(it) }
        return Ok(DBusValue.Pair(key, value))
    }

    private fun readVariant(): Result<DBusValue, DBusError> {
        val types = readSignature().getOrElse { return Err(it) }
        val only = types.singleOrNull() ?: return Err(DBusError.MalformedSignature(types.signature))
        return readValue(only).map(DBusValue::Variant)
    }

    private fun readFields(types: List<DBusType>): Result<List<DBusValue>, DBusError> =
        Ok(types.map { field -> readValue(field).getOrElse { return Err(it) } })

    /**
     * [length] bytes, or [DBusError.TruncatedMessage].
     *
     * The check is against what is left rather than against a cap, because a length is read off the wire
     * and a peer is free to send one that is negative or names a gigabyte.
     */
    private fun take(length: Int): Result<ByteArray, DBusError> {
        if (length < 0 || length > bytes.remaining()) return Err(DBusError.TruncatedMessage)
        return Ok(ByteArray(length).also(bytes::get))
    }
}
