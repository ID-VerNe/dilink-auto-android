package com.dilinkauto.desktop.deploy

import com.dilinkauto.desktop.DesktopConfig
import com.dilinkauto.protocol.VdDeploy

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
     */
    fun deploy(config: DesktopConfig, adbPort: Int, jarPath: String, log: (String) -> Unit): Boolean {
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

        val plan = AdbDeploy.plan(config, jarPath = jarPath)
        log("ADB 部署：kill 旧引擎")
        runner.run(AdbDeploy.shellArgs(serial, plan.killCommand), true, SHELL_TIMEOUT_MS, log)
        if (!awaitEngineExit(serial)) {
            log("ADB 部署：旧引擎未按时退出，强制 kill")
            runner.run(AdbDeploy.shellArgs(serial, VdDeploy.stopCommand), true, SHELL_TIMEOUT_MS, log)
            awaitEngineExit(serial)
        }

        log("ADB 部署：启动 VD server（设备侧日志 ${VdDeploy.LOG_PATH}）")
        val started = runner.run(
            AdbDeploy.shellArgs(serial, plan.launchCommand),
            waitForExit = false,
            timeoutMs = 0,
            onOutput = log,
        )
        if (!started) log("ADB 部署：VD server 启动失败")
        return started
    }

    /** 结束本端持有的 adb 进程（会连带关掉设备侧引擎的 shell 流，等效于停止 VD server）。 */
    fun close() = runner.killAll()

    /**
     * 轮询"引擎是否已退出"。探活命令用**退出码**表达（0=存活 / 1=已退出），
     * 所以 `run` 返回 false 就是"已退出"——但传输失败也返回 false，故要求连续
     * 两次都是"已退出"才认账。
     */
    private fun awaitEngineExit(serial: String, timeoutMs: Long = EXIT_WAIT_MS): Boolean {
        val deadline = now() + timeoutMs
        var consecutiveGone = 0
        while (now() < deadline) {
            val alive = runner.run(
                AdbDeploy.shellArgs(serial, VdDeploy.probeExitCodeCommand),
                waitForExit = true,
                timeoutMs = SHELL_TIMEOUT_MS,
                onOutput = {},
            )
            if (alive) {
                consecutiveGone = 0
            } else if (++consecutiveGone >= 2) {
                return true
            }
            sleep(POLL_MS)
        }
        return false
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val SHELL_TIMEOUT_MS = 5_000L

        /** 等旧引擎退出的总时长：与车机端 `waitForVdServerExit` 的 3s 一致。 */
        private const val EXIT_WAIT_MS = 3_000L
        private const val POLL_MS = 150L
    }
}