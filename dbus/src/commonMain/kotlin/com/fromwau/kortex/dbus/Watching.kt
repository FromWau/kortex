package com.fromwau.kortex.dbus

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.getOrElse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A flow that reads some state and keeps it up from the signals [rules] route here.
 *
 * ```kotlin
 * connection.watching(RULES, ruleFailed = { send(Err(MyError.BusFailed(it))) }) { signals ->
 *     var known = readAll()
 *     send(known)
 *     for (signal in signals) {
 *         known = known.after(signal)
 *         send(known)
 *     }
 * }
 * ```
 *
 * The rules are added and the signals subscribed to before [block] runs, so whatever [block] reads first
 * cannot miss a change made in between. The flow ends when [block] returns, and the rules are removed
 * however it ends.
 *
 * @param ruleFailed what to send where the bus refuses one of [rules], after which the flow ends.
 * @param block your reading and folding. [signals] holds every signal this connection receives, not only
 *   the ones [rules] asked for, and is closed after the last one once the connection ends.
 */
public fun <T> DBusConnection.watching(
    rules: List<MatchRule>,
    ruleFailed: suspend ProducerScope<T>.(DBusError) -> Unit,
    block: suspend ProducerScope<T>.(signals: ReceiveChannel<Message.Signal>) -> Unit,
): Flow<T> = channelFlow {
    rules.forEach { rule ->
        addMatch(rule).getOrElse {
            ruleFailed(it)
            return@channelFlow
        }
    }

    val signals = Channel<Message.Signal>(Channel.UNLIMITED)
    val subscribed = CompletableDeferred<Unit>()
    val copying = launch {
        allSignals
            .onSubscription { subscribed.complete(Unit) }
            .transformWhile { received ->
                if (received is Ok) emit(received.value)
                received is Ok
            }
            .collect { signals.send(it) }
        signals.close()
    }
    subscribed.await()

    try {
        block(signals)
    } finally {
        copying.cancel()
    }
}.onCompletion {
    // NonCancellable: this usually runs because the last collector went away, and a rule left behind has
    // the bus routing signals to nobody for the rest of the session.
    withContext(NonCancellable) { rules.forEach { removeMatch(it) } }
}
