package com.dilinkauto.server.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * issue #269：reset 后主动重连的退避节奏。
 *
 * 验收线是「ADB controller reset → TCP ADB connected ≤ 10s」，退避本身不能把
 * 这段时间吃掉：1s + 2s + 4s = 7s，给单次 connect 留约 3s。
 */
class TcpAdbReconnectPolicyTest {

    @Test
    fun `退避序列固定为 1s 2s 4s`() {
        assertEquals(1_000L, TcpAdbReconnectPolicy.delayMsBeforeAttempt(0))
        assertEquals(2_000L, TcpAdbReconnectPolicy.delayMsBeforeAttempt(1))
        assertEquals(4_000L, TcpAdbReconnectPolicy.delayMsBeforeAttempt(2))
    }

    @Test
    fun `超过三次返回 null 表示不再重试`() {
        assertNull(TcpAdbReconnectPolicy.delayMsBeforeAttempt(3))
        assertNull(TcpAdbReconnectPolicy.delayMsBeforeAttempt(99))
        assertEquals(3, TcpAdbReconnectPolicy.maxAttempts())
    }

    @Test
    fun `累计退避不超过 10s 验收线`() {
        assertEquals(7_000L, TcpAdbReconnectPolicy.totalBackoffMs())
    }
}
