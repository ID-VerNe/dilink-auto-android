package com.dilinkauto.desktop

import com.dilinkauto.desktop.deploy.AdbDeployer
import com.dilinkauto.desktop.log.DesktopLog
import com.dilinkauto.protocol.HandshakeResponse
import com.dilinkauto.protocol.VdDeploy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicLong

/**
 * 无窗口探针模式（audit R3-SRP-13）——链路验证入口（不解码、不渲染）。
 *
 * 从 [main] 所在文件下沉：入口只做参数解析与模式选择，探针自己是一套完整的
 * 「建会话 → 挂回调 → 每秒打帧率」装配。与 [DesktopApp] 的窗口编排有意不共
 * 用同一套 wiring——探针没有解码/渲染/常亮，硬凑共享抽象只会让两侧互相牵制。
 */
internal class ProbeRunner(
    private val config: DesktopConfig,
    private val log: DesktopLog,
) {

    fun run() {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        val frameCount = AtomicLong()
        val service = DesktopConnectionService(scope, config).apply {
            onStateChanged = { log.info("state", "$it") }
            onHandshakeResponse = { logHandshake(log, it) }
            onVideoFrame = { frameCount.incrementAndGet() }
            onLog = { log.info("session", it) }
            // 探针同样支持 ADB 部署路径，否则无 Shizuku 的手机根本走不到推流
            onVdDeployRequired = { response -> deployVdServer(response) }
        }

        // 每秒打印一次视频帧率，方便肉眼确认推流是否稳定。
        scope.launch {
            var last = 0L
            while (true) {
                delay(1_000)
                val now = frameCount.get()
                log.info("stats", "video fps=${now - last} total=$now")
                last = now
            }
        }

        log.info("session", "连接 ${config.phoneHost}:${config.controlPort} ...")
        try {
            runBlocking { service.runSession() }
        } catch (e: Exception) {
            log.error("session", "会话异常: ${e.message}")
        }
        log.info("session", "会话结束，收到视频帧总数=${frameCount.get()}")
        scope.cancel()
    }

    /**
     * 探针模式没有配置面板，ADB 部署只能用环境变量 `DILINK_DEV_MODE=1` 显式开启
     * —— 不默认动用户的 adb（可能连到别的设备上）。
     */
    private suspend fun deployVdServer(response: HandshakeResponse) {
        if (System.getenv("DILINK_DEV_MODE") != "1") {
            log.warn(TAG, "手机无 Shizuku，且未设 DILINK_DEV_MODE=1 —— 无法部署 VD server")
            return
        }
        val deployer = AdbDeployer()
        try {
            val jarPath = response.vdServerJarPath.ifBlank { VdDeploy.JAR_PATH }
            deployer.deploy(config, response.adbPort, jarPath) { log.info("adb", it) }
        } finally {
            // 探针模式没有会话级的收尾钩子，这里等会话结束后由 JVM 退出时释放；
            // 显式关闭以免长驻 adb 进程把 shell 流挂着（会挡住手机侧回收 VD）。
            deployer.close()
        }
    }

    private companion object {
        const val TAG = "probe"
    }
}
