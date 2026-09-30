package com.fromwau.kortex.dbus

import com.fromwau.kern.result.IError
import com.fromwau.kern.result.Result

/**
 * An error to send back to whoever called, under a bus error name.
 *
 * The other direction from [DBusError]: that one says why a call kortex made did not work, this one is
 * what kortex tells a caller about a call it could not answer.
 */
public data class CallRejected(public val name: String, public val message: String) : IError {
    public companion object {
        /** What the bus itself answers a member no object implements, so kortex answers the same. */
        public const val UNKNOWN_METHOD: String = "org.freedesktop.DBus.Error.UnknownMethod"

        /** Nothing is exported at the path the call named. */
        public const val UNKNOWN_OBJECT: String = "org.freedesktop.DBus.Error.UnknownObject"

        /** The arguments were the wrong shape or outside what the member accepts. */
        public const val INVALID_ARGS: String = "org.freedesktop.DBus.Error.InvalidArgs"

        public fun unknownMethod(call: Message.Call): CallRejected = CallRejected(
            UNKNOWN_METHOD,
            "no such method ${call.iface.orEmpty()}.${call.member}",
        )
    }
}

/**
 * What an object exported on a connection does when somebody calls it.
 *
 * Returns the reply's body, which is empty for a member that answers nothing, or a [CallRejected] that
 * becomes an error reply. A call that expects no reply is still handled and its answer dropped, because
 * the work a member does is usually the point rather than what it hands back.
 */
public fun interface ObjectHandler {
    public suspend fun handle(call: Message.Call): Result<List<DBusValue>, CallRejected>
}

/** One object on a connection: what answers calls to it, and what it says when introspected. */
internal class Exported(val introspection: String?, val handler: ObjectHandler)
