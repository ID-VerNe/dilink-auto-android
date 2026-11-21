package com.dilinkauto.client.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 物理屏恢复的判定回归（issue #23）。
 *
 * 现场证据（`client.log:108` + `vd-server.log:60`）：`cmd display power-reset`
 * 在 Flyme 上返回 `Unknown command: power-reset`，手机端却无条件打印
 * `Physical display restored via Shizuku`；同一会话 `wakeUp()` 反射失败也只打 D。
 * 于是三层兜底里两层是死的，日志却显示成功。
 *
 * 本测试锁住两点：
 *  1. power-reset 不可用 + wakeUp 不可用时，**Activity 兜底仍必须执行**；
 *  2. 日志里**不得**出现任何成功文案（restored / succeeded / 已点亮）。
 */
class DisplayRestoreSequenceTest {

    /** 内存日志：记录级别与文本，便于断言“没打成功”。 */
    private class RecordingLog : RestoreLog {
        val lines = mutableListOf<Pair<Char, String>>()
        override fun i(msg: String) { lines += 'I' to msg }
        override fun w(msg: String) { lines += 'W' to msg }
        override fun d(msg: String) { lines += 'D' to msg }
        fun texts(): List<String> = lines.map { it.second }
    }

    private class Harness {
        val log = RecordingLog()
        val commands = mutableListOf<String>()
        var activityLaunches = 0
        val capabilityCache = PowerResetCapabilityCache()

        fun sequence(
            // 默认 ROM 支持 power-reset，避免 issue #221 的能力探测把既有用例短路。
            shellAnswer: (String) -> ShellOutcome? = { cmd ->
                if (cmd.startsWith("cmd display help")) {
                    ShellOutcome("  power-reset DISPLAY_ID\n__DILINK_RC=0", "")
                } else {
                    ShellOutcome("__DILINK_RC=0", "")
                }
            },
            wakeUp: () -> Unit = {},
        ) = DisplayRestoreSequence(
            shell = { cmd ->
                commands += cmd
                shellAnswer(cmd)
            },
            wakeUpFn = wakeUp,
            launchActivityFn = { activityLaunches++ },
            wakeLockFn = {},
            log = log,
            capabilityCache = capabilityCache,
        )
    }

    private val successWords = listOf("restored", "succeeded", "已点亮")

    private fun List<String>.hasSuccessWord(): Boolean =
        any { line -> successWords.any { line.contains(it, ignoreCase = true) } }

    @Test
    fun `power reset unknown and wakeUp unavailable still launches activity without success log`() {
        val h = Harness()
        val seq = h.sequence(
            shellAnswer = { cmd ->
                when {
                    cmd.startsWith("cmd display help") -> ShellOutcome("  power-reset DISPLAY_ID\n__DILINK_RC=0", "")
                    cmd.contains("power-reset") -> ShellOutcome("Unknown command: power-reset", "")
                    else -> ShellOutcome("__DILINK_RC=0", "")
                }
            },
            wakeUp = {
                throw NoSuchMethodException(
                    "android.os.PowerManager.wakeUp [long, int, class java.lang.String]"
                )
            },
        )

        val report = seq.run(null)

        assertFalse("power-reset 返回 Unknown command 时不得记为成功", report.powerResetOk)
        assertFalse("wakeUp 抛异常时不得记为成功", report.wakeUpOk)
        assertEquals("Activity 兜底必须执行", 1, h.activityLaunches)
        assertTrue(
            "wakeUp 失败后必须降级 KEYCODE_WAKEUP：${h.commands}",
            h.commands.any { it.contains("input keyevent 224") },
        )
        assertFalse(
            "失败场景不得出现任何成功文案：${h.log.texts()}",
            h.log.texts().hasSuccessWord(),
        )
        assertTrue(
            "power-reset 失败必须打 W 级日志：${h.log.lines}",
            h.log.lines.any { it.first == 'W' && it.second.contains("power-reset") },
        )
    }

    @Test
    fun `power reset success is logged and keyevent fallback is skipped`() {
        val h = Harness()
        val seq = h.sequence(
            shellAnswer = { cmd ->
                if (cmd.startsWith("cmd display help")) {
                    ShellOutcome("  power-reset DISPLAY_ID\n__DILINK_RC=0", "")
                } else {
                    ShellOutcome("__DILINK_RC=0", "")
                }
            },
        )
        val report = seq.run(null)

        assertTrue("rc=0 应判定成功", report.powerResetOk)
        assertTrue("wakeUp 成功", report.wakeUpOk)
        assertTrue(
            "wakeUp 成功时不应再发 keyevent 兜底：${h.commands}",
            h.commands.none { it.contains("input keyevent 224") },
        )
        assertTrue("成功必须打 I 级日志", h.log.texts().hasSuccessWord())
        assertEquals("Activity 兜底仍照常执行", 1, h.activityLaunches)
    }

