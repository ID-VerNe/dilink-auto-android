package com.dilinkauto.protocol

import kotlin.math.abs

/**
 * 手机端电量上报的节流状态机（issue #151）。
 *
 * 放在 protocol-core 而不是 app-client：它只依赖整数时间戳，不碰 Android API，
 * 因此「1% 抖动不刷屏」「60s 兜底」这类时序规则可以在 JVM 上钉死。
 *
 * 规则（按优先级）：
 * 1. 从未发送过 → 立即发（车机一进镜像页就有值）。
 * 2. 距上次已发 ≥ [heartbeatMs]（60s）→ 发，即使电量没变（证明手机还活着，
 *    也覆盖「电量长期不变时广播根本不触发」的情况）。心跳节拍由 [msUntilHeartbeat]
 *    对齐到「上次成功上报」，保证兜底间隔**最迟** 60s（issue #160）。
 * 3. charging 位翻转 → 立即发（插拔 USB 是用户最关心的变化）。
 * 4. 电量变化 ≥ [IMMEDIATE_DELTA_PERCENT]（2%）→ 立即发。
 * 5. 电量只变 1% → 距上次已发 ≥ [changeDebounceMs]（5s）才发；否则丢弃。
 *    这一条专门压掉「充电到某个百分比附近来回跳 1%」造成的刷屏。
 *
 * [shouldSend] 只做判定，不改状态；调用方必须在**发送成功之后**调用 [markSent]。
 * 发送失败时保持旧状态，下一轮广播 / 60s 兜底会重试 —— 失败被静默吞掉正是
 * issue #41 的教训。非线程安全，调用方须串行使用（本仓库里只有广播回调与
 * 单线程定时协程两个入口，且都在主线程）。
 */
class BatteryReportGate(
    private val heartbeatMs: Long = HEARTBEAT_MS,
    private val changeDebounceMs: Long = CHANGE_DEBOUNCE_MS
) {
    private var lastLevel: Int? = null
    private var lastCharging: Boolean? = null
    private var lastSentAtMs: Long = 0L

    fun shouldSend(level: Int, charging: Boolean, nowMs: Long): Boolean {
        val prevLevel = lastLevel
        val prevCharging = lastCharging
        // 第一次采样：没有基准，直接发。
        if (prevLevel == null || prevCharging == null) return true

        val elapsed = nowMs - lastSentAtMs
        if (elapsed >= heartbeatMs) return true
        if (level == prevLevel && charging == prevCharging) return false
        if (charging != prevCharging) return true
        if (abs(level - prevLevel) >= IMMEDIATE_DELTA_PERCENT) return true
        return elapsed >= changeDebounceMs
    }

    /**
     * 距下一次兜底上报还需等待的毫秒数（issue #160）。
     *
     * 心跳节拍必须按「距上次**成功上报**」计算，而不是相对 `start()` 的固定 60s 网格：
     * 广播触发的上报不落在网格上，固定网格会把兜底间隔拉成 `60s + 相位差`
     * （真机/复演 61~114s）。调用方每轮都用本函数重算 `delay()`，一次 off-grid 上报
     * 之后的下一个 tick 就落回 `lastSentAtMs + heartbeatMs`。
     *
     * - 还没有基准（从未成功上报）→ 等满一个 [heartbeatMs]；
     * - 已到点 → 返回下限 [MIN_HEARTBEAT_TICK_MS]，让「未连接 / 发送失败」按 1s 重试，
     *   而不是空转或再等 60s；
     * - 时钟回拨（剩余量 > [heartbeatMs]）→ 钳到 [heartbeatMs]，不让一次负 elapsed 拖出更长的等待；
     * - 其余情况返回真实剩余量，即文档承诺的「最迟 60s」。
     */
    fun msUntilHeartbeat(nowMs: Long): Long {
        if (lastLevel == null) return heartbeatMs
        val retryFloor = MIN_HEARTBEAT_TICK_MS.coerceAtMost(heartbeatMs)
        return (heartbeatMs - (nowMs - lastSentAtMs)).coerceIn(retryFloor, heartbeatMs)
    }

    /** 发送成功后记录本次值，作为下一轮节流的基准。 */
    fun markSent(level: Int, charging: Boolean, nowMs: Long) {
        lastLevel = level
        lastCharging = charging
        lastSentAtMs = nowMs
    }

    /** 清空基准（服务重启 / 断开重连），下一次采样必定发送。 */
    fun reset() {
        lastLevel = null
        lastCharging = null
        lastSentAtMs = 0L
    }

    companion object {
        /** 电量不变时的兜底上报周期：60s（**最迟**，节拍按上次成功上报对齐，issue #160）。 */
        const val HEARTBEAT_MS = 60_000L

        /**
         * 兜底节拍的最小步长（issue #160）。
         *
         * 已到点却没能发出去（未握手 / 发送失败）时按它重试，既不空转，也不至于再等一个
         * 完整周期 —— 否则「断开重连后电量没变」的窗口会再拖 60s。
         */
        const val MIN_HEARTBEAT_TICK_MS = 1_000L

        /** 1% 变化的防抖窗口：5s 内只发一次。 */
        const val CHANGE_DEBOUNCE_MS = 5_000L

        /** 达到该变化幅度时不受防抖限制，立即上报。 */
        const val IMMEDIATE_DELTA_PERCENT = 2
    }
}
