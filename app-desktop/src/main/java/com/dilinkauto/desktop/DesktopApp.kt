package com.dilinkauto.desktop

import com.dilinkauto.desktop.apps.AppCatalog
import com.dilinkauto.desktop.apps.AppEntry
import com.dilinkauto.desktop.config.DesktopSettings
import com.dilinkauto.desktop.deploy.AdbDeployer
import com.dilinkauto.desktop.display.KeepAwake
import com.dilinkauto.desktop.input.InputSender
import com.dilinkauto.desktop.log.DesktopLog
import com.dilinkauto.desktop.ui.SwingVideoView
import com.dilinkauto.desktop.video.VideoDecodePipeline
import com.dilinkauto.protocol.HandshakeResponse
import com.dilinkauto.protocol.VdDeploy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO

/**
 * 窗口模式的运行时编排（组合根）。
 *
 * 为什么要有"会话代"（[Session]）：握手参数（视口 / DPI / 帧率 / 码率）只在
 * 连接建立时发一次，改了就必须**重连**。所以 [restart] 的做法是把当前一代整个
 * 关掉（停会话 → 手机侧回收 VD → 停解码线程）再按新配置建一代，UI 靠
 * [Session.generation] 判断"画面组件要不要换"。
 *
 * 生命周期操作全部串行在一根专用线程上（[lifecycle]）：`stop()` 里会 join 解码
 * 线程，放在 UI 线程上点一下"应用"就会卡住窗口。
 *
 * 职责边界：本类只管"把 [DesktopConnectionService] / [VideoDecodePipeline] /
 * [AppCatalog] / 窗口组件接起来"，协议、解码、渲染各自在自己的类里。
 */
