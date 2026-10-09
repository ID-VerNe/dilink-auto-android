package com.dilinkauto.desktop.deploy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * [ProcessAdbRunner] 的进程生命周期测试（audit WIN-15 ②）。
 *
 * 为什么这组测试重要：`waitForExit = false` 的 launch 进程**必须被一直持有** ——
 * 本地 adb.exe 就是设备侧 `exec app_process` 引擎的存活锚点，进程一退引擎就被
 * adbd 回收（见类 KDoc）。这条语义此前只用 FakeRunner 测过命令序列，真实进程的
 * 持有与回收没有任何覆盖（WIN-02 的藏身处）。
 *
 * 不依赖设备 / adb：用系统自带的长命命令冒充 adb.exe（进程行为与 adb 一致）。
 */
class ProcessAdbRunnerTest {

    /** 长命命令：在本测试的秒级窗口内绝不会自己退出。 */
    private fun longLivedCommand(): Pair<String, List<String>> =
        if (isWindows()) {
            // ping 每秒一个包，30 秒后才结束
            "ping" to listOf("-n", "30", "127.0.0.1")
        } else {
            "sleep" to listOf("30")
        }

    @Test
    fun `launch 进程被持有直到 killAll 才退出`() {
        val (command, args) = longLivedCommand()
        val runner = ProcessAdbRunner(adbPath = command)

        assertTrue(
            "launch 必须返回 true",
            runner.run(args, waitForExit = false, timeoutMs = 0, onOutput = {}),
        )

        val held = runner.heldProcesses()
        assertEquals("launch 的进程必须被持有", 1, held.size)
        assertTrue("持有期间进程应当还活着", held[0].isAlive)

        runner.killAll()

        // destroy 是异步的：等一下确认进程真的退了（这正是 killAllAndAwait 存在的理由）
        assertTrue("killAll 后进程应当退出", held[0].waitFor(2, TimeUnit.SECONDS))
        assertFalse(held[0].isAlive)
        assertEquals("killAll 后不再持有任何进程", 0, runner.heldProcesses().size)
    }

    @Test
    fun `多次 launch 持有的进程会被 killAll 一次清空`() {
        val (command, args) = longLivedCommand()
        val runner = ProcessAdbRunner(adbPath = command)

        assertTrue(runner.run(args, waitForExit = false, timeoutMs = 0, onOutput = {}))
        assertTrue(runner.run(args, waitForExit = false, timeoutMs = 0, onOutput = {}))

        val held = runner.heldProcesses()
        assertEquals(2, held.size)

        runner.killAll()

        held.forEach { assertTrue("killAll 后进程应当退出", it.waitFor(2, TimeUnit.SECONDS)) }
    }

    @Test
    fun `同步命令超时返回 false 且不持有进程`() {
        val (command, args) = longLivedCommand()
        val runner = ProcessAdbRunner(adbPath = command)

        val startedAt = System.nanoTime()
        val ok = runner.run(args, waitForExit = true, timeoutMs = 300, onOutput = {})
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertFalse("超时命令必须返回 false", ok)
        assertTrue("至少要等满超时时间（实际 ${elapsedMs}ms）", elapsedMs >= 300)
        assertTrue("同步命令不进持有列表", runner.heldProcesses().isEmpty())
    }

    @Test
    fun `同步命令的正常输出会被排空到 onOutput`() {
        val (command, args) = if (isWindows()) {
            "cmd" to listOf("/c", "echo", "hello")
        } else {
            "/bin/sh" to listOf("-c", "echo hello")
        }
        val runner = ProcessAdbRunner(adbPath = command)
        val lines = Collections.synchronizedList(mutableListOf<String>())

        val ok = runner.run(args, waitForExit = true, timeoutMs = 10_000, onOutput = { lines += it })

        assertTrue("echo 命令应当成功", ok)
        assertTrue("输出应当包含 hello（实际 $lines）", lines.any { it.trim() == "hello" })
    }

    @Test
    fun `找不到的 adb 路径当作命令失败而不是抛异常`() {
        val runner = ProcessAdbRunner(adbPath = "definitely-not-a-real-adb-${System.nanoTime()}")
        assertFalse(runner.run(listOf("devices"), waitForExit = true, timeoutMs = 1_000, onOutput = {}))
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name")?.startsWith("Windows") == true
}
