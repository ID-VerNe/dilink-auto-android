package com.dilinkauto.desktop.deploy

import com.dilinkauto.desktop.DesktopConfig
import com.dilinkauto.protocol.VdDeploy
import com.dilinkauto.protocol.VdDeployExecutor
import com.dilinkauto.protocol.VdProbeResult
import com.dilinkauto.protocol.vdRunDeploySequence
import kotlinx.coroutines.runBlocking

/**
 * 无 Shizuku 时的 ADB 部署路径（Phase 5c）。
 *
 * 流程与车机端 `VdServerDeployer` 完全对应，只是把 `TcpAdbConnection` 换成
 * 本地 `adb.exe`：
 *   1. `adb connect <host>:<port>`
 *   2. kill 旧引擎，并**等它真的退出**（两个引擎抢同一个 VD/9638-9639，抢输的
 *      那个永远不会跑 cleanup()，每轮重连泄漏一个 VirtualDisplay）
 *   3. 前台启动新引擎（`exec app_process`，不 `&`）
 *
 * 第 2/3 步的编排 —— 包括"连续两次判退出才认账"的收敛规则与超时兜底强杀 ——
 * 现在由 protocol-core 的 `vdRunDeploySequence` 单点定义，与车机 USB/TCP 两条
 * 路径、手机 Shizuku 路径共用同一实现。
 *
 * 不需要往手机推 jar：`/sdcard/DiLinkAuto/vd-server.jar` 由手机侧自己落盘。
 *
 * 许可/分发提示：依赖用户机器上的 `adb.exe`（Android SDK Platform-Tools），
 * 不打进 app-image。找不到时就报"未安装 adb"，镜像主流程不受影响。
 */
class AdbDeployer(
    private val runner: AdbRunner = ProcessAdbRunner(),
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val now: () -> Long = System::currentTimeMillis,
) {

    /**
     * 部署一次 VD server。返回 false 时上层应提示用户走 Shizuku 或检查 adb 环境，
     * 但**不要**反复重试 —— 失败原因（没装 adb / 没开无线调试 / 不同网段）重试也不会变。
     *
     * @param jarPath 手机侧 vd-server.jar 的路径，取自握手响应的 `vdServerJarPath`。
     * @param phoneVdWidth 手机侧握手响应建议的 VirtualDisplay 宽（防 IME 裁切）；
     *   0 = 旧版手机未下发，回退视口尺寸。见 [AdbDeploy.plan]。
     * @param phoneVdHeight 同上，高。
     */
    fun deploy(
        config: DesktopConfig,
        adbPort: Int,
        jarPath: String,
        phoneVdWidth: Int = 0,
        phoneVdHeight: Int = 0,
        log: (String) -> Unit,
    ): Boolean {
        val serial = AdbDeploy.serial(config.phoneHost, adbPort)
        log("ADB 部署：connect $serial")
        val connected = runner.run(
            AdbDeploy.connectArgs(config.phoneHost, adbPort),
            waitForExit = true,
            timeoutMs = CONNECT_TIMEOUT_MS,
            onOutput = log,
        )
        if (!connected) {
            log("ADB connect 失败 —— 请确认手机已开启「无线调试」且与 PC 同网段")
            return false
        }

        val plan = AdbDeploy.plan(config, jarPath = jarPath, phoneVdWidth = phoneVdWidth, phoneVdHeight = phoneVdHeight)
        log("ADB 部署：kill 旧引擎")

        val executor = object : VdDeployExecutor {
            override suspend fun shellSync(command: String) {
                runner.run(AdbDeploy.shellArgs(serial, command), true, SHELL_TIMEOUT_MS, log)
            }

            override suspend fun launch(command: String): Boolean = runner.run(
                AdbDeploy.shellArgs(serial, command),
                waitForExit = false,
                timeoutMs = 0,
                onOutput = log,
            )

            override suspend fun probe(): VdProbeResult {
                val alive = runner.run(
                    AdbDeploy.shellArgs(serial, VdDeploy.probeExitCodeCommand),
                    waitForExit = true,
                    timeoutMs = SHELL_TIMEOUT_MS,
                    onOutput = {},
                )
                return if (alive) VdProbeResult.ALIVE else VdProbeResult.GONE
            }
        }

        // 经 runBlocking 驱动共享序列；测试注入的假时钟使等待瞬时完成。
        val outcome = runBlocking {
            vdRunDeploySequence(plan, executor, now = now, sleep = { sleep(it) })
        }
        if (outcome.forcedKill) log("ADB 部署：旧引擎未按时退出，强制 kill")
        log("ADB 部署：启动 VD server（设备侧日志 ${VdDeploy.LOG_PATH}）")
        if (!outcome.launched) log("ADB 部署：VD server 启动失败")
        return outcome.launched
    }

    /** 结束本端持有的 adb 进程（会连带关掉设备侧引擎的 shell 流，等效于停止 VD server）。 */
    fun close() = runner.killAll()

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val SHELL_TIMEOUT_MS = 5_000L
    }
}
