package com.dilinkauto.desktop.deploy

import com.dilinkauto.desktop.DesktopConfig
import com.dilinkauto.protocol.VdDeploy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * adb 进程的执行抽象。抽出来是为了让 [AdbDeployer] 的命令序列可以脱离真实
 * `adb.exe` 单测（CI 上不会装 adb，也不该起真机进程）。
 */
interface AdbRunner {

    /**
     * 跑一条 adb 命令。
     *
     * @param waitForExit true：等进程结束，返回值表示"退出码为 0"；超时会强杀并返回 false。
     *   false：启动即返回，进程保持附着不关闭 —— 这正是 VD server 需要的行为
     *   （`exec app_process` 接管 adb shell 流，流一关引擎就被回收）。
     * @param onOutput 进程输出行（stdout+stderr 已合并），用于写进 desktop.log。
     */
    fun run(args: List<String>, waitForExit: Boolean, timeoutMs: Long, onOutput: (String) -> Unit): Boolean

    /** 杀掉所有还挂着的长驻进程（会话结束 / 重启 / 退出时调用）。 */
    fun killAll()
}

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

/**
 * 真实实现：用 [ProcessBuilder] 起 `adb.exe`。
 *
 * `waitForExit = false` 的进程（VD server 的 launch）会被一直持有，直到
 * [killAll] —— 这不是资源泄漏，而是协议要求：本地 adb 进程一退出，设备侧的
 * shell 流就断，`exec app_process` 起来的引擎会被 adbd 回收。
 */
class ProcessAdbRunner(
    private val adbPath: String = defaultAdbPath(),
) : AdbRunner {

    private val streams = CopyOnWriteArrayList<Process>()

    override fun run(
        args: List<String>,
        waitForExit: Boolean,
        timeoutMs: Long,
        onOutput: (String) -> Unit,
    ): Boolean = try {
        val process = ProcessBuilder(listOf(adbPath) + args)
            .redirectErrorStream(true)
            .start()
        // 输出一律在独立线程里排空：同步命令也可能在超时前一直不产出，
        // 在读线程里等待会吃掉 waitFor 的超时语义。
        Thread({ drain(process, onOutput) }, "adb-output").apply {
            isDaemon = true
            start()
        }
        if (waitForExit) {
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) process.destroyForcibly()
            finished && process.exitValue() == 0
        } else {
            streams += process
            true
        }
    } catch (_: Exception) {
        // 找不到 adb / 权限不足 / 进程启动失败，一律当作命令失败
        false
    }

    override fun killAll() {
        streams.forEach { runCatching { it.destroy() } }
        streams.clear()
    }

    private fun drain(process: Process, onOutput: (String) -> Unit) {
        val reader = process.inputStream.bufferedReader()
        while (true) {
            val line = runCatching { reader.readLine() }.getOrNull() ?: return
            onOutput(line)
        }
    }

    companion object {
        /** 允许用 `DILINK_ADB` 指定 adb 路径（SDK 没进 PATH 时最省事）。 */
        fun defaultAdbPath(): String =
            System.getenv("DILINK_ADB")?.takeIf { it.isNotBlank() } ?: "adb"
    }
}
