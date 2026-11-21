package com.dilinkauto.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 电量上报节流（issue #151，[BatteryReportGate]）。
 *
 * 覆盖四条用户可见的时序规则：
 * - 首次采样必定上报；
 * - 1% 变化过了防抖窗口就发；
 * - 1% 在窗口内来回抖动不发（不刷屏）；
 * - 电量不变时 60s 兜底发一次；
 * - 2% 跳变、charging 翻转不受防抖限制。
 */
class BatteryReportGateTest {

    private val gate = BatteryReportGate()

    @Test
    fun `首次采样必定发送`() {
        assertTrue(gate.shouldSend(level = 86, charging = false, nowMs = 0))
    }

    @Test
    fun `1 百分比变化过了防抖窗口立即发送`() {
        gate.markSent(level = 85, charging = false, nowMs = 0)
        assertTrue(gate.shouldSend(level = 86, charging = false, nowMs = BatteryReportGate.CHANGE_DEBOUNCE_MS))
    }

    @Test
    fun `连续 1 百分比抖动不刷屏`() {
        gate.markSent(level = 85, charging = false, nowMs = 0)
        // 1s 后掉到 84：变化只有 1% 且在 5s 防抖窗口内 → 不发
        assertFalse(gate.shouldSend(level = 84, charging = false, nowMs = 1_000))
        // 2s 后回到 85：仍被防抖挡住
        assertFalse(gate.shouldSend(level = 85, charging = false, nowMs = 2_000))
        // 5s 窗口过后同一变化才放行
        assertTrue(gate.shouldSend(level = 84, charging = false, nowMs = 5_000))
    }

    @Test
    fun `2 百分比跳变不受防抖限制`() {
        gate.markSent(level = 85, charging = false, nowMs = 0)
        assertTrue(gate.shouldSend(level = 83, charging = false, nowMs = 500))
    }

    @Test
    fun `charging 翻转立即发送`() {
        gate.markSent(level = 85, charging = false, nowMs = 0)
        assertTrue(gate.shouldSend(level = 85, charging = true, nowMs = 100))
        gate.markSent(level = 85, charging = true, nowMs = 100)
        assertTrue(gate.shouldSend(level = 85, charging = false, nowMs = 200))
    }

    @Test
    fun `电量不变时 60 秒兜底发送`() {
        gate.markSent(level = 85, charging = false, nowMs = 0)
        // 59.9s：没变化，不发
        assertFalse(gate.shouldSend(level = 85, charging = false, nowMs = 59_900))
        // 60s：兜底心跳
        assertTrue(gate.shouldSend(level = 85, charging = false, nowMs = 60_000))
    }

    @Test
    fun `markSent 之后同样的值不再重复发送`() {
        gate.markSent(level = 85, charging = true, nowMs = 0)
        assertFalse(gate.shouldSend(level = 85, charging = true, nowMs = 1_000))
        gate.markSent(level = 85, charging = true, nowMs = 1_000)
        assertFalse(gate.shouldSend(level = 85, charging = true, nowMs = 2_000))
    }

    @Test
    fun `reset 后重新回到首次采样语义`() {
        gate.markSent(level = 85, charging = false, nowMs = 0)
        gate.reset()
        assertTrue(gate.shouldSend(level = 85, charging = false, nowMs = 1))
    }

    /**
     * 复演 issue #160：一次广播触发的上报落在固定 60s tick 网格之外，
     * 之后的兜底节拍必须按「距上次成功上报」重算，两次上报间隔不得被拉长到 60s + 相位差。
     *
     * 旧实现用 `delay(HEARTBEAT_MS)` 固定网格：相位差 17.3s 时，下一次 tick 落在
     * 60s，而上次上报在 42.7s，间隔只有 17.3s（不发），再下一次 120s → 间隔 77.3s。
     * 这条用例断言最大间隔恰好等于 [BatteryReportGate.HEARTBEAT_MS]。
     */
    @Test
    fun `off-grid 上报后兜底间隔仍是 60 秒而不是 60 秒加相位差`() {
        val heartbeat = BatteryReportGate.HEARTBEAT_MS
        val phase = 17_300L

        // 基准：t=0 已上报一次。
        gate.markSent(level = 85, charging = false, nowMs = 0)

        // 广播在网格外成功上报（60s - 17.3s = 42.7s），电量掉了 2% 触发立即上报。
        val offGridAt = heartbeat - phase
        assertTrue(gate.shouldSend(level = 83, charging = false, nowMs = offGridAt))
        gate.markSent(level = 83, charging = false, nowMs = offGridAt)

        // 之后电量不变，只靠兜底推进；记录每次实际间隔。
        var now = offGridAt
        var lastSent = offGridAt
        var maxGap = 0L
        repeat(3) {
            now += gate.msUntilHeartbeat(now)
            val gap = now - lastSent
            if (gap > maxGap) maxGap = gap
            assertTrue(
                "兜底到点必须放行（now=$now）",
                gate.shouldSend(level = 83, charging = false, nowMs = now)
            )
            gate.markSent(level = 83, charging = false, nowMs = now)
            lastSent = now
        }

        assertEquals(
            "兜底间隔必须回到最迟 60s，不能是 60s + 相位差",
            heartbeat,
            maxGap
        )
    }

    @Test
    fun `msUntilHeartbeat 在时钟回拨时钳到 60 秒`() {
        gate.markSent(level = 85, charging = false, nowMs = 100_000)
        // 时钟回拨到 0：剩余量 160s 被钳到 heartbeat，而不是拖出更长的等待。
        assertEquals(BatteryReportGate.HEARTBEAT_MS, gate.msUntilHeartbeat(0))
    }
}
