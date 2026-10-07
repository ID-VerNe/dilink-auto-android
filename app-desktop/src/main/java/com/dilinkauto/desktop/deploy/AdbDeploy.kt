package com.dilinkauto.desktop.deploy

import com.dilinkauto.desktop.DesktopConfig
import com.dilinkauto.desktop.HandshakeFactory
import com.dilinkauto.protocol.Ports
import com.dilinkauto.protocol.VdDeploy

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
     * 视口用握手时的偶数对齐结果（H.264 编码器要求偶数边长），DPI 优先用
     * 握手里的 `dpiOverride`。`phoneHost` 固定 127.0.0.1：VD server 与手机 app
     * 同机，推流目标就是本机回环（与车机端 `VdServerDeployer` 一致）。
     *
     * `background = false` 是硬约束：只有 `exec app_process` 才能让 adb shell 流
     * 在引擎存活期间保持附着；`&` 后台化会被 adbd 回收，表现为日志 0 字节、进程秒死。
     *
     * @param jarPath 用握手响应里的 `vdServerJarPath`（手机侧刚校验过 CRC 的那份）；
     *   空则退回 `VdDeploy.JAR_PATH`。
     */
    fun plan(
        config: DesktopConfig,
        dpi: Int = resolveDpi(config),
        jarPath: String = VdDeploy.JAR_PATH,
    ): VdDeploy.DeployPlan {
        val width = HandshakeFactory.evenAlign(config.viewportWidth)
        val height = HandshakeFactory.evenAlign(config.viewportHeight)
        return VdDeploy.buildDeployPlan(
            jarPath = jarPath.ifBlank { VdDeploy.JAR_PATH },
            logPath = VdDeploy.LOG_PATH,
            vdWidth = width,
            vdHeight = height,
            dpi = dpi,
            encodeWidth = width,
            encodeHeight = height,
            phoneHost = "127.0.0.1",
            fps = config.targetFps,
            bitrate = config.bitrate,
            background = false,
        )
    }

    /** 握手里的 `dpiOverride` 优先；为 0（自动）时用报给手机的 `screenDpi`。 */
    fun resolveDpi(config: DesktopConfig): Int =
        if (config.dpiOverride > 0) config.dpiOverride else config.screenDpi
}
