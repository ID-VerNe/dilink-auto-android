package com.dilinkauto.protocol

import kotlinx.coroutines.channels.Channel

/**
 * Shared "non-blocking enqueue → single consumer" skeleton for the log sinks
 * (phone `FileLog`, car `CarLogWriter`; audit R3-DRY-05).
 *
 * Callers enqueue from any thread without blocking; exactly one consumer
 * drains in FIFO order via [consume]. The *consumer loop* stays caller-owned
 * because the two call sites deliberately differ:
 *  - `FileLog`: a dedicated daemon thread (object without a lifecycle scope)
 *  - `CarLogWriter`: a coroutine on Dispatchers.IO
 * while the queue mechanics — thread-safe offer, bounded overflow policy,
 * ordered drain — live here in one place.
 *
 * @param capacity max queued items. When full, the newest item is dropped
 *        (trySend semantics — matches CarLogWriter's previous `Channel(1024)`).
 *        Defaults to unbounded, matching FileLog's previous
 *        `ConcurrentLinkedQueue`.
 */
class AsyncLogQueue<T : Any>(capacity: Int = Channel.UNLIMITED) {

    private val channel = Channel<T>(capacity)

    /** Non-blocking enqueue. Drops the item when the queue is at [capacity]. */
    fun offer(item: T) {
        channel.trySend(item)
    }

    /**
     * Drain the queue forever, handing each item to [sink] in FIFO order.
     * Suspends while empty; returns when the channel is [close]d or the
     * calling coroutine is cancelled.
     */
    suspend fun consume(sink: (T) -> Unit) {
        for (item in channel) sink(item)
    }

    /** Best-effort discard of everything currently queued (used by log rotate). */
    fun clear() {
        while (channel.tryReceive().isSuccess) { /* drop */ }
    }

    /** Close the channel — a running [consume] loop terminates. */
    fun close() {
        channel.close()
    }
}
