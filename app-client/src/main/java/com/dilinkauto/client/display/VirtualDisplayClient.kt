package com.dilinkauto.client.display

import com.dilinkauto.client.FileLog
import com.dilinkauto.protocol.FrameCodec
import com.dilinkauto.protocol.NioReader
import com.dilinkauto.protocol.VdLifecycle
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * Lifecycle channel to the VD server process running as shell UID.
 *
 * Receives display-ready and stack-empty notifications from the VD server over
 * localhost. Sends CMD_STOP to the VD server.
 *
 * Video and touch flow directly between VD server and car (ports 9638/9639).
 * This class handles only the lifecycle/command channel on localhost:19647.
 */
class VirtualDisplayClient(
    private val scope: CoroutineScope,
    private val appContext: android.content.Context
) {
    @Volatile private var channel: SocketChannel? = null
    @Volatile private var reader: NioReader? = null
    private val writeBuf = ByteBuffer.allocate(64)
    private val writeLock = Any()

    @Volatile
    var displayId: Int = -1
    var hasDirectInjection: Boolean = false
        private set

    @Volatile
    var isConnected = false
        private set

    @Volatile private var serverChannel: ServerSocketChannel? = null
    private var commandRelayJob: Job? = null

    // Callbacks for relaying VD signals to ConnectionService
    var onStackEmpty: (() -> Unit)? = null
    var onDisplayReady: (() -> Unit)? = null

    /**
     * Opens the ServerSocket immediately (synchronous, instant).
     * Call this BEFORE deploying the VD server so the socket is ready
     * when the VD server connects back.
     */
    fun startListening(port: Int = SERVER_PORT) {
        try { serverChannel?.close() } catch (_: Exception) {}
        val ch = ServerSocketChannel.open()
        ch.configureBlocking(false)
        ch.socket().reuseAddress = true
        ch.socket().bind(InetSocketAddress("0.0.0.0", port))
        serverChannel = ch
        FileLog.i(TAG, "Listening for VD server lifecycle on 0.0.0.0:$port")
    }

    /**
     * Waits for the VD server to connect on the already-open ServerSocket.
     * Call startListening() first.
     */
    suspend fun acceptConnection(port: Int = SERVER_PORT, timeoutMs: Int = 60000): Boolean {
        return withContext(Dispatchers.IO) {
            var ch = serverChannel
            if (ch == null || !ch.isOpen) {
                startListening(port)
                ch = serverChannel
            }
            if (ch == null || !ch.isOpen) {
                FileLog.e(TAG, "acceptConnection: Failed to open ServerSocket on port $port")
                return@withContext false
            }
            try {
                FileLog.i(TAG, "Waiting for VD server lifecycle connection...")

                val deadline = System.currentTimeMillis() + timeoutMs
                var accepted: SocketChannel? = null
                while (isActive && System.currentTimeMillis() < deadline) {
                    accepted = ch.accept()
                    if (accepted != null) break
                    delay(50)
                }

                if (accepted == null) {
                    FileLog.w(TAG, "VD server did not connect within ${timeoutMs}ms")
                    return@withContext false
                }

                accepted.configureBlocking(false)
                accepted.socket().tcpNoDelay = true
                channel = accepted
                val rdr = NioReader(accepted, 65536)
                reader = rdr

                // Read MSG_DISPLAY_READY from VD
                val msgType = rdr.readByte()
                if (msgType == VdLifecycle.MSG_DISPLAY_READY) {
                    displayId = rdr.readInt()
                    val flags = rdr.readByte()
                    hasDirectInjection = (flags.toInt() and 1) != 0
                    isConnected = true
                    FileLog.i(TAG, "VD server connected, displayId=$displayId directInjection=$hasDirectInjection")
                    onDisplayReady?.invoke()
                    startCommandRelay()
                    true
                } else {
                    FileLog.w(TAG, "Unexpected first message from VD server: $msgType")
                    accepted.close()
                    false
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FileLog.e(TAG, "VD accept failed: ${e.message}")
                false
            } finally {
                try { serverChannel?.close() } catch (_: Exception) {}
                serverChannel = null
            }
        }
    }

    /**
     * Reads non-video messages from the VD server (stack empty).
     */
    private fun startCommandRelay() {
        commandRelayJob = scope.launch(Dispatchers.IO) {
            val rdr = reader ?: return@launch
            FileLog.i(TAG, "Command relay started")
            try {
                while (isActive && isConnected) {
                    val msgType = rdr.readByte()

                    when (msgType) {
                        VdLifecycle.MSG_STACK_EMPTY -> {
                            FileLog.i(TAG, "VD stack empty")
                            onStackEmpty?.invoke()
                        }
                        else -> {
                            FileLog.w(TAG, "Unknown VD msg type: 0x${msgType.toString(16)}")
                        }
                    }
                }
            } catch (e: Exception) {
                FileLog.e(TAG, "Command relay error", e)
                isConnected = false
            }
        }
    }

    /**
     * Send CMD_STOP to the VD server to trigger graceful shutdown.
     *
     * Returns true when the byte was handed to the socket. A false return is
     * expected whenever the lifecycle channel is already gone — the engine's
     * `readLifecycleCommands()` treats the resulting EOF/IOException exactly
     * like CMD_STOP (sets running=false → finally cleanup()), so the teardown
     * still happens; the caller just must not assume it was graceful.
     *
     * ── 为什么内部换成工作线程写 ──
     * **主线程不能做 socket 写**：Android 会抛 `NetworkOnMainThreadException`。
     * 真机 2026-10-09 实测每次 `cleanupSession()` 都抛（它跑在 `Dispatchers.Main`），
     * 优雅 CMD_STOP **从未真正发出过**，每次都靠通道 EOF 或 shell kill 兜底 ——
     * 日志里的 "NetworkOnMainThreadException" 就是它。
     *
     * 换线程而不是把调用方改成 suspend：`disconnect()` 必须在写之后**同步**执行
     * （它释放 19647 的 ServerSocketChannel，re-handshake 路径紧接着就要重新
     * bind），投递到异步 scope 会引入端口占用竞态。写线程用 `Future.get(timeout)`
     * 同步汇合，对调用方保持"返回即已发出（或已失败）"的原语义。
     */
    fun stopVdServer(): Boolean {
        val ch = channel
        if (ch == null || !ch.isOpen) {
            FileLog.d(TAG, "stopVdServer: no lifecycle channel (displayId=$displayId) — engine will be stopped by the shell kill instead")
            return false
        }
        val write = FutureTask(Callable {
            try {
                synchronized(writeLock) {
                    writeBuf.clear()
                    writeBuf.put(VdLifecycle.CMD_STOP.toByte())
                    writeBuf.flip()
                    FrameCodec.writeAll(ch, writeBuf)
                }
                FileLog.i(TAG, "Sent CMD_STOP to VD server")
                true
            } catch (e: Exception) {
                // Was `Failed to send CMD_STOP: null` in every teardown: writeAll's
                // exception message is null on a closed socket, so the log looked
                // like a no-op even though the graceful stop had already failed and
                // the caller was about to escalate to pkill.
                FileLog.w(TAG, "Failed to send CMD_STOP (${ch.isOpen}), falling back to shell kill: ${e.javaClass.simpleName}: ${e.message}")
                false
            }
        })
        val worker = Thread(write, "vd-cmd-stop").apply { isDaemon = true }
        worker.start()
        return try {
            // 正常 1 字节写 <1ms；上限只兜底"对端 TCP 缓冲满"的病态场景
            // （writeAll 自身有 5s deadline，超时后它自己收敛，不取消它）。
            write.get(STOP_WRITE_JOIN_MS, TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            FileLog.w(TAG, "CMD_STOP write still blocked after ${STOP_WRITE_JOIN_MS}ms (send buffer full?) — channel close will unblock it")
            false
        } catch (e: java.util.concurrent.ExecutionException) {
            // Callable 全路径自带 catch，这里只防御 JVM 级异常（OOM 等）。
            FileLog.w(TAG, "CMD_STOP write task aborted: ${e.cause?.javaClass?.simpleName}")
            false
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    fun disconnect() {
        isConnected = false
        commandRelayJob?.cancel()
        reader?.close()
        try { serverChannel?.close() } catch (_: Exception) {}
        try { channel?.close() } catch (_: Exception) {}
        serverChannel = null
        channel = null
        reader = null
        displayId = -1
        FileLog.i(TAG, "Disconnected from VD server lifecycle channel")
    }

    companion object {
        private const val TAG = "VirtualDisplayClient"
        // Port the VD server reverse-connects to. Matches Ports.LIFECYCLE_PORT.
        const val SERVER_PORT = com.dilinkauto.protocol.Ports.LIFECYCLE_PORT
        // Lifecycle wire constants (MSG_DISPLAY_READY / MSG_STACK_EMPTY / CMD_STOP)
        // live in com.dilinkauto.protocol.VdLifecycle — shared with vd-server.

        /**
         * [stopVdServer] 等待写线程汇合的毫秒上限。见该方法的线程说明：
         * 等待只为把"写失败/缓冲满"与"写成功"区分开，超时后放行调用方
         * （随之而来的 `disconnect()` 关 channel 会让写线程立即收敛）。
         */
        private const val STOP_WRITE_JOIN_MS = 1000L
    }
}
