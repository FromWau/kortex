package com.fromwau.kortex.wayland

// Reading libwayland's own WAYLAND_DEBUG lines, which is the only place a request the client sent shows up:
// nothing in process observes one leaving. A line is "[time] {queue}  -> interface#id.name(args)" for a
// request and the same without the arrow for an event, as src/connection.c's wl_closure_print writes it.

/** Whether this line is [interfaceName] sending [name], rather than receiving an event of that name. */
internal fun String.isRequest(interfaceName: String, name: String): Boolean =
    contains("-> $interfaceName#") && contains(".$name(")

/** Whether this line is [interfaceName] receiving [name]. */
internal fun String.isEvent(interfaceName: String, name: String): Boolean =
    !contains("-> ") && contains("$interfaceName#") && contains(".$name(")

/**
 * Only the lines naming one of [interfaces], for a failure message: a whole trace is mostly frame callbacks
 * and helps nobody, and an empty one says more than a blank does.
 *
 * The empty case counts what it searched, because nothing captured and nothing matched read the same
 * otherwise, and they mean opposite things: a trace that never reached the test, or a request never sent.
 */
internal fun List<String>.traceOf(vararg interfaces: String): String =
    filter { line -> interfaces.any { line.contains("$it#") } }
        .joinToString("\n")
        .ifEmpty { "(none of ${interfaces.joinToString(" or ")} among $size wire lines)" }
