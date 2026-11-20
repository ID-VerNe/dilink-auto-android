package com.dilinkauto.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 电量兜底上报的相位对齐（issue #160，红基线）。
 *
 * `PhoneBatteryReporter` 的心跳原来是**相对 `start()` 的固定 60s 网格**
 * （`PhoneBatteryReporter.kt:113-118` 的 `delay(BatteryReportGate.HEARTBEAT_MS)`），
 * 而兜底判据是**相对上次成功上报**（`BatteryReportGate.kt:39-40`）。两个基准不同，
 * 任意一次广播触发的 off-grid 成功上报都会把兜底推到再下一个 tick：
 *
 * ```
 * gap = 60s + p     // p = 上一次成功上报到下一个 tick 的剩余相位
 * ```
 *
 * 红基线：把 [nextTickAt] 换成「以 0 为原点的固定网格」——
 * `(nowMs / HEARTBEAT_MS + 1) * HEARTBEAT_MS`（与 base 的 `delay(HEARTBEAT_MS)` 等价）——
 * 本文件首个用例在 base `a18015b5` 上红在
 * `相位 p=1000ms 时相邻上报最长间隔 61000ms 超过 60000ms`。
 * 修复后 tick 改由 [BatteryReportGate.msUntilHeartbeat] 给出，落回 `lastSentAtMs + 60s`。
 */
class BatteryHeartbeatPhaseTest {

    /** 下一次 tick 的时刻：按「距上次成功上报」重算的剩余延时（修复后的 reporter 节拍）。 */
    private fun nextTickAt(gate: BatteryReportGate, nowMs: Long): Long =
        nowMs + gate.msUntilHeartbeat(nowMs)

    /**
     * 复演 reporter 的兜底循环，返回所有成功上报的时刻。
     *
     * t=0 是 `start()`（也是首个 tick 的基准），sticky 广播立刻成功上报一次；
     * 随后在 t = 60s - [phaseMs] 发生一次**广播触发**的 off-grid 成功上报
     * （例如充电位翻转后又翻回），再继续按 tick 走兜底。
     */
    private fun simulateSendTimes(phaseMs: Long, horizonMs: Long = 600_000L): List<Long> {
        val gate = BatteryReportGate()
        val sends = ArrayList<Long>()
        var now = 0L
        gate.markSent(level = 85, charging = false, nowMs = now)
        sends += now

        val offGridAt = BatteryReportGate.HEARTBEAT_MS - phaseMs
        var offGridDone = false
        while (true) {
            val tickAt = nextTickAt(gate, now)
            if (!offGridDone && offGridAt <= tickAt) {
                now = offGridAt
                gate.markSent(level = 85, charging = false, nowMs = now)
                sends += now
                offGridDone = true
                if (now >= horizonMs) break
                continue
            }
            now = tickAt
            if (now >= horizonMs) break
            if (gate.shouldSend(level = 85, charging = false, nowMs = now)) {
                gate.markSent(level = 85, charging = false, nowMs = now)
                sends += now
            }
        }
        return sends
    }

    @Test
    fun `off-grid 成功上报后兜底间隔仍不超过 60s`() {
        for (phaseMs in listOf(1_000L, 25_000L, 54_000L, 59_000L)) {
            val sends = simulateSendTimes(phaseMs)
            val gaps = sends.zipWithNext { a, b -> b - a }
            val maxGap = gaps.max()
            assertTrue(
                "相位 p=${phaseMs}ms 时相邻上报最长间隔 ${maxGap}ms 超过 60000ms：" +
                    "sends=$sends",
                maxGap <= BatteryReportGate.HEARTBEAT_MS,
            )
        }
    }

    @Test
    fun `off-grid 上报后下一个 tick 恰好落在 60s 之后`() {
        for (phaseMs in listOf(1_000L, 25_000L, 54_000L, 59_000L)) {
            val sends = simulateSendTimes(phaseMs)
            // sends = [0, offGrid, offGrid + 60s, ...]：相位差不再叠加到兜底间隔上。
            assertEquals(
                "相位 p=${phaseMs}ms：off-grid 上报后应在 60s 内触发兜底，sends=$sends",
                sends[1] + BatteryReportGate.HEARTBEAT_MS,
                sends[2],
            )
        }
    }

    @Test
    fun `从未成功上报时等满一个兜底周期`() {
        val gate = BatteryReportGate()
        assertEquals(
            BatteryReportGate.HEARTBEAT_MS,
            gate.msUntilHeartbeat(nowMs = 123_456L),
        )
    }

    @Test
    fun `已到点但本轮没发出去时按最小步长重试`() {
        val gate = BatteryReportGate()
        gate.markSent(level = 85, charging = false, nowMs = 10_000)
        // 到点（含超过一个周期）不再空转，也不至于再等 60s。
        assertEquals(
            BatteryReportGate.MIN_HEARTBEAT_TICK_MS,
            gate.msUntilHeartbeat(nowMs = 70_000),
        )
        assertEquals(
            BatteryReportGate.MIN_HEARTBEAT_TICK_MS,
            gate.msUntilHeartbeat(nowMs = 130_000),
        )
    }

    @Test
    fun `时钟回拨时钳到一个完整周期`() {
        val gate = BatteryReportGate()
        gate.markSent(level = 85, charging = false, nowMs = 50_000)
        assertEquals(
            BatteryReportGate.HEARTBEAT_MS,
            gate.msUntilHeartbeat(nowMs = 40_000),
        )
    }

    @Test
    fun `剩余延时等于到点前的真实余量`() {
        val gate = BatteryReportGate()
        gate.markSent(level = 85, charging = false, nowMs = 3_000)
        assertEquals(59_900L, gate.msUntilHeartbeat(nowMs = 3_100))
        assertEquals(1_900L, gate.msUntilHeartbeat(nowMs = 61_100))
    }
}
