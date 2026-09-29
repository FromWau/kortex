package com.fromwau.kortex.dbus

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.getOrElse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/** What a signature parses to, and what is not a signature at all. */
class SignatureTest {
    @Test
    fun `every basic type code parses to itself`() {
        DBusType.Basic.entries.forEach { basic ->
            assertEquals(
                listOf(basic),
                DBusType.parse(basic.signature).getOrElse { fail("${basic.signature} did not parse: $it") },
            )
        }
    }

    @Test
    fun `a signature holds a run of types rather than one`() {
        assertEquals(
            listOf(DBusType.Basic.Text, DBusType.Basic.Text),
            DBusType.parse("ss").getOrElse { fail("ss did not parse: $it") },
        )
        assertEquals(emptyList(), DBusType.parse("").getOrElse { fail("the empty signature did not parse: $it") })
    }

    /** The two a tray host cannot avoid, and the ones every mistake in nesting shows up in. */
    @Test
    fun `the tray's own nested types parse and spell themselves back`() {
        listOf("a{sv}", "(sa(iiay)ss)", "a(iiay)", "aai", "a{sa{sv}}", "(y(vv))", "av").forEach { text ->
            val parsed = DBusType.parse(text).getOrElse { fail("$text did not parse: $it") }
            assertEquals(text, parsed.signature, "$text did not survive being parsed and spelled again")
        }
    }

    @Test
    fun `a dictionary is an array of pairs, because that is the only place a pair is legal`() {
        assertEquals(
            listOf(DBusType.Sequence(DBusType.Pair(DBusType.Basic.Text, DBusType.Variant))),
            DBusType.parse("a{sv}").getOrElse { fail("a{sv} did not parse: $it") },
        )
    }

    @Test
    fun `a pair outside an array is not a signature`() {
        assertEquals(Err(DBusError.MalformedSignature("{sv}")), DBusType.parse("{sv}"))
    }

    @Test
    fun `a struct with no fields is not a signature, having no spelling that means one`() {
        assertEquals(Err(DBusError.MalformedSignature("()")), DBusType.parse("()"))
    }

    @Test
    fun `an unknown type code is not a signature`() {
        assertEquals(Err(DBusError.MalformedSignature("z")), DBusType.parse("z"))
        assertEquals(Err(DBusError.MalformedSignature("sz")), DBusType.parse("sz"))
    }

    @Test
    fun `a bracket left open is not a signature`() {
        listOf("(s", "a{sv", "((s)", "a").forEach { text ->
            assertEquals(Err(DBusError.MalformedSignature(text)), DBusType.parse(text), "$text parsed")
        }
    }

    /** A key may only be basic, so a nested one is refused rather than read as something else. */
    @Test
    fun `a pair keyed on a container is not a signature`() {
        assertEquals(Err(DBusError.MalformedSignature("a{(s)v}")), DBusType.parse("a{(s)v}"))
        assertEquals(Err(DBusError.MalformedSignature("a{asv}")), DBusType.parse("a{asv}"))
    }

    @Test
    fun `alignment comes off the type and not off a call site`() {
        assertEquals(1, DBusType.Basic.Byte.alignment)
        assertEquals(4, DBusType.Basic.Text.alignment)
        assertEquals(8, DBusType.Basic.UInt64.alignment)
        assertEquals(1, DBusType.Variant.alignment)
        assertEquals(4, DBusType.Sequence(DBusType.Basic.UInt64).alignment, "an array aligns to its length")
        assertEquals(8, DBusType.Struct(listOf(DBusType.Basic.Byte)).alignment, "a struct aligns to 8 regardless")
    }

    @Test
    fun `parse and spell are inverse over every signature the tests name`() {
        val types = DBusType.parse("ybnqiuxtdsogva{sv}(iiay)").getOrElse { fail("did not parse: $it") }
        assertEquals(Ok(types), DBusType.parse(types.signature))
    }
}
