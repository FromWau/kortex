package com.fromwau.kortex.dbus

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.getOrElse
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One message, in the four shapes the bus has.
 *
 * Split by shape rather than carried as one type with every header field nullable, because which fields a
 * message must have is fixed by what kind of message it is: a call names a member, a reply names the serial
 * it answers, and neither can be built without them.
 */
public sealed interface Message {
    /** This message's own serial, unique per connection and never 0. */
    public val serial: UInt

    /** The unique name of whoever sent it, which the bus fills in and a sender never sets. */
    public val sender: String?

    public val body: List<DBusValue>

    /** A request for another connection to do something. */
    public data class Call(
        override val serial: UInt,
        public val path: String,
        public val member: String,
        public val iface: String? = null,
        public val destination: String? = null,
        override val sender: String? = null,
        override val body: List<DBusValue> = emptyList(),
        public val expectsReply: Boolean = true,
    ) : Message

    /** A [Call] that succeeded, carrying whatever it returned. */
    public data class Return(
        override val serial: UInt,
        public val replySerial: UInt,
        public val destination: String? = null,
        override val sender: String? = null,
        override val body: List<DBusValue> = emptyList(),
    ) : Message

    /** A [Call] that failed, carrying the error's own bus name. */
    public data class Failure(
        override val serial: UInt,
        public val replySerial: UInt,
        public val name: String,
        public val destination: String? = null,
        override val sender: String? = null,
        override val body: List<DBusValue> = emptyList(),
    ) : Message

    /** A broadcast nobody replies to. */
    public data class Signal(
        override val serial: UInt,
        public val path: String,
        public val iface: String,
        public val member: String,
        public val destination: String? = null,
        override val sender: String? = null,
        override val body: List<DBusValue> = emptyList(),
    ) : Message

    public companion object
}

/** The header field codes, under the names the specification gives them. */
private object Field {
    const val PATH = 1
    const val INTERFACE = 2
    const val MEMBER = 3
    const val ERROR_NAME = 4
    const val REPLY_SERIAL = 5
    const val DESTINATION = 6
    const val SENDER = 7
    const val SIGNATURE = 8
}

private const val LITTLE = 'l'
private const val BIG = 'B'
private const val PROTOCOL_VERSION: Byte = 1
private const val NO_REPLY_EXPECTED: Byte = 0x1

private const val KIND_CALL: Byte = 1
private const val KIND_RETURN: Byte = 2
private const val KIND_FAILURE: Byte = 3
private const val KIND_SIGNAL: Byte = 4

/** Where the header fields begin: four bytes of flags, the body length, and the serial come first. */
private const val FIELDS_OFFSET = 12

/** Everything before the header fields themselves, including the length counting them. */
internal const val FIXED_HEADER_LENGTH = FIELDS_OFFSET + Int.SIZE_BYTES

/** The header's own alignment, which the body also starts on. */
private const val HEADER_ALIGNMENT = 8

/** What the specification caps a message at, so a length read off a socket cannot ask for a gigabyte. */
private const val MAX_MESSAGE_LENGTH = 134_217_728

/** A header field as it sits on the wire: a code, then a variant holding whatever that code carries. */
private val FIELDS_TYPE = DBusType.Sequence(
    DBusType.Struct(listOf(DBusType.Basic.Byte, DBusType.Variant)),
)

/**
 * This message as bytes, ready for the socket.
 *
 * Little-endian, which is what every peer on the machines kortex targets reads fastest and what all of them
 * accept; a message declares its own order, so nothing depends on the reader agreeing.
 */
public fun Message.encode(): Result<ByteArray, DBusError> {
    val writer = WireWriter(ByteOrder.LITTLE_ENDIAN)
    writer.putByte(LITTLE.code.toByte())
    writer.putByte(kindCode())
    writer.putByte(if (this is Message.Call && !expectsReply) NO_REPLY_EXPECTED else 0)
    writer.putByte(PROTOCOL_VERSION)
    writer.putInt32(0)
    writer.putInt32(serial.toInt())

    writer.putInt32(0)
    val fieldsLengthAt = writer.position - Int.SIZE_BYTES
    writer.pad(HEADER_ALIGNMENT)
    val fieldsFrom = writer.position
    headerFields(body.map { it.type }).forEach { field -> writer.write(field).getOrElse { return Err(it) } }
    writer.patchInt32(fieldsLengthAt, writer.position - fieldsFrom)

    writer.pad(HEADER_ALIGNMENT)
    val bodyFrom = writer.position
    body.forEach { value -> writer.write(value).getOrElse { return Err(it) } }
    writer.patchInt32(Int.SIZE_BYTES, writer.position - bodyFrom)

    return Ok(writer.toByteArray())
}

