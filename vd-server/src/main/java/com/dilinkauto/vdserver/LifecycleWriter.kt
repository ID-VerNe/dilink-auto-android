package com.dilinkauto.vdserver

import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.util.concurrent.ArrayBlockingQueue

/**
 * Single-writer queue for the lifecycle channel (audit R3-SRP-10).
 *
 * The lifecycle Channel is registered for OP_READ by [NioReader]; toggling its
 * blocking mode to write synchronously throws `IllegalBlockingModeException`,
 * which the old try/catch silently swallowed — so every MSG_STACK_EMPTY was
 * dropped. This dedicated thread performs a non-blocking spin write without
 * ever touching the channel's blocking mode.
 *
 * Extracted from [PipelineServer], which keeps only the wiring.
 *
 * @param running polled by the writer loop; when it flips false the thread
 *  exits after finishing the current buffer.
 */
internal class LifecycleWriter(private val running: () -> Boolean) {

    private val queue = ArrayBlockingQueue<ByteBuffer>(16)

    /** Set once the phone-side lifecycle channel is accepted. */
    @Volatile var channel: SocketChannel? = null

    private val thread = Thread({
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
        while (running()) {
            val buf = try { queue.take() } catch (_: InterruptedException) { break }
            val ch = channel
            if (ch == null || !ch.isOpen) continue
            try {
                synchronized(ch) {
                    while (buf.hasRemaining()) {
                        val n = ch.write(buf)
                        if (n == 0) Thread.sleep(1)
                    }
                }
            } catch (_: Exception) {
                // Swallowed: lifecycle responses are advisory (stack-empty).
                // Dropping one on a transient I/O error is preferable to crashing the
                // pipeline — which is what toggling blocking mode to write would do.
            }
        }
    }, "LifeWriter").apply { isDaemon = true }

    fun start() = thread.start()

    /**
     * Enqueue a response. Layout: bare `msgType` byte when the payload is empty,
     * otherwise `msgType` + int length + payload. Drops silently if the queue is full.
     */
    fun enqueue(msgType: Byte, payload: ByteArray) {
        try {
            val len = if (payload.isEmpty()) 1 else 5 + payload.size
            val buf = ByteBuffer.allocate(len)
            buf.put(msgType)
            if (payload.isNotEmpty()) { buf.putInt(payload.size); buf.put(payload) }
            buf.flip()
            queue.offer(buf)
        } catch (_: Exception) {}
    }

    /** Interrupt the writer so it leaves `take()` before the shell/VD teardown. */
    fun stop() = thread.interrupt()
}
