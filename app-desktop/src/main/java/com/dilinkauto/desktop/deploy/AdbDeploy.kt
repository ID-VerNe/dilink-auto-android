package com.dilinkauto.desktop.deploy

import com.dilinkauto.desktop.DesktopConfig
import com.dilinkauto.desktop.HandshakeFactory
import com.dilinkauto.protocol.DimAlign
import com.dilinkauto.protocol.Ports
import com.dilinkauto.protocol.VdDeploy
import com.dilinkauto.protocol.VdDeployArgs
import com.dilinkauto.protocol.VideoConfig

/**
 * ADB 部署路径的纯命令拼装（Phase 5c）。
 *
 * 车机端走的是自己实现的 `TcpAdbConnection`（要自签 ADB 密钥、自己做协议握手）；
 * Windows 上直接调 `adb.exe` 省掉这一整块 —— 这里只负责把 `DesktopConfig`
 * 翻译成 `adb` 的 argv，进程怎么跑交给 [AdbRunner]。
 *
 * 与 `VdDeploy` 的分工：参数与 `CLASSPATH=... app_process ...` 那行的形状由
 * `VdDeploy.buildDeployPlan` 单点决定（三端共用），本对象只补 adb 特有的
 * `connect` / `-s <serial> shell` 外壳。
 */
object AdbDeploy {

    /** adb 里的设备号（TCP 连接时就是 `host:port`）。 */
    fun serial(phoneHost: String, adbPort: Int = Ports.ADB_PORT): String = "$phoneHost:$adbPort"

    /** `adb connect <host>:<port>`。 */
    fun connectArgs(phoneHost: String, adbPort: Int = Ports.ADB_PORT): List<String> =
        listOf("connect", serial(phoneHost, adbPort))

    /** 在指定设备上跑一条 shell 命令（整条命令作为单个 argv，交给设备侧 shell 解析）。 */
    fun shellArgs(serial: String, command: String): List<String> =
        listOf("-s", serial, "shell", command)

    /**
     * 构造 VD server 的部署计划。
     *
     * **VD 尺寸 ≠ 编码尺寸**：编码尺寸（encodeWidth/Height）用本端视口（H.264
     * 偶数对齐后），这是流的真实分辨率；而 VirtualDisplay 尺寸必须用手机侧
     * 握手响应下发的 [phoneVdWidth]/[phoneVdHeight]（`VdDimensions.compute`
     * 的输出——车机视口按手机真实物理长边放大的结果，防 Chinese-ROM IME 把
     * 键盘按物理像素宽渲染到更窄的画布上）。2026-10-09 实锤：按视口 1280x720
     * 建 VD 时，微信键道键盘行宽 1368px 被切 88px，且与 DPI 无关。两个字段
     * 为 0（旧版手机不下发）时退回视口尺寸，保持旧行为。
     * `phoneHost` 固定 127.0.0.1：VD server 与手机 app 同机，推流目标就是本机
     * 回环（与车机端 `VdServerDeployer` 一致）。
     *
     * `background = false` 是硬约束：只有 `exec app_process` 才能让 adb shell 流
     * 在引擎存活期间保持附着；`&` 后台化会被 adbd 回收，表现为日志 0 字节、进程秒死。
     *
     * @param jarPath 用握手响应里的 `vdServerJarPath`（手机侧刚校验过 CRC 的那份）；
     *   空则退回 `VdDeploy.JAR_PATH`。
     */
    fun plan(
        config: DesktopConfig,
        jarPath: String = VdDeploy.JAR_PATH,
        phoneVdWidth: Int = 0,
        phoneVdHeight: Int = 0,
    ): VdDeploy.DeployPlan {
        val encodeWidth = HandshakeFactory.evenAlign(config.viewportWidth)
        val encodeHeight = HandshakeFactory.evenAlign(config.viewportHeight)
        // 手机下发的 VD 尺寸优先（防 IME 裁切）；0/负值时退回视口。
        val vdWidth = if (phoneVdWidth > 0) DimAlign.even(phoneVdWidth) else encodeWidth
        val vdHeight = if (phoneVdHeight > 0) DimAlign.even(phoneVdHeight) else encodeHeight
        return VdDeploy.buildDeployPlan(
            jarPath = jarPath.ifBlank { VdDeploy.JAR_PATH },
            logPath = VdDeploy.LOG_PATH,
            vdWidth = vdWidth,
            vdHeight = vdHeight,
            dpi = resolveDpi(config, vdWidth, vdHeight),
            encodeWidth = encodeWidth,
            encodeHeight = encodeHeight,
            phoneHost = "127.0.0.1",
            fps = config.targetFps,
            bitrate = config.bitrate,
            background = false,
        )
    }

    /**
     * 本次部署用的 DPI。
     *
     * 握手里的 `dpiOverride` 优先，且**按协议区间夹紧**（[VdDeployArgs.coerceDpiOverride]）：
     * 桌面端以前直通不夹紧，同一个 UI 值在 Shizuku 路径落到 480、在 ADB 路径原样发 640
     * （audit WIN-08）。为 0（自动）时用 [VideoConfig.calculateOptimalDpi] —— 与手机侧
     * `VdDimensions` 的自动标定同一个函数，而不是直接拿 `screenDpi` 顶上去。
     */
    fun resolveDpi(config: DesktopConfig, vdWidth: Int, vdHeight: Int): Int {
        val override = VdDeployArgs.coerceDpiOverride(config.dpiOverride)
        return if (override > 0) {
            override
        } else {
            VideoConfig.calculateOptimalDpi(vdWidth, vdHeight, config.screenDpi)
        }
    }
}
