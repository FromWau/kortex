package com.fromwau.kortex.dbus

import com.fromwau.kern.result.IError

/** Failures a caller of `:dbus` can distinguish and act on. */
public sealed interface DBusError : IError {
    /** [text] is not a signature: an unknown type code, an unclosed bracket, or a struct with no fields. */
    public data class MalformedSignature(public val text: String) : DBusError

    /** A value ran past the end of the bytes holding it, so the message was cut short or misaligned. */
    public data object TruncatedMessage : DBusError

    /**
     * A message carried a file descriptor, which kortex cannot receive.
     *
     * The JVM's socket channels expose no `SCM_RIGHTS` ancillary data, so kortex never negotiates `UNIX_FD`
     * and no well-behaved peer sends one. A peer that sends one anyway is told rather than handed an index
     * pointing at nothing.
     */
    public data object UnixFdUnsupported : DBusError

    /** A message declared an endianness byte that is neither `l` nor `B`. */
    public data class UnknownEndianness(public val marker: Char) : DBusError

    /** [signature] is longer than the 255 bytes the wire's one-byte length can count. */
    public data class SignatureTooLong(public val signature: String) : DBusError

    /** A message declared itself [length] bytes long, past the 128 MiB the specification allows. */
    public data class MessageTooLarge(public val length: Int) : DBusError

    /** A message of a kind that must carry header field [code] arrived without it. */
    public data class MissingHeaderField(public val code: Int) : DBusError

    /** A message declared a kind byte that is none of call, return, error or signal. */
    public data class UnknownMessageKind(public val kind: Byte) : DBusError
}
