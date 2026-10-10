package com.dilinkauto.protocol

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages a multiplexed connection between client and server.
 * Handles frame reading/writing, heartbeats, and channel dispatch.
 *
 * All I/O is non-blocking NIO. Reads use NioReader (Selector-based).
 * Writes use a dedicated writer coroutine with a non-blocking coroutine Channel —
 * no synchronized blocks on the write path, no spin-waiting.
 */
class Connection(
    private val channel: SocketChannel,
    private val scope: CoroutineScope
) {
    private val actualSendBuf: Int
    private val actualRecvBuf: Int

    init {
        channel.configureBlocking(false)
        val sock = channel.socket()
        sock.sendBufferSize = SOCKET_BUF_BYTES    // request 256KB
        sock.receiveBufferSize = SOCKET_BUF_BYTES
        sock.tcpNoDelay = true
        sock.keepAlive = true
        actualSendBuf = sock.sendBufferSize   // what the kernel actually gave us
        actualRecvBuf = sock.receiveBufferSize
    }

    // The stall deadline covers connections without a heartbeat/watchdog (the
    // direct video and input sockets): a peer that connects, sends a partial
    // frame and goes silent no longer pins the reader forever. Idle-at-boundary
    // (touch link with no user input) is deliberately not timed out.
    private val reader = NioReader(
        channel = channel,
        readStallTimeoutMs = HEARTBEAT_TIMEOUT_MS
    )
    private val connected = AtomicBoolean(true)

    // Write queue: bounded coroutine channel, drained by dedicated writer coroutine.
    // Bounded (not UNLIMITED) so a slow TCP consumer applies backpressure to the
    // reader instead of growing the queue unbounded and OOMing on a slow car link.
    private val writeQueue = kotlinx.coroutines.channels.Channel<FrameCodec.Frame>(
        64, kotlinx.coroutines.channels.BufferOverflow.SUSPEND
    )

    /**
     * 发送入队的单线程派发器。
     *
     * 为什么需要它：`sendFrame` 是非挂起 API，会被 UI / 服务线程随时调用。若每帧各自
     * `scope.launch` 到多线程的 IO 池，多个协程会并发抢入队，帧的上线路顺序就不再等于
     * 调用顺序 —— 触摸 DOWN/MOVE/UP 乱序会让手机侧注入出错。
     *
     * 单线程派发器保证入队严格 FIFO：同一调用线程连续调用 `sendFrame` 时，调用顺序 ==
     * 上线路顺序。它只是 IO 池的一个受限视图，不额外占用线程，也无需释放。
     */
    @kotlin.OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val sendDispatcher = Dispatchers.IO.limitedParallelism(1)

    /**
     * 入队互斥:limitedParallelism(1) 只串行化"派发",不串行化"挂起"。
     * 队列满时首个协程挂在 `writeQueue.send`,单线程派发器随即运行下一个协程,
     * 后帧反而先入队 —— 恰好在链路拥塞(注释声称要保护的场景)下乱序。
     * 用锁把"入队"这段临界区钉住:后到的协程阻塞在 acquire,先到的先入队。
     */
    private val sendMutex = Mutex()

    private val frameListeners = ConcurrentHashMap<Byte, (FrameCodec.Frame) -> Unit>()
    @Volatile private var disconnectListener: (() -> Unit)? = null
    @Volatile private var logListener: ((String) -> Unit)? = null
    @Volatile private var disconnectReason: String = "unknown"

    private var readerJob: Job? = null
    private var writerJob: Job? = null
    private var heartbeatJob: Job? = null
    private var watchdogJob: Job? = null

    @Volatile
    private var lastFrameReceivedAt = System.currentTimeMillis()

    val isConnected: Boolean get() = connected.get() && channel.isOpen

    /** Remote peer's IP address (e.g. for ADB connection to the car) */
    val remoteAddress: String? get() = try {
        (channel.remoteAddress as? InetSocketAddress)?.address?.hostAddress
    } catch (_: Exception) { null }

    /**
     * Starts reading frames, writing queued frames, heartbeats, and watchdog.
     */
    fun start(
        enableHeartbeat: Boolean = true,
        heartbeatIntervalMs: Long = HEARTBEAT_INTERVAL_MS,
        heartbeatTimeoutMs: Long = HEARTBEAT_TIMEOUT_MS
    ) {
        log("Connection started: sendBuf=$actualSendBuf recvBuf=$actualRecvBuf tcpNoDelay=${channel.socket().tcpNoDelay} heartbeat=$enableHeartbeat")
        lastFrameReceivedAt = System.currentTimeMillis()

        readerJob = scope.launch(Dispatchers.IO) {
            // 8x A53 has no big cores; mark the socket-drain thread urgent so
            // background coroutines on the same pool don't starve frame reads.
            // 平台钩子：Android 用 Process.setThreadPriority(URGENT_DISPLAY)，桌面用 JVM 线程优先级。
            ThreadPriority.elevateCurrent()
            try {
                while (isActive && connected.get()) {
                    val frame = FrameCodec.readFrame(reader)
                    if (frame == null) {
                        disconnectReason = "reader: EOF"
                        log(disconnectReason)
                        disconnect()
                        break
                    }

                    lastFrameReceivedAt = System.currentTimeMillis()

                    if (frame.channel == Channel.CONTROL &&
                        frame.messageType == ControlMsg.HEARTBEAT
                    ) {
                        // Heartbeat ack is a control frame — suspend-enqueue is safe on the reader.
                        enqueueFrame(FrameCodec.Frame(Channel.CONTROL, ControlMsg.HEARTBEAT_ACK, ByteArray(0)))
                        continue
                    }

                    // Video frames: handle inline for low latency.
                    // INPUT（触摸 DOWN/MOVE/UP）与 CONTROL（导航命令）顺序敏感，也必须
                    // 内联按到达顺序处理 —— 逐帧异步派发会打乱触摸注入。只有 DATA 可能
                    // 是重活（应用列表解码），异步派发以免阻塞读线程排空 TCP。
                    val listener = frameListeners[frame.channel]
                    if (listener != null) {
                        if (frame.channel == Channel.VIDEO ||
                            frame.channel == Channel.INPUT ||
                            frame.channel == Channel.CONTROL
                        ) {
                            // A listener that throws must not kill the reader coroutine:
                            // decoders may raise ProtocolDecodeException (not a
                            // ProtocolException) and BufferUnderflowException on
                            // malformed peer payloads, and a SupervisorJob scope has
                            // no handler for them — the socket would sit open until
                            // the watchdog timed out. Fail the connection instead.
                            try {
                                listener.invoke(frame)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                disconnectReason = "frame handler (ch=${frame.channel}, type=0x${Integer.toHexString(frame.messageType.toInt() and 0xFF)}): ${e.javaClass.simpleName}: ${e.message}"
                                log(disconnectReason)
                                if (connected.get()) disconnect()
                                break
                            }
                        } else {
                            scope.launch {
                                try {
                                    listener.invoke(frame)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Throwable) {
                                    disconnectReason = "frame handler (ch=${frame.channel}, type=0x${Integer.toHexString(frame.messageType.toInt() and 0xFF)}): ${e.javaClass.simpleName}: ${e.message}"
                                    log(disconnectReason)
                                    if (connected.get()) disconnect()
                                }
                            }
                        }
                    }
                }
            } catch (e: java.nio.channels.ClosedSelectorException) {
                // Selector closed by disconnect() — normal shutdown
            } catch (e: IOException) {
                disconnectReason = "reader: IOException: ${e.message}"
                log(disconnectReason)
                if (connected.get()) disconnect()
            } catch (e: ProtocolException) {
                disconnectReason = "reader: ProtocolException: ${e.message}"
                log(disconnectReason)
                if (connected.get()) disconnect()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // ProtocolDecodeException, BufferUnderflowException, and anything a
                // future decoder raises are NOT IOException/ProtocolException, so the
                // three catches above never saw them: they escaped into an unhandled
                // coroutine exception (no CoroutineExceptionHandler exists anywhere in
                // this repo) and killed the process on a single malformed frame.
                disconnectReason = "reader: ${e.javaClass.simpleName}: ${e.message}"
                log(disconnectReason)
                if (connected.get()) disconnect()
            }
        }

        // Dedicated writer coroutine: drains the write queue.
        // Uses Selector for write-readiness when TCP send buffer is full.
        // No deadline — the watchdog handles dead connections.
        writerJob = scope.launch(Dispatchers.IO) {
            val headerBuf = ByteArray(FrameCodec.HEADER_SIZE)
            var writeCount = 0L
            try {
                for (frame in writeQueue) {
                    if (!connected.get() || !isActive) break

                    // Encode header — the byte layout lives in FrameCodec only
                    // (single definition shared with writeFrame/writeFrameToChannel).
                    FrameCodec.encodeHeaderInto(headerBuf, frame)

                    // Gathering write: header + payload in single syscall
                    val bufs = if (frame.payload.isNotEmpty()) {
                        arrayOf(ByteBuffer.wrap(headerBuf), ByteBuffer.wrap(frame.payload))
                    } else {
                        arrayOf(ByteBuffer.wrap(headerBuf))
                    }
                    writeBuffersToChannel(bufs)
                    writeCount++
                    if (frame.channel == Channel.VIDEO && writeCount % 60 == 0L) {
                        log("writer: frame #$writeCount ch=${frame.channel} size=${frame.payload.size} stalls=$writeStallCount")
                    }
                }
            } catch (e: kotlinx.coroutines.channels.ClosedReceiveChannelException) {
                // Channel closed by disconnect() — normal shutdown
            } catch (e: CancellationException) {
                // Coroutine cancelled — normal shutdown
            } catch (e: java.nio.channels.ClosedSelectorException) {
                // Selector closed by disconnect() — normal shutdown
            } catch (e: IOException) {
                disconnectReason = "writer: IOException: ${e.message}"
                log(disconnectReason)
                if (connected.get()) disconnect()
            }
        }

        if (enableHeartbeat) {
            heartbeatJob = scope.launch(Dispatchers.IO) {
                while (isActive && connected.get()) {
                    delay(heartbeatIntervalMs)
                    if (!connected.get()) break
                    try {
                        enqueueFrame(FrameCodec.Frame(Channel.CONTROL, ControlMsg.HEARTBEAT, ByteArray(0)))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        disconnectReason = "heartbeat: ${e.javaClass.simpleName}: ${e.message}"
                        log(disconnectReason)
                        disconnect()
                        break
                    }
                }
            }

            watchdogJob = scope.launch(Dispatchers.IO) {
                while (isActive && connected.get()) {
                    delay(heartbeatIntervalMs)
                    val elapsed = System.currentTimeMillis() - lastFrameReceivedAt
                    if (elapsed > heartbeatTimeoutMs) {
                        disconnectReason = "watchdog: no frame received for ${elapsed}ms (timeout=${heartbeatTimeoutMs}ms)"
                        log(disconnectReason)
                        disconnect()
                        break
                    }
                }
            }
        }
    }

    /**
     * Writes all bytes from multiple buffers to the channel using gathering write.
     * When TCP send buffer is full, yields briefly then retries — the writer is a
     * dedicated coroutine so busy-waiting is acceptable and matches v0.6.2 behavior
     * (blocking OutputStream.write that retried immediately when buffer drained).
     * No Selector OP_WRITE — the 100ms Selector timeout was causing stalls.
     */

    @Volatile private var writeStallCount = 0L

    private suspend fun writeBuffersToChannel(bufs: Array<ByteBuffer>) {
        while (bufs.any { it.hasRemaining() }) {
            val n = channel.write(bufs)
            if (n > 0) continue
            if (!connected.get()) throw IOException("Connection closed during write")
            writeStallCount++
            if (writeStallCount % 100 == 1L) {
                val remaining = bufs.sumOf { it.remaining().toLong() }
                log("write stall #$writeStallCount: remaining=$remaining")
            }
            delay(1)
        }
    }

    /**
     * Enqueues a frame for writing. Suspending so backpressure from a full queue
     * propagates to the caller (reader / heartbeat / watchdog) instead of dropping.
     */
    private suspend fun enqueueFrame(frame: FrameCodec.Frame) {
        if (!connected.get()) throw IOException("Not connected")
        writeQueue.send(frame)
    }

    fun onFrames(channel: Byte, listener: (FrameCodec.Frame) -> Unit) {
        frameListeners[channel] = listener
    }

    fun onDisconnect(listener: (() -> Unit)?) {
        disconnectListener = listener
    }

    fun clearDisconnectListener() {
        disconnectListener = null
    }

    fun onLog(listener: (String) -> Unit) {
        logListener = listener
    }

    private fun log(msg: String) {
        logListener?.invoke(msg)
    }

    fun sendFrame(frame: FrameCodec.Frame) {
        // Public senders run on arbitrary threads (UI, service scope, etc).
        // Route through the scope so the suspending enqueueFrame can apply
        // backpressure without forcing every caller to be a suspend function.
        // 走单线程派发器（而不是裸 scope）以保证入队顺序 == 调用顺序，见 sendDispatcher；
        // sendMutex 保证队列满(挂起)时顺序仍然成立。
        if (!connected.get()) throw IOException("Not connected")
        scope.launch(sendDispatcher) {
            try {
                sendMutex.withLock { enqueueFrame(frame) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // disconnect() closes the writeQueue between the connected check and
                // send(); that surfaces as ClosedSendChannelException (an
                // IllegalStateException, NOT an IOException) — catching only
                // IOException let it escape into an unhandled coroutine exception.
            }
        }
    }

    fun sendControl(messageType: Byte, payload: ByteArray = ByteArray(0)) {
        sendFrame(FrameCodec.Frame(Channel.CONTROL, messageType, payload))
    }

    fun sendVideo(messageType: Byte, payload: ByteArray) {
        sendFrame(FrameCodec.Frame(Channel.VIDEO, messageType, payload))
    }

    fun sendAudio(messageType: Byte, payload: ByteArray) {
        sendFrame(FrameCodec.Frame(Channel.AUDIO, messageType, payload))
    }

    fun sendData(messageType: Byte, payload: ByteArray) {
        sendFrame(FrameCodec.Frame(Channel.DATA, messageType, payload))
    }

    fun sendInput(messageType: Byte, payload: ByteArray) {
        sendFrame(FrameCodec.Frame(Channel.INPUT, messageType, payload))
    }

    fun disconnect() {
        if (connected.compareAndSet(true, false)) {
            val caller = if (disconnectReason == "unknown") {
                val trace = Thread.currentThread().stackTrace
                val relevant = trace.drop(2).take(5).joinToString(" <- ") { "${it.className.substringAfterLast('.')}:${it.methodName}:${it.lineNumber}" }
                "external call: $relevant"
            } else disconnectReason
            log("disconnect() reason=$caller")
            readerJob?.cancel()
            heartbeatJob?.cancel()
            watchdogJob?.cancel()
            if (disconnectReason.startsWith("reader: EOF")) {
                // Graceful peer close (DISCONNECT then FIN): frames already queued
                // — e.g. our DISCONNECT_ACK — are still deliverable for a short
                // window. Cancelling the writer and closing the socket instantly
                // (the old behaviour) silently dropped them. Give the writer
                // FLUSH_GRACE_MS to drain, then finish the teardown.
                scope.launch {
                    delay(FLUSH_GRACE_MS)
                    writerJob?.cancel()
                    writeQueue.close()
                    reader.close()
                    try { channel.close() } catch (_: Exception) {}
                    try { disconnectListener?.invoke() } catch (_: Exception) {}
                }
                return
            }
            writerJob?.cancel()
            writeQueue.close()
            reader.close()
            try { channel.close() } catch (_: Exception) {}
            try { disconnectListener?.invoke() } catch (_: Exception) {}
        }
    }

    companion object {
        private const val HEARTBEAT_INTERVAL_MS = 3000L
        private const val HEARTBEAT_TIMEOUT_MS = 10000L
        private const val CONNECT_TIMEOUT_MS = 10000L

        /** Grace period for the writer to drain queued frames on a graceful peer close. */
        private const val FLUSH_GRACE_MS = 500L

        /**
         * TCP send/receive buffer request (256KB). Raised above the OS default
         * because 1080p keyframes burst larger than the default window.
         * Public because vd-server's socket setup uses the same value — this
         * constant is the single source for both ends of the video path.
         */
        const val SOCKET_BUF_BYTES = 262144

        suspend fun connect(host: String, port: Int, scope: CoroutineScope): Connection =
            connect(host, port, scope, CONNECT_TIMEOUT_MS)

        /**
         * @param timeoutMs deadline for the TCP handshake. A black-holed SYN used
         *   to spin `finishConnect()` forever (only the desktop's wrapper had a
         *   timeout); now the channel is closed and the connect fails.
         */
        suspend fun connect(
            host: String,
            port: Int,
            scope: CoroutineScope,
            timeoutMs: Long = CONNECT_TIMEOUT_MS
        ): Connection =
            withContext(Dispatchers.IO) {
                val channel = SocketChannel.open()
                try {
                    channel.configureBlocking(false)
                    channel.connect(InetSocketAddress(host, port))
                    val deadline = System.currentTimeMillis() + timeoutMs
                    while (!channel.finishConnect()) {
                        if (System.currentTimeMillis() > deadline) {
                            throw IOException("Connect to $host:$port timed out after ${timeoutMs}ms")
                        }
                        delay(50)
                    }
                    Connection(channel, scope)
                } catch (e: Exception) {
                    try { channel.close() } catch (_: Exception) {}
                    throw e
                }
            }

        suspend fun accept(
            port: Int,
            scope: CoroutineScope
        ): Connection = withContext(Dispatchers.IO) {
            val channel = ServerSocketChannel.open()
            channel.configureBlocking(false)
            channel.socket().reuseAddress = true
            channel.socket().bind(InetSocketAddress(port))
            try {
                while (isActive) {
                    val accepted = channel.accept()
                    if (accepted != null) {
                        return@withContext Connection(accepted, scope)
                    }
                    delay(100)
                }
                throw java.util.concurrent.CancellationException("Cancelled while accepting")
            } finally {
                channel.close()
            }
        }
    }
}
