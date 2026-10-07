package com.dilinkauto.desktop.deploy

import com.dilinkauto.desktop.DesktopConfig
import com.dilinkauto.protocol.VdDeploy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [AdbDeployer] 命令序列单测。
 *
 * 注入假 [AdbRunner] + 假时钟：CI 上不会装 adb，也不该起真机进程；同时假时钟让
 * "等旧引擎退出"这段真实要 3 秒的轮询在测试里瞬时完成。
 */
class AdbDeployerTest {

    private class FakeTime {
        var millis = 0L
        fun now(): Long = millis
        fun sleep(ms: Long) {
            millis += ms
        }
    }

    /** 记录每条 adb 命令，并按命令种类返回可配置结果。 */
    private class FakeRunner(
        private val connectResult: Boolean = true,
        private val launchResult: Boolean = true,
        /** 传给探活命令的"引擎是否存活"判定。 */
        private val probeAlive: () -> Boolean = { false },
    ) : AdbRunner {
        data class Call(val args: List<String>, val waitForExit: Boolean, val timeoutMs: Long)

        val calls = CopyOnWriteArrayList<Call>()
        var killAllCalled = 0
            private set

        override fun run(
            args: List<String>,
            waitForExit: Boolean,
            timeoutMs: Long,
            onOutput: (String) -> Unit,
        ): Boolean {
            calls += Call(args, waitForExit, timeoutMs)
            val command = args.getOrNull(3)
            return when {
                args.firstOrNull() == "connect" -> connectResult
                command == VdDeploy.killCommand -> true
                command == VdDeploy.probeExitCodeCommand -> probeAlive()
                command == VdDeploy.stopCommand -> true
                args.lastOrNull()?.contains("app_process") == true -> launchResult
                else -> true
            }
        }

        override fun killAll() {
            killAllCalled++
        }

        /** 所有 `shell` 命令的命令体（去掉了 `-s <serial> shell` 外壳）。 */
        fun shellCommands(): List<String> =
            calls.filter { it.args.getOrNull(2) == "shell" }.map { it.args[3] }

        fun probeCalls(): Int = shellCommands().count { it == VdDeploy.probeExitCodeCommand }

        fun launchCall(): Call = calls.last { it.args.last().contains("app_process") }
    }

    private fun config() = DesktopConfig(
        phoneHost = "192.168.3.206",
        viewportWidth = 1280,
        viewportHeight = 720,
    )

    private fun deployer(runner: FakeRunner, time: FakeTime = FakeTime()) =
        AdbDeployer(runner = runner, sleep = { time.sleep(it) }, now = { time.now() })

    @Test
    fun `正常流程按 connect - kill - 探活 - 前台启动 的顺序执行`() {
        val runner = FakeRunner()
        val logs = mutableListOf<String>()

        val ok = deployer(runner).deploy(config(), adbPort = 5555, jarPath = VdDeploy.JAR_PATH) { logs.add(it) }

        assertTrue(ok)
        val shells = runner.shellCommands()
        // kill → 探活 ×2（连续两次"已退出"） → launch
        assertEquals(listOf(VdDeploy.killCommand, VdDeploy.probeExitCodeCommand, VdDeploy.probeExitCodeCommand), shells.dropLast(1))
        assertTrue("最后一条应是启动命令: ${shells.last()}", shells.last().contains("exec app_process"))

        // 启动命令必须保持前台（waitForExit=false），否则 adb shell 流一关引擎就被回收
        assertFalse("启动必须异步保持附着", runner.launchCall().waitForExit)
        assertEquals(listOf("connect", "192.168.3.206:5555"), runner.calls.first().args)
    }

    @Test
    fun `connect 失败即中止，不会去 kill 或启动`() {
        val runner = FakeRunner(connectResult = false)

        val ok = deployer(runner).deploy(config(), 5555, VdDeploy.JAR_PATH) {}

        assertFalse(ok)
        assertEquals(1, runner.calls.size)
        assertEquals(listOf("connect", "192.168.3.206:5555"), runner.calls.single().args)
    }

    @Test
    fun `必须连续两次判定退出才认账`() {
        // 第一次探活仍报存活（上一次传输抖动/进程正在退出），后两次才报已退出
        val probes = ArrayDeque(listOf(true, false, false))
        val runner = FakeRunner(probeAlive = { probes.removeFirstOrNull() ?: false })

        deployer(runner).deploy(config(), 5555, VdDeploy.JAR_PATH) {}

        assertEquals("应探活 3 次（存活→退出→退出）", 3, runner.probeCalls())
    }

    @Test
    fun `旧引擎迟迟不退时会补一次强制 kill 再启动`() {
        // 探活永远报存活 → 等到超时 → 走 stopCommand 兜底
        val runner = FakeRunner(probeAlive = { true })

        val ok = deployer(runner).deploy(config(), 5555, VdDeploy.JAR_PATH) {}

        assertTrue(ok)
        assertTrue("应补发 stopCommand: ${runner.shellCommands()}", runner.shellCommands().contains(VdDeploy.stopCommand))
        // 无论等多久，最后一定是启动命令，不能把会话卡在"等旧引擎退出"
        assertTrue(runner.shellCommands().last().contains("exec app_process"))
    }

    @Test
    fun `close 会杀掉所有挂着的长驻进程`() {
        val runner = FakeRunner()
        deployer(runner).close()
        assertEquals(1, runner.killAllCalled)
    }
}
