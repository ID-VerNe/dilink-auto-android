package com.dilinkauto.protocol

import kotlinx.coroutines.isActive
import kotlinx.coroutines.yield
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.ClosedSelectorException
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import kotlin.coroutines.coroutineContext
import kotlin.Throws

/**
 * Non-blocking buffered reader for a NIO SocketChannel.
 * Accumulates data from non-blocking reads and provides typed read methods.
 *
 * Uses a Selector for zero-latency wakeup when data arrives — no polling delay.
 * Cooperates with coroutine cancellation via periodic isActive checks.
 */
class NioReader(
    private val channel: SocketChannel,
    initialCapacity: Int = DEFAULT_CAPACITY,
    private val selectTimeoutMs: Long = 500 // reduced from 16ms — select() wakes on data anyway
) {

    init {
        require(!channel.isBlocking) { "Channel must be in non-blocking mode" }
    }

    private val selector: Selector = Selector.open()
    private val selectionKey: SelectionKey = channel.register(selector, SelectionKey.OP_READ)

    private var buf: ByteBuffer = ByteBuffer.allocate(initialCapacity).apply {
        order(ByteOrder.BIG_ENDIAN)
        flip() // empty, ready for reading
    }

    suspend fun readByte(): Byte { ensureAvailable(1); return buf.get() }
    suspend fun readInt(): Int { ensureAvailable(4); return buf.getInt() }
    suspend fun readFloat(): Float { ensureAvailable(4); return buf.getFloat() }

    suspend fun readFully(dst: ByteArray, offset: Int = 0, length: Int = dst.size) {
        ensureAvailable(length)
        buf.get(dst, offset, length)
    }

    /**
     * Read a byte, returning null on EOF. Use at message boundaries
     * to distinguish clean disconnect from mid-message EOF.
     */
    suspend fun readByteOrNull(): Byte? {
        if (!fillOrEof(1)) return null
        return buf.get()
    }

    /** Read an int, returning null on EOF. */
    suspend fun readIntOrNull(): Int? {
        if (!fillOrEof(4)) return null
        return buf.getInt()
    }

    /**
     * Ensures at least [count] bytes are available for reading.
     * Grows the internal buffer if necessary.
     * @throws IOException on EOF.
     */
    private suspend fun ensureAvailable(count: Int) {
        if (!fillOrEof(count)) {
            throw IOException("Channel closed: expected $count bytes")
        }
    }

    /**
     * Fill the buffer until at least [needed] bytes are available.
     * Returns false on EOF, true when enough data is ready.
     *
     * Coroutine variant of the shared fill loop: same read/wait primitives as
     * [fillOrEofBlocking], plus periodic logging and cooperative cancellation
     * ([yield] + isActive check) around the selector wait.
     */
    private suspend fun fillOrEof(needed: Int): Boolean {
        growIfNeeded(needed)
        // Read from channel until we have enough
        while (buf.remaining() < needed) {
            val n = readIntoBuffer()
            if (n == -1) return false
            if (n == 0) {
                // No data available — log periodically, then wait for data
                // using Selector (instant wakeup).
                noDataCount++
                if (noDataCount % 100 == 1L) {
                    PlatformLog.d("NioReader", "no data #$noDataCount: needed=$needed remaining=${buf.remaining()} capacity=${buf.capacity()}")
                }
                yield() // cooperate with coroutine cancellation
                if (!coroutineContext.isActive) return false
                if (!awaitReadable()) return false
            }
        }
        return true
    }

    // ── Blocking read API (for non-coroutine callers like vd-server) ──

    @Throws(IOException::class)
    fun readByteBlocking(): Byte { ensureAvailableBlocking(1); return buf.get() }

    @Throws(IOException::class)
    fun readIntBlocking(): Int { ensureAvailableBlocking(4); return buf.getInt() }

    @Throws(IOException::class)
    fun readFloatBlocking(): Float { ensureAvailableBlocking(4); return buf.getFloat() }

    @Throws(IOException::class)
    fun readFullyBlocking(dst: ByteArray, offset: Int = 0, length: Int = dst.size) {
        ensureAvailableBlocking(length)
        buf.get(dst, offset, length)
    }

    /**
     * Blocking read — returns null on EOF, the byte value if available.
     * Use at message boundaries to distinguish clean disconnect from mid-message EOF.
     */
    @Throws(IOException::class)
    fun readByteOrNullBlocking(): Byte? {
        if (!fillOrEofBlocking(1)) return null
        return buf.get()
    }

    /** Blocking read — returns null on EOF, the int value if available. */
    @Throws(IOException::class)
    fun readIntOrNullBlocking(): Int? {
        if (!fillOrEofBlocking(4)) return null
        return buf.getInt()
    }

    private fun ensureAvailableBlocking(count: Int) {
        if (!fillOrEofBlocking(count)) {
            throw IOException("Channel closed: expected $count bytes")
        }
    }

    private fun fillOrEofBlocking(needed: Int): Boolean {
        growIfNeeded(needed)
        while (buf.remaining() < needed) {
            val n = readIntoBuffer()
            if (n == -1) return false
            if (n == 0) {
                if (!awaitReadable()) return false
            }
        }
        return true
    }

    // ── Shared fill primitives (used by both the coroutine and blocking loops) ──

    /**
     * One non-blocking read attempt into the internal buffer.
     * Returns bytes read, 0 when no data is pending, -1 on EOF.
     */
    private fun readIntoBuffer(): Int {
        buf.compact()
        val n = channel.read(buf)
        buf.flip()
        return n
    }

    /**
     * Block the calling thread on the selector until data arrives (or the
     * select timeout expires). Returns false when the selector was closed —
     * i.e. [close]/disconnect ran concurrently, which counts as EOF.
     */
    private fun awaitReadable(): Boolean {
        return try {
            if (!selector.isOpen) return false
            selector.select(selectTimeoutMs) // blocks thread until data or timeout
            if (selector.isOpen) selector.selectedKeys().clear()
            true
        } catch (_: ClosedSelectorException) {
            false
        }
    }

    /**
     * Grow the internal buffer if a single read unit exceeds capacity.
     * Shared by [fillOrEof] and [fillOrEofBlocking] so the grow policy lives
     * in one place — previously the same 5-line block was duplicated.
     */
    private fun growIfNeeded(needed: Int) {
        if (needed > buf.capacity()) {
            val newBuf = ByteBuffer.allocate(needed + GROW_PADDING)
            newBuf.order(ByteOrder.BIG_ENDIAN)
            newBuf.put(buf) // copy remaining unread data
            newBuf.flip()
            buf = newBuf
        }
    }

    /**
     * Wakes the selector from another thread (e.g., during disconnect).
     * This unblocks any select() call in fillOrEof().
     */
    fun wakeup() {
        try { selector.wakeup() } catch (_: Exception) {}
    }

    fun close() {
        try { selector.wakeup() } catch (_: Exception) {}
        try { selectionKey.cancel() } catch (_: Exception) {}
        try { selector.close() } catch (_: Exception) {}
    }

    private var noDataCount = 0L

    companion object {
        const val DEFAULT_CAPACITY = 131072 // 128KB — reduced for low-end devices
        private const val GROW_PADDING = 4096
    }
}
