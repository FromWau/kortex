package com.fromwau.kortex.dbus

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.getOrElse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/** A message survives being written and read back, and says no to bytes that are not one. */
class MessageTest {
    @Test
    fun `every value type survives a round trip through a message body`() {
        val body = listOf(
            DBusValue.U8(-7),
            DBusValue.Bool(true),
            DBusValue.Bool(false),
            DBusValue.I16(Short.MIN_VALUE),
            DBusValue.U16(UShort.MAX_VALUE),
            DBusValue.I32(Int.MIN_VALUE),
            DBusValue.U32(UInt.MAX_VALUE),
            DBusValue.I64(Long.MIN_VALUE),
            DBusValue.U64(ULong.MAX_VALUE),
            DBusValue.F64(-0.5),
            DBusValue.Text("a string with åéü in it"),
            DBusValue.ObjectPath("/org/freedesktop/portal/documents"),
            DBusValue.Sig(listOf(DBusType.Sequence(DBusType.Pair(DBusType.Basic.Text, DBusType.Variant)))),
            DBusValue.Bytes(byteArrayOf(1, 2, 3, 4, 5)),
            DBusValue.Bytes(ByteArray(0)),
            DBusValue.Sequence(DBusType.Basic.Text, listOf(DBusValue.Text("one"), DBusValue.Text("two"))),
            DBusValue.Sequence(DBusType.Basic.UInt64, emptyList()),
            DBusValue.Struct(listOf(DBusValue.I32(3), DBusValue.Text("three"))),
            DBusValue.Variant(DBusValue.Text("boxed")),
            DBusValue.Variant(DBusValue.Variant(DBusValue.I32(2))),
        )

        assertEquals(body, roundTrip(call(body)).body)
    }

