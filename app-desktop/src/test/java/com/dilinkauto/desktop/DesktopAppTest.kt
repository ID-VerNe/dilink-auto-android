package com.dilinkauto.desktop

import com.dilinkauto.desktop.config.DesktopSettings
import com.dilinkauto.desktop.config.DesktopSettingsStore
import com.dilinkauto.desktop.log.DesktopLog
import com.dilinkauto.protocol.CONNECTION_METHOD_USB_ADB
import com.dilinkauto.protocol.Channel
import com.dilinkauto.protocol.Connection
import com.dilinkauto.protocol.ControlMsg
import com.dilinkauto.protocol.HandshakeResponse
import com.dilinkauto.protocol.Ports
import com.dilinkauto.protocol.VdDeployArgs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.Closeable
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.channels.ClosedChannelException
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [DesktopApp] 的会话生命周期测试（audit WIN-15 ①）。
 *
 * 这是三条 HIGH/MED 缺陷的回归防线：
 *  - [DesktopApp.stop] 必须**可等待**（WIN-03）：调用方紧接着 exitProcess，
 *    异步提交会把收尾截断；
 *  - 会话结束信号只由**未被下一代顶替**的那一代发出（WIN-04）：否则"应用并重连"
 *    会先结束旧代，旧代的结束信号把窗口误关掉；
 *  - 部署失败必须**终止会话**而不是静默挂着（WIN-06）。
 *
 * 不依赖真实设备：用"不可达端口"模拟连接失败、用"只接不回"的假服务器模拟挂住、
 * 用"无 Shizuku"的假手机驱动部署失败路径。所有用例都在真 TCP（127.0.0.1）上跑。
 */
class DesktopAppTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ─── 用例 ───

    @Test
    fun `正常断连会向未被顶替的当前代发出结束信号`() {
        val app = newApp(freePort())
        try {
            app.start()
            val session = awaitSession(app)

            // 端口无人监听 → 连接立即失败 → 会话收尾；未经历 restart，代际守卫应当放行
            awaitCondition("当前代发出 sessionEnded") { session.sessionEnded.value }

            assertSame("未被顶替时 session 应保持为这一代", session, app.session.value)
            assertEquals(SessionState.DISCONNECTED, session.state.value)
        } finally {
            app.stop()
        }
    }

    @Test
    fun `应用并重连时被顶替的旧代不发出结束信号`() {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val server = SilentControlServer(scope)
        val app = newApp(server.port)
        try {
            app.start()
            val first = awaitSession(app)
            awaitCondition("旧代连上假服务器") { server.connectionCount >= 1 }

            // 「应用并重连」：closeSession 先摘 _session，再建新代
            app.restart(dpiOverride = null, hwaccelEnabled = null)
            awaitCondition("新代建立") { app.session.value?.generation == 2 }

            // 等旧代协程真正收尾（状态机落到 DISCONNECTED 是收尾路径上的最后一站），
            // 再留一小段缓冲：若守卫失效，sessionEnded 会在 runSession 返回后立即被置真
            awaitCondition("旧代收尾") { first.state.value == SessionState.DISCONNECTED }
            Thread.sleep(150)

            assertFalse("被顶替的旧代不得发出结束信号（WIN-04）", first.sessionEnded.value)
            assertFalse("新代的结束信号也不该被旧代触发", app.session.value!!.sessionEnded.value)
        } finally {
            app.stop()
            server.close()
            scope.cancel()
        }
    }

    @Test
    fun `stop 返回时收尾已完成`() {
        val app = newApp(freePort())
        app.start()
        awaitSession(app)

        app.stop()

        // WIN-03 的核心断言：stop() 是同步收尾 —— 返回时当前会话必须已摘除，
        // 这样 DesktopMain 紧接着 exitProcess 才不会截断 closeSession
        assertNull("stop() 返回后 session 必须为 null", app.session.value)
    }

    @Test
    fun `restart 把新参数写进配置并按协议区间夹紧`() {
        val app = newApp(freePort())
        try {
            app.start()
            awaitSession(app)

            // 9999 越界 → 按协议区间夹到 480（WIN-08：与手机侧同一份区间定义）
            app.restart(dpiOverride = 9999, hwaccelEnabled = true)
            awaitCondition("重连完成") { app.session.value?.generation == 2 }

            assertEquals(VdDeployArgs.DPI_OVERRIDE_MAX, app.startupDpi)
            assertTrue(app.startupHwaccel)

            // 已落盘：下次启动读回同一组值
            val saved = DesktopSettingsStore(configFile).load {}
            assertEquals(VdDeployArgs.DPI_OVERRIDE_MAX, saved.startupDpi)
            assertTrue(saved.startupHwaccel)

            // null（持续黑屏自动重连用的就是这组参数）= 不改动现值
            app.restart(dpiOverride = null, hwaccelEnabled = null)
            awaitCondition("再次重连完成") { app.session.value?.generation == 3 }
            assertEquals(VdDeployArgs.DPI_OVERRIDE_MAX, app.startupDpi)
            assertTrue(app.startupHwaccel)
        } finally {
            app.stop()
        }
    }

    @Test
    fun `dev_mode 关闭时部署失败会终止会话而不是静默挂着`() {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val phone = NoShizukuPhone(scope)
        val logLines = Collections.synchronizedList(mutableListOf<String>())
        val app = newApp(phone.port, logLines)
        try {
            app.start()
            val session = awaitSession(app)

            // 握手到达假手机（"无 Shizuku"响应已回）→ 桌面端进入部署分支
            awaitCondition("假手机收到握手请求") { phone.handshakeReceived.isCompleted }

            // dev_mode 关闭 → 部署闸门拒绝 → 会话必须终止（WIN-06），而不是
            // 与 UI 一起停在"正在连接 …"看不出死活
            awaitCondition("会话因部署失败而结束") { session.sessionEnded.value }
            assertEquals(SessionState.DISCONNECTED, session.state.value)
            assertTrue(
                "日志里应有 dev_mode 提示",
                synchronized(logLines) { logLines.any { it.contains("dev_mode") } },
            )
        } finally {
            app.stop()
            phone.close()
            scope.cancel()
        }
    }

    // ─── 辅助 ───

    private val configFile: File get() = File(tmp.root, "config.json")

    private fun newApp(
        controlPort: Int,
        logLines: MutableList<String>? = null,
    ): DesktopApp {
        val collected = logLines
        val log = DesktopLog(
            file = null,
            fileEnabled = false,
            echoToConsole = collected != null,
            console = { line -> collected?.add(line) },
        )
        return DesktopApp(
            configFile = configFile,
            settings = DesktopSettings(),
            log = log,
            initialConfig = DesktopConfig(
                phoneHost = "127.0.0.1",
                controlPort = controlPort,
                videoPort = freePort(),
                inputPort = freePort(),
                viewportWidth = 1280,
                viewportHeight = 720,
                connectTimeoutMs = 3_000,
            ),
        )
    }

    /** 拿一个"几乎必然无人监听"的端口：OS 分配后立即释放。 */
    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun awaitSession(app: DesktopApp): DesktopApp.Session {
        awaitCondition("会话代建立") { app.session.value != null }
        return app.session.value!!
    }

    private fun awaitCondition(what: String, timeoutMs: Long = 8_000, cond: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (cond()) return
            Thread.sleep(10)
        }
        fail("等待超时：$what")
    }
}