class DesktopApp(
    private val configFile: File,
    private var settings: DesktopSettings,
    private val log: DesktopLog,
    initialConfig: DesktopConfig,
) {

    /**
     * 一次连接的全部运行时对象。重启后整代替换 —— UI 收集 [session] 拿到当前代，
     * 用 [generation] 当 key 决定是否重建 Swing 视频组件。
     */
    class Session internal constructor(
        val generation: Int,
        val videoView: SwingVideoView,
        val apps: StateFlow<List<AppEntry>>,
        val sessionEnded: StateFlow<Boolean>,
        val state: StateFlow<SessionState>,
        /** 当前是否真在用硬解；回退软解后变 false（Phase 5b）。 */
        val hardwareDecode: StateFlow<Boolean>,
        private val input: InputSender,
    ) {
        fun launchApp(packageName: String) = input.launchApp(packageName)
        fun goHome() = input.goHome()
        fun goBack() = input.goBack()
        fun goRecent() = input.goRecent()

        /** 开关手机物理屏（Phase 5e）；未连上输入口时返回 false，命令被丢弃。 */
        fun setDisplayPower(on: Boolean) = input.setDisplayPower(on)
    }

    private var config: DesktopConfig = initialConfig

    /** 当前代是否启用硬解（来自 settings，可在显示面板里改）。 */
    private var hwaccel: Boolean = settings.startupHwaccel

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()

    private val keepAwake = KeepAwake()

    private val _keepAwakeOn = MutableStateFlow(settings.keepAwake)

    /** 会话期间是否阻止本机息屏（Phase 5d）；UI 开关写这里。 */
    val keepAwakeOn: StateFlow<Boolean> = _keepAwakeOn.asStateFlow()

    /** 本机能否阻止息屏（非 Windows / JNA 不可用时为 false，UI 据此禁用开关）。 */
    val keepAwakeSupported: Boolean = keepAwake.isSupported

    /** 连接目标（窗口标题用）。 */
    val phoneHost: String get() = config.phoneHost

    /** 握手视口尺寸（窗口初始尺寸用）。 */
    val viewportWidth: Int get() = config.viewportWidth
    val viewportHeight: Int get() = config.viewportHeight

    /** 显示面板的初值；改这两个值需要走 [restart]（握手参数只在建连时发一次）。 */
    val startupDpi: Int get() = settings.startupDpi
    val startupHwaccel: Boolean get() = settings.startupHwaccel

    private val adbDeployer = AdbDeployer()
    private val lifecycle = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "DesktopApp").apply { isDaemon = true }
    }
    private val stopped = AtomicBoolean(false)

    private var scope: CoroutineScope? = null
    private var service: DesktopConnectionService? = null
    private var pipeline: VideoDecodePipeline? = null
    private var generation = 0

    /** 建第一代会话。 */
    fun start() = lifecycle.execute { openSession() }

    /**
     * 改"需要重新握手才能生效"的参数并重连。
     *
     * @param dpiOverride 新的 DPI 覆盖（0=自动）；null 表示不改
     * @param hwaccelEnabled 是否启用 D3D11VA 硬解；null 表示不改
     */
    fun restart(dpiOverride: Int?, hwaccelEnabled: Boolean?) = lifecycle.execute {
        val next = settings.copy(
            startupDpi = dpiOverride?.coerceIn(0, DesktopSettings.MAX_DPI) ?: settings.startupDpi,
            startupHwaccel = hwaccelEnabled ?: settings.startupHwaccel,
        )
        settings = next
        next.save(configFile) { log.warn("config", it) }
        config = config.copy(dpiOverride = next.startupDpi)
        hwaccel = next.startupHwaccel
        log.info(TAG, "重连：dpiOverride=${config.dpiOverride} hwaccel=$hwaccel")
        openSession()
    }

    /** 切换"会话期间保持本机常亮"（Phase 5d）。 */
    fun setKeepAwake(enabled: Boolean) {
        val next = settings.copy(keepAwake = enabled)
        settings = next
        next.save(configFile) { log.warn("config", it) }
        _keepAwakeOn.value = enabled
        log.info(TAG, "保持常亮：$enabled")
        lifecycle.execute { applyKeepAwake() }
    }

    /** 关闭窗口时的收尾：停会话（触发手机回收 VD）→ 停解码 → 关闭所有后台资源。 */
    fun stop() = lifecycle.execute {
        if (!stopped.compareAndSet(false, true)) return@execute
        closeSession()
        keepAwake.close()
        lifecycle.shutdown()
        Unit
    }

    // ─── 会话代管理 ───

    private fun openSession() {
        if (stopped.get()) return
        closeSession()

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val service = DesktopConnectionService(scope, config)
        val videoView = SwingVideoView(
            onTouchDown = { x, y -> service.inputSender.down(x, y) },
            onTouchMove = { x, y -> service.inputSender.move(x, y) },
            onTouchUp = { x, y -> service.inputSender.up(x, y) },
        )

        val catalog = AppCatalog()
        service.onAppList = { catalog.onAppList(it) }
        service.onAppUninstalled = { catalog.onUninstalled(it) }
        service.onLog = { log.info("session", it) }
        service.onHandshakeResponse = { logHandshake(log, it) }

        val sessionEnded = MutableStateFlow(false)
        val stateFlow = MutableStateFlow(SessionState.IDLE)
        val hardwareFlow = MutableStateFlow(hwaccel)
        service.onStateChanged = { state ->
            log.info("state", "$state")
            stateFlow.value = state
            // 只有真正开始推流才申请常亮；断开即释放，避免"连不上却一直亮着"。
            when (state) {
                SessionState.STREAMING -> applyKeepAwake()
                SessionState.DISCONNECTED -> keepAwake.disable()
                else -> Unit
            }
        }

        // Phase 5c：手机没有 Shizuku 时由本端用 adb.exe 部署 VD server。
        service.onVdDeployRequired = { response -> deployVdServer(response) }

        val dumper = FrameDumper(log)
        val pipeline = VideoDecodePipeline(
            hwaccel = if (hwaccel) VideoDecodePipeline.HWACCEL_D3D11VA else null,
        ).apply {
            onLog = { log.info("video", it) }
            onImage = { image ->
                videoView.setFrame(image)
                dumper.maybeDump(image, framesDecoded.get())
            }
        }
        service.onVideoFrame = { pipeline.feed(it) }
        pipeline.start()

        val currentGeneration = ++generation
        val session = Session(
            generation = currentGeneration,
            videoView = videoView,
            apps = catalog.apps,
            sessionEnded = sessionEnded,
            state = stateFlow,
            hardwareDecode = hardwareFlow,
            input = service.inputSender,
        )

        scope.launch {
            // 每秒一条统计：联调时用它判断瓶颈在接收侧还是解码侧。
            var last = 0L
            while (true) {
                delay(1_000)
                val decoded = pipeline.framesDecoded.get()
                log.info(
                    "stats",
                    "decoded fps=${decoded - last} total=$decoded " +
                        "rebuilds=${pipeline.decoderRebuilds.get()} recv=${pipeline.framesFed.get()} " +
                        "cfg=${pipeline.configsFed.get()} hw=${pipeline.hardwareInUse}",
                )
                last = decoded
                hardwareFlow.value = pipeline.hardwareInUse
            }
        }

        scope.launch {
            log.info("session", "连接 ${config.phoneHost}:${config.controlPort} ...")
            service.runSession()
            log.info("session", "已结束（解码帧总数=${pipeline.framesDecoded.get()}）")
            keepAwake.disable()
            sessionEnded.value = true
        }

        this.scope = scope
        this.service = service
        this.pipeline = pipeline
        _session.value = session
    }

    private fun closeSession() {
        if (service == null && pipeline == null && scope == null) return
        // 顺序：先停会话（手机侧据此回收 VD），再停解码，最后收掉 adb 与协程域。
        runCatching { service?.stop() }
        runCatching { pipeline?.stop() }
        runCatching { adbDeployer.close() }
        runCatching { scope?.cancel() }
        runCatching { keepAwake.disable() }
        service = null
        pipeline = null
        scope = null
        _session.value = null
    }

    /**
     * Phase 5c：手机侧没有 Shizuku，VD server 转由本端部署。
     *
     * `dev_mode` 是关键闸门：没有它就不该悄悄调用户的 adb（可能连到别的设备上）。
     */
    private suspend fun deployVdServer(response: HandshakeResponse) {
        if (!settings.devMode) {
            log.warn(
                TAG,
                "手机无 Shizuku（method=${response.connectionMethod}）且未开启 dev_mode —— " +
                    "无法部署 VD server，会话会停在等待 VD。请在 ${configFile.name} 里设 \"dev_mode\": true",
            )
            return
        }
        val jarPath = response.vdServerJarPath.ifBlank { VdDeploy.JAR_PATH }
        adbDeployer.deploy(config, response.adbPort, jarPath) { log.info("adb", it) }
    }

    private fun applyKeepAwake() {
        if (_keepAwakeOn.value && service?.state == SessionState.STREAMING) {
            if (!keepAwake.enable()) log.warn(TAG, "本机不支持阻止息屏（非 Windows 或缺少内核库）")
        } else {
            keepAwake.disable()
        }
    }

    private companion object {
        const val TAG = "app"
    }
}

/**
 * 联调钩子：`DILINK_DUMP_FRAME=<png 路径>` 时把第 N 帧画面落盘，
 * `DILINK_DUMP_FRAME_AT` 指定第几帧（默认 96 ≈ 解码开始 4 秒后；VD 里的 App
 * 需要时间投射，联调时可调大等到画面就绪）。锁屏/显示器休眠时无法截屏，
 * 用解码输出来证明"画面是真内容"。不设置则零行为变化。
 */
private class FrameDumper(private val log: DesktopLog) {
    private val path = System.getenv("DILINK_DUMP_FRAME")?.takeIf { it.isNotBlank() }?.let(::File)
    private val at = System.getenv("DILINK_DUMP_FRAME_AT")?.toLongOrNull() ?: 96L
    private var done = false

    fun maybeDump(image: BufferedImage, framesDecoded: Long) {
        val target = path ?: return
        if (done || framesDecoded < at) return
        done = true
        runCatching { ImageIO.write(image, "png", target) }
            .onSuccess { log.info("video", "解码帧已落盘: ${target.absolutePath}") }
            .onFailure { log.warn("video", "解码帧落盘失败: ${it.message}") }
    }
}
