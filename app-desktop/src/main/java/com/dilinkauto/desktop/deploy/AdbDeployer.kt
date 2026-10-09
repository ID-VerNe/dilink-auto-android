package com.dilinkauto.desktop.deploy

import com.dilinkauto.desktop.DesktopConfig
import com.dilinkauto.protocol.VdDeploy
import com.dilinkauto.protocol.VdDeployExecutor
import com.dilinkauto.protocol.VdProbeResult
import com.dilinkauto.protocol.vdRunDeploySequence
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex

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
 *
 * ── 并发与可取消（audit D-M6）──
 * [deploy] 是挂起函数：序列里的等待经 kotlinx 的 `delay`（可被协程取消），
 * 而不是 `Thread.sleep`。整段再套一把 [Mutex] —— "会话中途 restart"时旧的部署
 * 协程可能还在对**共享的** [AdbRunner] 跑命令（旧协程杀 adb 进程 / 新协程已经
 * 起了自己的进程），串行化保证同一时刻只有一轮部署。
 */
class AdbDeployer(
    private val runner: AdbRunner = ProcessAdbRunner(),
    private val now: () -> Long = System::currentTimeMillis,
    /**
     * 部署序列里的等待 seam（audit D-M6）：生产是 [delay]（可取消），测试注入 no-op
     * 让等待瞬时完成。
     *
     * 注入的是**已声明的挂起函数引用**（如 `::delay`），而不是"类型为 suspend 的
     * lambda 字面量"：后者在这个版本的编译器后端上会让 continuation lowering 崩
     * （AddContinuationLowering 的 assertion）。
     */
    private val sleep: (suspend (Long) -> Unit) = ::delay,
) {

    /** 单部署串行化（audit D-M6）：restart 期间新旧两代不能同时跑部署序列。 */
    private val deployMutex = Mutex()

    /**
     * 部署一次 VD server。返回 false 时上层应提示用户走 Shizuku 或检查 adb 环境；
     * 但**不要**反复重试 —— 失败原因（没装 adb / 没开无线调试 / 不同网段）重试也不会变。
     *
     * @param jarPath 手机侧 vd-server.jar 的路径，取自握手响应的 `vdServerJarPath`。
     *   调用方必须先过白名单校验（见 [AdbDeploy.sanitizeJarPath]，audit D-01）。
     * @param phoneVdWidth 手机侧握手响应建议的 VirtualDisplay 宽（防 IME 裁切）；
     *   0 = 旧版手机未下发，回退视口尺寸。见 [AdbDeploy.plan]（内部会钳制到
     *   [AdbDeploy.MIN_VD_DIM]..[AdbDeploy.MAX_VD_DIM]，audit D-M4）。
     * @param phoneVdHeight 同上，高。
     */
    suspend fun deploy(
        config: DesktopConfig,
        adbPort: Int,
        jarPath: String,
        phoneVdWidth: Int = 0,
        phoneVdHeight: Int = 0,
        log: (String) -> Unit,
    ): Boolean {
        // 为什么不用惯用的 `deployMutex.withLock { ... }`：本方法要把 [sleep]
        // （挂起函数类型的值）传给同为 inline 的 `vdRunDeploySequence`——两个
        // inline suspend lambda 套在一起会撞上 Kotlin JVM-IR 后端的
        // AddContinuationLowering assertion（编译期崩，不是运行时问题）。
        // 显式 lock/unlock 语义等价（含异常与取消路径，见 finally），只是绕开那个
        // 编译器 bug。
        deployMutex.lock()
        try {
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

            // 直接挂起驱动共享序列（audit D-M6）：等旧引擎退出的轮询等待走 [sleep]
            // （生产 = kotlinx 的 delay，协程取消时立刻抛 CancellationException 停下），
            // 而不是 Thread.sleep。此前这里包一层 runBlocking + Thread.sleep，部署既
            // 不可取消、restart 期间又会与新一轮部署并发跑同一套共享 runner。
            val outcome = vdRunDeploySequence(plan, executor, now = now, sleep = sleep)
            if (outcome.forcedKill) log("ADB 部署：旧引擎未按时退出，强制 kill")
            // 把最终 argv 打进日志：peer 可控字段（vdWidth/vdHeight/jarPath/adbPort）
            // 的校验结果只能从这里事后查证（audit D-01/D-M4）。
            log("ADB 部署：app_process 参数 ${plan.args}")
            log("ADB 部署：启动 VD server（设备侧日志 ${VdDeploy.LOG_PATH}）")
            if (!outcome.launched) log("ADB 部署：VD server 启动失败")
            return outcome.launched
        } finally {
            deployMutex.unlock()
        }
    }

    /** 结束本端持有的 adb 进程（会连带关掉设备侧引擎的 shell 流，等效于停止 VD server）。 */
    fun close() = runner.killAll()

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val SHELL_TIMEOUT_MS = 5_000L
    }
}