/**
 * 哑控制口服务器：接受连接后**不回应握手**，但定期发一条无副作用控制帧保活。
 *
 * 桌面端连上后会停在"等握手响应"（该等待没有超时，靠连接断开/停止收尾），
 * 这样测试可以稳定地构造"旧代仍在运行"的时刻去点「应用并重连」。
 *
 * 保活的意义：控制口的 watchdog 在 10s 收不到任何帧时会主动断开 —— 慢机器上
 * 若测试前序步骤拖长，"旧代仍活着"这个前提会被 watchdog 打断（那不是被测行为）。
 * 任何入站帧都会刷新计时，所以每 2s 发一条 `HEARTBEAT_ACK` 即可。
 */
private class SilentControlServer(scope: CoroutineScope) : Closeable {

    private val server = ServerSocketChannel.open().apply {
        configureBlocking(false)
        socket().reuseAddress = true
        socket().bind(InetSocketAddress(0))
    }

    private val conns = CopyOnWriteArrayList<Connection>()

    val port: Int get() = (server.localAddress as InetSocketAddress).port
    val connectionCount: Int get() = conns.size

    private val job = scope.launch {
        while (currentCoroutineContext().isActive) {
            val channel = try {
                server.accept()
            } catch (_: ClosedChannelException) {
                null
            }
            if (channel == null) {
                delay(10)
                continue
            }
            val conn = Connection(channel, scope)
            conns += conn
            conn.start(enableHeartbeat = false)
            scope.launch {
                while (currentCoroutineContext().isActive) {
                    delay(2_000)
                    runCatching { conn.sendControl(ControlMsg.HEARTBEAT_ACK) }
                }
            }
        }
    }

    override fun close() {
        runCatching { server.close() }
        job.cancel()
        conns.forEach { runCatching { it.disconnect() } }
    }
}

/**
 * 无 Shizuku 的假手机：只接控制口，回一条 `connectionMethod=USB_ADB` 的接受响应，
 * **不**发 `VD_PORTS_BOUND`。驱动的正是"由本端用 adb 部署 VD"那条路径 ——
 * 部署闸门（dev_mode）拒绝后，桌面端应当立刻终止会话。
 */
private class NoShizukuPhone(scope: CoroutineScope) : Closeable {

    private val server = ServerSocketChannel.open().apply {
        configureBlocking(false)
        socket().reuseAddress = true
        socket().bind(InetSocketAddress(0))
    }

    val port: Int get() = (server.localAddress as InetSocketAddress).port

    val handshakeReceived = CompletableDeferred<Unit>()

    private val job = scope.launch {
        val sock = awaitAccept() ?: return@launch
        val conn = Connection(sock, scope)
        conn.onFrames(Channel.CONTROL) { frame ->
            if (frame.messageType != ControlMsg.HANDSHAKE_REQUEST) return@onFrames
            handshakeReceived.complete(Unit)
            conn.sendControl(
                ControlMsg.HANDSHAKE_RESPONSE,
                HandshakeResponse(
                    accepted = true,
                    deviceName = "NoShizukuPhone",
                    displayWidth = 1280,
                    displayHeight = 720,
                    virtualDisplayId = 7,
                    adbPort = Ports.ADB_PORT,
                    connectionMethod = CONNECTION_METHOD_USB_ADB,
                    vdDpi = 240,
                ).encode(),
            )
        }
        conn.start(enableHeartbeat = false)
    }

    private suspend fun awaitAccept(): SocketChannel? {
        while (currentCoroutineContext().isActive) {
            try {
                server.accept()?.let { return it }
            } catch (_: ClosedChannelException) {
                return null
            }
            delay(10)
        }
        return null
    }

    override fun close() {
        runCatching { server.close() }
        job.cancel()
    }
}
