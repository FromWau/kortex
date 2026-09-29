package com.fromwau.kortex.dbus

/**
 * Which signals a connection is asking the bus to route to it.
 *
 * Every field left null matches anything, and the bus routes a signal that satisfies all of the ones set.
 * A rule is only ever about signals: a method call addressed to this connection arrives because it is
 * addressed here, and needs no rule at all.
 *
 * Two jobs, and both are needed. [asExpression] is what `AddMatch` is told, and [matches] picks a rule's
 * own signals back out of everything the bus routes, because one socket receives what every rule asked for.
 */
public data class MatchRule(
    public val sender: String? = null,
    public val iface: String? = null,
    public val member: String? = null,
    public val path: String? = null,
    public val pathNamespace: String? = null,
) {
    /** This rule as `AddMatch` and `RemoveMatch` spell it. */
    public val asExpression: String
        get() = buildList {
            add("type" to "signal")
            sender?.let { add("sender" to it) }
            iface?.let { add("interface" to it) }
            member?.let { add("member" to it) }
            path?.let { add("path" to it) }
            pathNamespace?.let { add("path_namespace" to it) }
        }.joinToString(separator = ",") { (key, value) -> "$key='${value.escaped()}'" }

    public fun matches(signal: Message.Signal): Boolean =
        (sender == null || sender == signal.sender) &&
            (iface == null || iface == signal.iface) &&
            (member == null || member == signal.member) &&
            (path == null || path == signal.path) &&
            (pathNamespace == null || signal.path.startsWithNamespace(pathNamespace))

    private companion object {
        /**
         * A quote inside a value, as the bus's own grammar takes it.
         *
         * The value is single-quoted, and a single-quoted run cannot contain a quote, so the run is closed,
         * a backslash-escaped quote is written outside it, and a new run is opened.
         */
        fun String.escaped(): String = replace("'", "'\\''")

        /**
         * Whether a path is [namespace] or sits under it.
         *
         * The prefix has to end at a separator, or `path_namespace='/a/b'` would also take `/a/bc`.
         */
        fun String.startsWithNamespace(namespace: String): Boolean {
            val trimmed = namespace.trimEnd('/').ifEmpty { "/" }
            return this == trimmed || trimmed == "/" || startsWith("$trimmed/")
        }
    }
}
