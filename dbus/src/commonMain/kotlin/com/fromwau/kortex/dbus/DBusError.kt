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

    /** A message declared a length of [length] bytes, past the 128 MiB the specification allows. */
    public data class MessageTooLarge(public val length: Int) : DBusError

    /**
     * A message nested containers past the 64 levels deep the specification allows; [depth] is where it
     * stopped being read.
     *
     * A variant carries its own signature inside the message rather than in the enclosing one, so three
     * bytes of body buy a level each and no signature length bounds how deep one goes.
     */
    public data class NestingTooDeep(public val depth: Int) : DBusError

    /** A message of a kind that must carry header field [code] arrived without it. */
    public data class MissingHeaderField(public val code: Int) : DBusError

    /** A message declared a kind byte that is none of call, return, error or signal. */
    public data class UnknownMessageKind(public val kind: Byte) : DBusError

    /** Neither `DBUS_SESSION_BUS_ADDRESS` nor `XDG_RUNTIME_DIR` is set, so there is nowhere to look. */
    public data object NoSessionBus : DBusError

    /**
     * [address] names no socket a JVM can open.
     *
     * `unix:abstract=` is the one a Linux desktop may still hand out: the abstract namespace needs a
     * leading NUL in the address, and [java.net.UnixDomainSocketAddress] cannot write one.
     */
    public data class UnreachableBusAddress(public val address: String) : DBusError

    /** This process's own uid could not be read, so `EXTERNAL` has nothing to send. */
    public data class NoCredentials(public val detail: String) : DBusError

    /** The bus refused the connection; [answer] is the line it refused with. */
    public data class AuthenticationRejected(public val answer: String) : DBusError

    /** The socket failed for a reason outside the protocol. */
    public data class SocketFailed(public val detail: String) : DBusError

    /** The connection is closed, whether by [DBusConnection.close] or by the bus hanging up. */
    public data object Disconnected : DBusError

    /** The peer answered with an error of its own; [name] is the error's bus name. */
    public data class CallFailed(public val name: String, public val message: String?) : DBusError

    /** Nothing answered inside the reply budget. */
    public data object ReplyTimedOut : DBusError

    /**
     * The coroutine reading the socket ended on something other than a protocol failure; [detail] is what.
     *
     * Either a bug in kortex or the JVM running out of room for a message a peer declared. It is reported
     * rather than left to the void because the connection is deaf from then on: nothing will read a reply
     * off the socket again, so every call made afterwards would otherwise wait out its own timeout.
     */
    public data class ReaderFailed(public val detail: String) : DBusError
}
