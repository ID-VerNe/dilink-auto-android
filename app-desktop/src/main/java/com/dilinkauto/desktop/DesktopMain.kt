package com.dilinkauto.desktop

import androidx.compose.ui.window.application
import com.dilinkauto.desktop.config.DesktopPaths
import com.dilinkauto.desktop.config.DesktopSettings
import com.dilinkauto.desktop.log.DesktopLog
import com.dilinkauto.desktop.ui.DesktopWindow
import com.dilinkauto.protocol.HandshakeResponse
import com.dilinkauto.protocol.PlatformLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * :app-desktop 入口（只做参数解析、配置装配与选择运行模式）。
 *
 * 两种模式：
 *  - 默认：打开桌面窗口显示手机镜像；
 *  - `--probe`：无窗口探针，只打印状态机与视频帧率（回归/联调工具）。
 *
 * 用法： app-desktop [手机IP] [宽=1280] [高=720] [--probe]
 *
 * 手机 IP 优先取命令行；没传则用 `config.json` 的 `dev_phone_ip`。
 * 配置与日志目录见 [DesktopPaths]（默认 `%APPDATA%\DiLinkAuto`，可用环境变量
 * `DILINK_DESKTOP_HOME` 覆盖，便携模式与测试都靠它）。
 *
 * 分工：连接编排在 [DesktopConnectionService]，握手报文在 [HandshakeFactory]，
 * 解码在 [com.dilinkauto.desktop.video.VideoDecodePipeline]，渲染在
 * [com.dilinkauto.desktop.ui.SwingVideoView]，应用列表在
 * [com.dilinkauto.desktop.apps.AppCatalog]，窗口模式的编排在 [DesktopApp]。
 */
fun main(args: Array<String>) {
    val probe = args.contains("--probe")
    val positional = args.filterNot { it.startsWith("--") }

    // 1) 配置：先读 config.json（首次运行落一份默认配置，方便用户直接编辑）。
    val configFile = DesktopPaths.configFile()
    val settings = DesktopSettings.load(configFile) { println("[config] $it") }
    if (!configFile.isFile) {
        settings.save(configFile) { println("[config] $it") }
    }

    // 2) 日志：控制台始终输出，文件写入由 log_enabled 控制。
    val log = DesktopLog(file = DesktopPaths.logFile(), fileEnabled = settings.logEnabled)
    installDesktopLogging(log)
    log.info(TAG, "config: ${configFile.absolutePath} (log_enabled=${settings.logEnabled})")

    // 3) 手机 IP：命令行 > config.json，都没有就给用法提示。
    val host = positional.getOrNull(0)?.takeIf { it.isNotBlank() }
        ?: settings.devPhoneIp.ifBlank { null }
    if (host == null) {
        val usage = "用法: app-desktop [手机IP] [宽=1280] [高=720] [--probe]\n" +
            "也可在 ${configFile.absolutePath} 里填写 \"dev_phone_ip\"。"
        println(usage)
        log.info(TAG, "缺少手机 IP，已退出")
        log.close()
        return
    }

    val width = positional.getOrNull(1)?.toIntOrNull() ?: DEFAULT_VIEWPORT_WIDTH
    val height = positional.getOrNull(2)?.toIntOrNull() ?: DEFAULT_VIEWPORT_HEIGHT

    val config = DesktopConfig(
        phoneHost = host,
        viewportWidth = width,
        viewportHeight = height,
        targetFps = settings.startupFps,
        bitrate = settings.startupBitrate,
        dpiOverride = settings.startupDpi,
    )
    log.info(
        TAG,
        "连接目标 ${config.phoneHost} 视口 ${width}x$height fps=${config.targetFps} " +
            "bitrate=${config.bitrate} dpiOverride=${config.dpiOverride} hwaccel=${settings.startupHwaccel}",
    )

    try {
        if (probe) runProbe(config, log) else runWindow(config, settings, configFile, log)
    } finally {
        log.close()
    }
}

/** 无窗口探针：链路验证入口（不解码、不渲染）。 */
private fun runProbe(config: DesktopConfig, log: DesktopLog) {
    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val frameCount = AtomicLong()
    val service = DesktopConnectionService(scope, config).apply {
        onStateChanged = { log.info("state", "$it") }
        onHandshakeResponse = { logHandshake(log, it) }
        onVideoFrame = { frameCount.incrementAndGet() }
        onLog = { log.info("session", it) }
        // 探针同样支持 ADB 部署路径，否则无 Shizuku 的手机根本走不到推流
        onVdDeployRequired = { response -> deployVdServerForProbe(response, config, log) }
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
        kotlinx.coroutines.runBlocking { service.runSession() }
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
private suspend fun deployVdServerForProbe(
    response: HandshakeResponse,
    config: DesktopConfig,
    log: DesktopLog,
) {
    if (System.getenv("DILINK_DEV_MODE") != "1") {
        log.warn(TAG, "手机无 Shizuku，且未设 DILINK_DEV_MODE=1 —— 无法部署 VD server")
        return
    }
    val deployer = com.dilinkauto.desktop.deploy.AdbDeployer()
    try {
        val jarPath = response.vdServerJarPath.ifBlank { com.dilinkauto.protocol.VdDeploy.JAR_PATH }
        deployer.deploy(config, response.adbPort, jarPath) { log.info("adb", it) }
    } finally {
        // 探针模式没有会话级的收尾钩子，这里等会话结束后由 JVM 退出时释放；
        // 显式关闭以免长驻 adb 进程把 shell 流挂着（会挡住手机侧回收 VD）。
        deployer.close()
    }
}

/** 窗口模式：把编排交给 [DesktopApp]，窗口关闭后收尾退出。 */
private fun runWindow(
    config: DesktopConfig,
    settings: DesktopSettings,
    configFile: java.io.File,
    log: DesktopLog,
) {
    val app = DesktopApp(configFile, settings, log, config)
    app.start()

    // exitProcessOnExit=false：窗口关闭后先回到这里做收尾（停会话触发手机回收 VD、停解码线程），
    // 再显式退出进程；否则 Compose 会直接 exitProcess，收尾代码永远执行不到。
    application(exitProcessOnExit = false) {
        DesktopWindow(app = app, onClose = { exitApplication() })
    }

    app.stop()
    log.info(TAG, "已退出")
    kotlin.system.exitProcess(0)
}

internal fun logHandshake(log: DesktopLog, r: HandshakeResponse) {
    log.info(
        "handshake",
        "${r.deviceName} accepted=${r.accepted} display=${r.displayWidth}x${r.displayHeight} " +
            "vdId=${r.virtualDisplayId} method=${r.connectionMethod} vdDpi=${r.vdDpi} " +
            "adbPort=${r.adbPort}",
    )
}

/**
 * 桌面端平台日志策略：只把 WARN 及以上转给 [DesktopLog]。
 *
 * `:protocol-core` 的 NioReader 每次轮询无数据都会打 DEBUG 日志
 * （真机 17 秒上百行），会淹没真正有用的会话日志。会话级日志由
 * [DesktopConnectionService.onLog] 单独输出，不经过这里。
 */
private fun installDesktopLogging(log: DesktopLog) {
    PlatformLog.sink = { level, tag, message ->
        if (level == PlatformLog.Level.WARN) log.warn(tag, message)
    }
}

private const val TAG = "main"
private const val DEFAULT_VIEWPORT_WIDTH = 1280
private const val DEFAULT_VIEWPORT_HEIGHT = 720
