package com.dilinkauto.server.service

import com.dilinkauto.protocol.Channel
import com.dilinkauto.protocol.Connection
import com.dilinkauto.protocol.DataMsg
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * issue #266：离线 buffer 纳入积压统计（行为级）。
 *
 * 断线 3 行 → 假时钟推进 5000ms → 重连 flush，车机必须发出
 * `CarLog backlog=N delay=5000ms`，且 3 行本体按原顺序到达。
 *
 * 走真实 loopback [Connection]（沿用 CarSessionRotationTest 的建连范式），
 * 只用注入的 `nowMs` 控制时钟，测试本身不睡 5 秒。
 */
class CarLogWriterOfflineDrainTest {

    private fun <T> getField(obj: Any, name: String): T {
        val f = obj.javaClass.getDeclaredField(name).apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return f.get(obj) as T
    }

    private fun bufferedLines(writer: CarLogWriter): Int =
        getField<AtomicInteger>(writer, "bufferCount").get()

    private fun createLoopbackConnection(): Pair<Connection, SocketChannel> {
        val server = ServerSocketChannel.open()
        server.bind(InetSocketAddress("127.0.0.1", 0))
        val client = SocketChannel.open()
        client.connect(InetSocketAddress("127.0.0.1", (server.localAddress as InetSocketAddress).port))
        val accepted = server.accept()
        server.close()
        // reader / writer / heartbeat 都在 start() 里启动，构造只配 socket。
        val connection = Connection(client, CoroutineScope(Dispatchers.IO + SupervisorJob()))
        connection.start(enableHeartbeat = false)
        return connection to accepted
    }

    /** 从连接另一端起守护线程收字节，按协议头拆出 CAR_LOG 行。 */
    private class FrameCollector(accepted: SocketChannel) {
        private val bytes = ByteArrayOutputStream()
        private val reader = thread(isDaemon = true, name = "test-266-reader") {
            val buf = ByteBuffer.allocate(8192)
            try {
                while (true) {
                    buf.clear()
                    val n = accepted.read(buf)
                    if (n < 0) break
                    buf.flip()
                    synchronized(bytes) { bytes.write(buf.array(), 0, buf.remaining()) }
                }
            } catch (_: Exception) {
                // socket torn down by the test
            }
        }

        fun carLogLines(): List<String> = synchronized(bytes) {
            val bb = ByteBuffer.wrap(bytes.toByteArray())
            val out = ArrayList<String>()
            while (bb.remaining() >= 6) {
                val len = bb.int
                if (len < 2 || bb.remaining() < len) break
                val channel = bb.get()
                val type = bb.get()
                if (channel == Channel.DATA && type == DataMsg.CAR_LOG) {
                    val payload = ByteArray(len - 2)
                    bb.get(payload)
                    out.add(String(payload, Charsets.UTF_8))
                } else {
                    bb.position(bb.position() + len - 2)
                }
            }
            out
        }

        fun stop() = reader.interrupt()
    }

    private fun <T> awaitUntil(what: String, timeoutMs: Long = 4000, probe: () -> T?): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            probe()?.let { return it }
            Thread.sleep(25)
        }
        error("等待超时：$what")
    }

    @Test
    fun `断线积压纳入 CarLog backlog 且 delay 用单调时钟`() {
        val clock = AtomicLong(0)
        var conn: Connection? = null
        val writer = CarLogWriter("test-266", { clock.get() }, { conn })
        writer.setEnabled(true)
        writer.start()

        var collector: FrameCollector? = null
        try {
            writer.send("offline-1")
            writer.send("offline-2")
            writer.send("offline-3")
            awaitUntil("3 行进入离线缓冲") { (bufferedLines(writer) == 3).takeIf { it } }

            val pair = createLoopbackConnection()
            collector = FrameCollector(pair.second)
            conn = pair.first

            // 断线持续 5s（假时钟），再恢复控制链路并触发出队。
            clock.set(5_000)
            writer.send("online-trigger")

            val lines = awaitUntil("收到 backlog 行 + 3 行离线 + 触发行") {
                collector!!.carLogLines().takeIf { it.size >= 5 }
            }

            val backlogLine = lines.firstOrNull { it.contains("CarLog backlog=") }
                ?: error("必须出现 CarLog backlog 行：$lines")
            assertTrue(
                "delay 必须用单调时钟差值 5000ms，而不是墙钟：$backlogLine",
                backlogLine.contains("delay=5000ms"),
            )
            assertTrue("offline-1 必须送达", lines.any { it.contains("offline-1") })
            assertTrue("offline-2 必须送达", lines.any { it.contains("offline-2") })
            assertTrue("offline-3 必须送达", lines.any { it.contains("offline-3") })
            assertTrue("重连后的在线行也要送达", lines.any { it.contains("online-trigger") })

            val idxBacklog = lines.indexOfFirst { it.contains("CarLog backlog=") }
            val idxFirstOffline = lines.indexOfFirst { it.contains("offline-1") }
            assertTrue("告警行必须先于被积压的行：$lines", idxBacklog in 0 until idxFirstOffline)
        } finally {
            writer.shutdown()
            conn?.disconnect()
            collector?.stop()
        }
    }
}
