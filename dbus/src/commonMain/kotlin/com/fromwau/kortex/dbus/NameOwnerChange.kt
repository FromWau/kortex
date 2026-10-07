package com.fromwau.kortex.dbus

/** A bus name changing hands, as the bus announces it with `NameOwnerChanged`. */
public data class NameOwnerChange(
    public val name: String,
    /** The unique name that held [name], null where nobody did. */
    public val oldOwner: String?,
    /** The unique name holding [name] now, null where it was given up. */
    public val newOwner: String?,
)

/**
 * The change this signal announces, or null where it is not a `NameOwnerChanged` from the bus itself.
 *
 * Only the bus's word counts: a peer can send a signal with that interface and member, but not with the
 * bus as its sender.
 */
public val Message.Signal.nameOwnerChange: NameOwnerChange?
    get() {
        if (sender != Bus.NAME || iface != Bus.INTERFACE || member != Bus.NAME_OWNER_CHANGED) return null
        val name = body.getOrNull(0)?.asText ?: return null
        return NameOwnerChange(
            name = name,
            oldOwner = body.getOrNull(1)?.asText?.ifEmpty { null },
            newOwner = body.getOrNull(2)?.asText?.ifEmpty { null },
        )
    }