private fun Message.kindCode(): Byte = when (this) {
    is Message.Call -> KIND_CALL
    is Message.Return -> KIND_RETURN
    is Message.Failure -> KIND_FAILURE
    is Message.Signal -> KIND_SIGNAL
}

private fun Message.headerFields(types: List<DBusType>): List<DBusValue> {
    val fields = mutableListOf<DBusValue>()

    fun put(code: Int, value: DBusValue) {
        fields += DBusValue.Struct(listOf(DBusValue.U8(code.toByte()), DBusValue.Variant(value)))
    }

    fun putText(code: Int, value: String?) {
        value?.let { put(code, DBusValue.Text(it)) }
    }

    when (this) {
        is Message.Call -> {
            put(Field.PATH, DBusValue.ObjectPath(path))
            putText(Field.INTERFACE, iface)
            putText(Field.MEMBER, member)
        }

        is Message.Return -> put(Field.REPLY_SERIAL, DBusValue.U32(replySerial))

        is Message.Failure -> {
            putText(Field.ERROR_NAME, name)
            put(Field.REPLY_SERIAL, DBusValue.U32(replySerial))
        }

        is Message.Signal -> {
            put(Field.PATH, DBusValue.ObjectPath(path))
            putText(Field.INTERFACE, iface)
            putText(Field.MEMBER, member)
        }
    }

    putText(Field.DESTINATION, destination())
    putText(Field.SENDER, sender)
    // An empty body carries no signature field at all, rather than a field holding the empty signature.
    if (types.isNotEmpty()) put(Field.SIGNATURE, DBusValue.Sig(types))
    return fields
}

private fun Message.destination(): String? = when (this) {
    is Message.Call -> destination
    is Message.Return -> destination
    is Message.Failure -> destination
    is Message.Signal -> destination
}

/**
 * The length of the message beginning at [header], which must hold its first [FIELDS_OFFSET] + 4 bytes.
 *
 * A socket reader needs this before it can know how much more to wait for, which is why it is separate
 * from [decode].
 */
public fun lengthOf(header: ByteArray): Result<Int, DBusError> {
    if (header.size < FIXED_HEADER_LENGTH) return Err(DBusError.TruncatedMessage)
    val order = orderOf(header.first()).getOrElse { return Err(it) }
    val bytes = ByteBuffer.wrap(header).order(order)
    val bodyLength = bytes.getInt(Int.SIZE_BYTES)
    val fieldsLength = bytes.getInt(FIELDS_OFFSET)
    if (bodyLength < 0 || fieldsLength < 0) return Err(DBusError.TruncatedMessage)

    val padded = pad(FIXED_HEADER_LENGTH + fieldsLength, HEADER_ALIGNMENT)
    val total = padded + bodyLength
    if (total > MAX_MESSAGE_LENGTH) return Err(DBusError.MessageTooLarge(total))
    return Ok(total)
}

/**
 * The bytes of exactly one whole message, back as the message.
 *
 * Fewer bytes than the header declares is [DBusError.TruncatedMessage], checked here and once, so that
 * every offset read below is known to be within the array. The reader's own guard catches a buffer running
 * out mid-value, but the fixed header is read at absolute offsets, which fail differently and earlier.
 */