    /** The shape `GetAll` answers in, built by kortex this time rather than captured. */
    @Test
    fun `a dictionary of variants of mixed type survives a round trip`() {
        val body = listOf(
            DBusValue.Sequence(
                DBusType.Pair(DBusType.Basic.Text, DBusType.Variant),
                listOf(
                    DBusValue.Pair(DBusValue.Text("IconName"), DBusValue.Variant(DBusValue.Text("dialog-info"))),
                    DBusValue.Pair(DBusValue.Text("ItemIsMenu"), DBusValue.Variant(DBusValue.Bool(false))),
                    DBusValue.Pair(DBusValue.Text("WindowId"), DBusValue.Variant(DBusValue.I32(0))),
                    DBusValue.Pair(
                        DBusValue.Text("IconPixmap"),
                        DBusValue.Variant(
                            DBusValue.Sequence(
                                DBusType.Struct(
                                    listOf(
                                        DBusType.Basic.Int32,
                                        DBusType.Basic.Int32,
                                        DBusType.Sequence(DBusType.Basic.Byte),
                                    ),
                                ),
                                listOf(
                                    DBusValue.Struct(
                                        listOf(
                                            DBusValue.I32(2),
                                            DBusValue.I32(1),
                                            DBusValue.Bytes(byteArrayOf(9, 8, 7, 6, 5, 4, 3, 2)),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(body, roundTrip(call(body)).body)
    }

    @Test
    fun `each of the four kinds keeps the fields that kind must carry`() {
        val call = Message.Call(
            serial = 1u,
            path = "/StatusNotifierItem",
            member = "Activate",
            iface = "org.kde.StatusNotifierItem",
            destination = ":1.31",
            body = listOf(DBusValue.I32(10), DBusValue.I32(20)),
        )
        assertEquals(call, roundTrip(call))

        val ret = Message.Return(serial = 2u, replySerial = 1u, destination = ":1.9")
        assertEquals(ret, roundTrip(ret))

        val failure = Message.Failure(
            serial = 3u,
            replySerial = 1u,
            name = "org.freedesktop.DBus.Error.UnknownMethod",
            body = listOf(DBusValue.Text("no such method")),
        )
        assertEquals(failure, roundTrip(failure))

        val signal = Message.Signal(
            serial = 4u,
            path = "/StatusNotifierWatcher",
            iface = "org.kde.StatusNotifierWatcher",
            member = "StatusNotifierItemRegistered",
            body = listOf(DBusValue.Text(":1.31/StatusNotifierItem")),
        )
        assertEquals(signal, roundTrip(signal))
    }

    /** A call that wants no answer says so in a flag, and the flag is the only place it is recorded. */
    @Test
    fun `a call that expects no reply survives saying so`() {
        val quiet = Message.Call(serial = 5u, path = "/x", member = "Go", expectsReply = false)

        assertEquals(false, roundTrip(quiet).let { assertIs<Message.Call>(it) }.expectsReply)
        assertEquals(true, roundTrip(quiet.copy(expectsReply = true)).let { assertIs<Message.Call>(it) }.expectsReply)
    }

    /**
     * A big-endian message decodes, because a peer picks the order and kortex only writes one of them.
     *
     * Hand-built rather than produced by kortex's own writer, which never emits this order, so nothing here
     * would notice if the marker were ignored.
     */
    @Test
    fun `a message declaring big-endian is read in that order`() {
        val raw = hex(
            "42020001" + // big-endian, a method return, no flags, protocol version 1
                "00000004" + // four bytes of body
                "00000001" + // serial 1
                "0000000f" + // fifteen bytes of header fields
                "05017500" + "00000007" + // reply_serial, as a u, answering serial 7
                "08016700" + "017500" + "00" + // signature "u", then padding out to a multiple of 8
                "00000001", // the body: one u, holding 1
        )

        val reply = assertIs<Message.Return>(Message.decode(raw).getOrElse { fail("did not decode: $it") })

        assertEquals(1u, reply.serial)
        assertEquals(7u, reply.replySerial, "a serial read in the wrong order would be 117440512")
        assertEquals(listOf(DBusValue.U32(1u)), reply.body)
    }

    @Test
    fun `an endianness marker that is neither l nor B is refused`() {
        assertEquals(Err(DBusError.UnknownEndianness('x')), Message.decode(hex("78020001" + "00000000".repeat(3))))
    }

    @Test
    fun `a message cut short is refused rather than read past its end`() {
        val whole = call(listOf(DBusValue.Text("a body long enough to lose the end of")))
            .encode()
            .getOrElse { fail("did not encode: $it") }

        (1 until whole.size).forEach { keep ->
            val cut = whole.copyOfRange(0, keep)
            val decoded = Message.decode(cut)
            assertTrue(
                decoded is Result.Error,
                "${cut.size} of ${whole.size} bytes decoded as a whole message",
            )
        }
    }

    @Test
    fun `a body whose signature field is absent decodes as no arguments at all`() {
        val empty = Message.Call(serial = 9u, path = "/org/freedesktop/DBus", member = "Hello")

        assertEquals(emptyList(), roundTrip(empty).body)
    }

    /** The length a socket reader needs before it knows how much more of the message to wait for. */
    @Test
    fun `the length read off a header matches the whole message`() {
        val whole = call(listOf(DBusValue.Text("measured"), DBusValue.U64(1u)))
            .encode()
            .getOrElse { fail("did not encode: $it") }

        assertEquals(whole.size, lengthOf(whole).getOrElse { fail("no length: $it") })
        assertEquals(
            whole.size,
            lengthOf(whole.copyOfRange(0, FIXED_HEADER_LENGTH)).getOrElse { fail("no length: $it") },
            "the fixed header alone says how long the whole message is",
        )
        assertEquals(Err(DBusError.TruncatedMessage), lengthOf(whole.copyOfRange(0, FIXED_HEADER_LENGTH - 1)))
    }

    private fun call(body: List<DBusValue>): Message.Call = Message.Call(
        serial = 1u,
        path = "/com/fromwau/kortex",
        member = "Echo",
        iface = "com.fromwau.kortex.Test",
        body = body,
    )

    private fun roundTrip(message: Message): Message {
        val raw = message.encode().getOrElse { fail("did not encode: $it") }
        return Message.decode(raw).getOrElse { fail("did not decode: $it") }
    }

    private fun hex(text: String): ByteArray =
        ByteArray(text.length / 2) { index -> text.substring(index * 2, index * 2 + 2).toInt(radix = 16).toByte() }
}
