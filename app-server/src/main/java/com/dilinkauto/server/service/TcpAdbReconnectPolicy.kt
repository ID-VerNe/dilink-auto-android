package com.dilinkauto.server.service

/**
 * issue #269：ADB 控制器 reset 后的主动重连退避。
 *
 * 现场 62s 空窗的根因不是「连不上」，而是 reset 之后没有任何东西再发起连接：
 * 控制通道在断网期间仍有心跳，`checkAndAdvance` 不会被触发。这里把重试节奏钉成
 * 固定常量，避免退避本身又把恢复时间拖过 10s 验收线。
 */
internal object TcpAdbReconnectPolicy {

    /** 第 1/2/3 次连接前的等待；累计 7s，给单次 connect 留 ~3s。 */
    private val DELAYS_MS = longArrayOf(1_000L, 2_000L, 4_000L)

    /** 返回第 attempt 次（0-based）连接前的等待；越界返回 null，表示不再重试。 */
    fun delayMsBeforeAttempt(attempt: Int): Long? = DELAYS_MS.getOrNull(attempt)

    fun maxAttempts(): Int = DELAYS_MS.size

    /** 全部退避之和，供验收对照（≤ 10s）。 */
    fun totalBackoffMs(): Long = DELAYS_MS.sum()
}