    @Test
    fun `unknown command is a failure even when rc is zero`() {
        val h = Harness()
        val report = h.sequence(
            shellAnswer = { cmd ->
                when {
                    cmd.startsWith("cmd display help") -> ShellOutcome("  power-reset DISPLAY_ID\n__DILINK_RC=0", "")
                    else -> ShellOutcome("Unknown command: power-reset\n__DILINK_RC=0", "")
                }
            },
        ).run(null)

        assertFalse("文本含 Unknown command 时，rc=0 也不能算成功", report.powerResetOk)
    }

    @Test
    fun `shizuku unavailable still falls through to the activity layer`() {
        val h = Harness()
        val report = h.sequence(
            shellAnswer = { null },
            wakeUp = { throw NoSuchMethodException("wakeUp") },
        ).run(null)

        assertFalse(report.powerResetOk)
        assertEquals("Shizuku 不可用时 Activity 兜底仍必须执行", 1, h.activityLaunches)
        assertFalse("没有任何显示层成功时不得打成功文案", h.log.texts().hasSuccessWord())
    }

    @Test
    fun `activity failure is reported and does not abort the wakelock layer`() {
        var wakeLockCalled = false
        val log = RecordingLog()
        val seq = DisplayRestoreSequence(
            shell = { ShellOutcome("__DILINK_RC=0", "") },
            wakeUpFn = {},
            launchActivityFn = { throw SecurityException("background activity launch blocked") },
            wakeLockFn = { wakeLockCalled = true },
            log = log,
        )

        val report = seq.run(null)

        assertFalse("Activity 启动抛异常时不得记为成功", report.activityLaunched)
        assertTrue("WakeLock 层必须继续执行", wakeLockCalled)
        assertTrue(report.wakeLockHeld)
    }

    @Test
    fun `rom without power-reset skips the command instead of waiting`() {
        // issue #221：现场 6/6 会话都白等 3.4–4.2s 才拿到 `Unknown command`。
        // help 已明确没有 power-reset 时，不得再下发该命令，并要留下跳过原因。
        val h = Harness()
        val report = h.sequence(
            shellAnswer = { cmd ->
                when {
                    cmd.startsWith("cmd display help") ->
                        ShellOutcome("  enable-display DISPLAY_ID\n  disable-display DISPLAY_ID\n__DILINK_RC=0", "")
                    cmd.contains("power-reset") -> ShellOutcome("Unknown command: power-reset\n__DILINK_RC=255", "")
                    else -> ShellOutcome("__DILINK_RC=0", "")
                }
            },
        ).run(null)

        assertFalse(report.powerResetOk)
        assertTrue(
            "ROM 不支持时不得再下发 power-reset：${h.commands}",
            h.commands.none { it.contains("power-reset 0") },
        )
        assertTrue(
            "必须记录跳过原因：${h.log.lines}",
            h.log.lines.any { it.first == 'W' && it.second.contains("skipped") && it.second.contains("power-reset") },
        )
    }

    @Test
    fun `capability probe runs once per process cache`() {
        val h = Harness()
        h.sequence().run(null)
        // 模拟 Service 重建：同一个进程级缓存，新的 sequence 实例。
        h.sequence().run(null)

        val probes = h.commands.count { it == "cmd display help" }
        assertEquals("能力探测必须命中进程缓存，只探测一次：${h.commands}", 1, probes)
    }

    @Test
    fun `runtime unknown command is cached for the next restore`() {
        // help 拿不到（UNKNOWN）时仍会尝试 power-reset；运行期返回 Unknown command
        // 就是负结论，必须写进缓存，下一次恢复不再白等（issue #221）。
        var helpQueries = 0
        val log = RecordingLog()
        // 两次恢复必须共享同一个缓存：这里把 cache 提出来，模拟进程级实例。
        val cache = PowerResetCapabilityCache()
        fun sharedSequence() = DisplayRestoreSequence(
            shell = { cmd ->
                when {
                    cmd.startsWith("cmd display help") -> { helpQueries++; null }
                    cmd.contains("power-reset") -> ShellOutcome("Unknown command: power-reset\n__DILINK_RC=255", "")
                    else -> ShellOutcome("__DILINK_RC=0", "")
                }
            },
            wakeUpFn = {},
            launchActivityFn = {},
            wakeLockFn = {},
            log = log,
            capabilityCache = cache,
        )

        assertFalse("首次没有 help，power-reset 运行期失败", sharedSequence().run(null).powerResetOk)
        assertFalse("第二次仍不得记为成功", sharedSequence().run(null).powerResetOk)
        assertEquals("第二次应命中运行期负缓存，不再探测 help", 1, helpQueries)
    }
}