public fun Message.Companion.decode(raw: ByteArray): Result<Message, DBusError> {
    val declared = lengthOf(raw).getOrElse { return Err(it) }
    if (raw.size < declared) return Err(DBusError.TruncatedMessage)

    val order = orderOf(raw.first()).getOrElse { return Err(it) }
    val bytes = ByteBuffer.wrap(raw).order(order)
    val kind = bytes.get(1)
    val flags = bytes.get(2)
    val serial = bytes.getInt(HEADER_ALIGNMENT).toUInt()
    val fieldsLength = bytes.getInt(FIELDS_OFFSET)

    val reader = WireReader(bytes)
    reader.seek(FIELDS_OFFSET)
    val fields = reader.read(FIELDS_TYPE)
        .getOrElse { return Err(it) }
        .let { it as? DBusValue.Sequence ?: return Err(DBusError.TruncatedMessage) }
        .let(::headerFieldsByCode)

    val signature = (fields[Field.SIGNATURE] as? DBusValue.Sig)?.types.orEmpty()
    reader.seek(pad(FIXED_HEADER_LENGTH + fieldsLength, HEADER_ALIGNMENT))
    val body = signature.map { type -> reader.read(type).getOrElse { return Err(it) } }

    return assemble(kind, flags, serial, fields, body)
}

private fun assemble(
    kind: Byte,
    flags: Byte,
    serial: UInt,
    fields: Map<Int, DBusValue>,
    body: List<DBusValue>,
): Result<Message, DBusError> {
    val path = fields.text(Field.PATH)
    val iface = fields.text(Field.INTERFACE)
    val member = fields.text(Field.MEMBER)
    val destination = fields.text(Field.DESTINATION)
    val sender = fields.text(Field.SENDER)
    val replySerial = (fields[Field.REPLY_SERIAL] as? DBusValue.U32)?.value

    return when (kind) {
        KIND_CALL -> Ok(
            Message.Call(
                serial = serial,
                path = path ?: return Err(DBusError.MissingHeaderField(Field.PATH)),
                member = member ?: return Err(DBusError.MissingHeaderField(Field.MEMBER)),
                iface = iface,
                destination = destination,
                sender = sender,
                body = body,
                expectsReply = flags.toInt() and NO_REPLY_EXPECTED.toInt() == 0,
            ),
        )

        KIND_RETURN -> Ok(
            Message.Return(
                serial = serial,
                replySerial = replySerial ?: return Err(DBusError.MissingHeaderField(Field.REPLY_SERIAL)),
                destination = destination,
                sender = sender,
                body = body,
            ),
        )

        KIND_FAILURE -> Ok(
            Message.Failure(
                serial = serial,
                replySerial = replySerial ?: return Err(DBusError.MissingHeaderField(Field.REPLY_SERIAL)),
                name = fields.text(Field.ERROR_NAME)
                    ?: return Err(DBusError.MissingHeaderField(Field.ERROR_NAME)),
                destination = destination,
                sender = sender,
                body = body,
            ),
        )

        KIND_SIGNAL -> Ok(
            Message.Signal(
                serial = serial,
                path = path ?: return Err(DBusError.MissingHeaderField(Field.PATH)),
                iface = iface ?: return Err(DBusError.MissingHeaderField(Field.INTERFACE)),
                member = member ?: return Err(DBusError.MissingHeaderField(Field.MEMBER)),
                destination = destination,
                sender = sender,
                body = body,
            ),
        )

        else -> Err(DBusError.UnknownMessageKind(kind))
    }
}

/** A code the specification does not define is skipped rather than refused, as it requires. */
private fun headerFieldsByCode(fields: DBusValue.Sequence): Map<Int, DBusValue> = fields.values
    .filterIsInstance<DBusValue.Struct>()
    .mapNotNull { field ->
        val code = field.fields.getOrNull(0) as? DBusValue.U8 ?: return@mapNotNull null
        val value = field.fields.getOrNull(1)?.unwrapped ?: return@mapNotNull null
        (code.value.toInt() and 0xff) to value
    }
    .toMap()

private fun Map<Int, DBusValue>.text(code: Int): String? = when (val value = this[code]) {
    is DBusValue.Text -> value.value
    is DBusValue.ObjectPath -> value.value
    else -> null
}

private fun orderOf(marker: Byte): Result<ByteOrder, DBusError> = when (marker.toInt().toChar()) {
    LITTLE -> Ok(ByteOrder.LITTLE_ENDIAN)
    BIG -> Ok(ByteOrder.BIG_ENDIAN)
    else -> Err(DBusError.UnknownEndianness(marker.toInt().toChar()))
}

private fun pad(offset: Int, alignment: Int): Int = (offset + alignment - 1) / alignment * alignment
